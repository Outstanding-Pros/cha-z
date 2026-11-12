"""차~즈 로컬 서버: AI 프레임 생성, 음성(TTS/STT), 인텐트 LLM 폴백, 차량↔폰 동기화(Supabase 대체), 폰 PWA 서빙.

실행:  uvicorn app.main:app --host 0.0.0.0 --port 8787
에뮬레이터에서 접근: http://10.0.2.2:8787  (8000번은 이 PC에서 다른 앱이 사용 중)
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import threading
import time
from datetime import date
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parent.parent
load_dotenv(ROOT / ".env", override=True)

from fastapi import File, Form, UploadFile  # noqa: E402
from fastapi.responses import Response  # noqa: E402

from . import frames, intent, prompts, providers, voice  # noqa: E402  (.env 로드 후 import)

log = logging.getLogger("photolive")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

DATA = ROOT / "data" / "frames"
DATA.mkdir(parents=True, exist_ok=True)
POIS: dict[str, dict] = {p["id"]: p for p in json.loads((Path(__file__).parent / "pois.json").read_text("utf-8"))}

_locks: dict[str, threading.Lock] = {}
_locks_guard = threading.Lock()

app = FastAPI(title="Cha~z Local Server", version="0.2.0")

# 차량 ↔ 폰 동기화 (Supabase 대체, T10) + 폰 컴패니언 PWA (companion-web/)
from fastapi.staticfiles import StaticFiles  # noqa: E402
from fastapi.responses import HTMLResponse  # noqa: E402

from . import sync  # noqa: E402

from . import camera  # noqa: E402
from .guard import guard  # noqa: E402

app.middleware("http")(guard)  # 외부(Cloudflare Tunnel·공인 IP) 요청 보호 — guard.py
app.include_router(sync.router)
app.include_router(camera.router)


# 폰용 HTTPS 리스너 (T11). 차량(에뮬레이터)은 그대로 http://10.0.2.2:8787, 폰은 QR 의 https://<LAN IP>:8788.
# 같은 프로세스에서 띄워야 SSE 구독·카메라 프레임(메모리)을 공유한다. CHAZ_HTTPS_PORT=0 이면 끈다.
import asyncio  # noqa: E402
import contextlib  # noqa: E402
import socket  # noqa: E402

import uvicorn  # noqa: E402

_https: dict = {}


class _SideServer(uvicorn.Server):
    @contextlib.contextmanager
    def capture_signals(self):  # 신호 처리는 본 서버에 맡긴다
        yield


def _port_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind(("0.0.0.0", port))
            return True
        except OSError:
            return False


@app.on_event("startup")
async def _start_https() -> None:
    port = int(os.getenv("CHAZ_HTTPS_PORT", "8788"))
    if os.getenv("PUBLIC_BASE_URL", "").startswith("https://"):
        port = 0  # 도메인(Cloudflare)이 HTTPS 를 맡으므로 자체 서명 리스너는 필요 없다
    if port <= 0 or _https:
        return
    if not _port_free(port):
        log.warning("HTTPS 포트 %d 사용 중 — 폰 카메라 모드(P5)는 HTTPS 가 필요합니다", port)
        return
    from .tls import ensure_cert

    cert, key = ensure_cert([sync.lan_ip()])
    cfg = uvicorn.Config(app, host="0.0.0.0", port=port, ssl_certfile=str(cert), ssl_keyfile=str(key),
                         lifespan="off", log_config=None)  # 본 서버 로그 설정을 덮어쓰지 않게 (access_log=False 도 전역이라 쓰지 않음)
    server = _SideServer(cfg)

    async def run() -> None:
        try:
            await server.serve()
        except (SystemExit, Exception) as e:  # 바인드 실패 등이 본 서버를 죽이지 않게
            log.warning("HTTPS 리스너 종료: %r", e)
            sync.HTTPS_PORT = None

    _https["server"] = server
    _https["task"] = asyncio.create_task(run())
    sync.HTTPS_PORT = port
    log.info("폰용 HTTPS: %s/app/", sync.public_base())


@app.on_event("shutdown")
async def _stop_https() -> None:
    server = _https.pop("server", None)
    task = _https.pop("task", None)
    if server:
        server.should_exit = True
    if task:
        with contextlib.suppress(Exception):
            await asyncio.wait_for(task, 5)


_PWA = ROOT.parent / "companion-web" / "public"
_PWA.mkdir(parents=True, exist_ok=True)
@app.get("/app/", include_in_schema=False)
@app.get("/app/index.html", include_in_schema=False)
def _pwa_index():
    """index.html 의 app.js·style.css 주소에 파일 버전(수정 시각)을 붙여 준다.
    Cloudflare 가 .js/.css 에 브라우저 4시간 캐시를 강제해서, 주소가 같으면 폰이 옛 app.js 를 계속 쓴다."""
    html = (_PWA / "index.html").read_text(encoding="utf-8")
    for name in ("app.js", "style.css"):
        f = _PWA / name
        if f.exists():
            html = re.sub(rf'{re.escape(name)}(\?v=[^"]*)?"', f'{name}?v={int(f.stat().st_mtime)}"', html)
    return HTMLResponse(html, headers={"Cache-Control": "no-cache"})


app.mount("/app", StaticFiles(directory=_PWA, html=True), name="pwa")


@app.middleware("http")
async def _pwa_no_stale(request, call_next):
    """폰 PWA 파일은 매번 새로 확인하게 한다(ETag 로 바뀐 것만 받음).
    헤더가 없으면 Cloudflare 가 브라우저에 4시간 캐시를 붙여, 새 화면(index.html)에 옛 app.js 가 섞여 버튼이 안 먹는다."""
    resp = await call_next(request)
    if request.url.path.startswith("/app"):
        resp.headers["Cache-Control"] = "no-cache"
    return resp


class GenerateRequest(BaseModel):
    poiId: str | None = Field(None, description="pois.json 의 id. 없으면 region+scene 사용")
    region: str | None = Field(None, description="예: 제주")
    placeName: str | None = Field(None, description="캡션에 표시할 장소명")
    scene: str | None = Field(None, description="영문 풍경 묘사 (poiId 없을 때)")
    theme: str = Field(prompts.DEFAULT_THEME, description="sunset | fantasy | cute | retro")
    extra: str | None = Field(None, description="추가 묘사 (최대 200자)")
    force: bool = Field(False, description="true면 캐시 무시하고 다시 생성")


def _lock_for(key: str) -> threading.Lock:
    with _locks_guard:
        return _locks.setdefault(key, threading.Lock())


def _urls(req: Request, fid: str) -> dict:
    base = str(req.base_url).rstrip("/")
    return {
        "frameUrl": f"{base}/frames/{fid}.png",
        "backgroundUrl": f"{base}/frames/{fid}/background.png",
        "previewUrl": f"{base}/frames/{fid}/preview.jpg",
    }


def _key_configured() -> bool:
    key = os.getenv("BIZROUTER_API_KEY", "")
    return key.startswith("sk-br-") and key.isascii()  # .env.example 의 한글 placeholder 제외


@app.get("/health")
def health(request: Request):
    from .guard import is_external

    if is_external(request):  # 외부에는 살아 있는지만 (모델·키 설정 여부는 숨김)
        return {"ok": True}
    return {
        "ok": True,
        "provider": os.getenv("IMAGE_PROVIDER", "mock"),
        "model": os.getenv("IMAGE_MODEL"),
        "keyConfigured": _key_configured(),
    }


@app.get("/pois")
def list_pois():
    return list(POIS.values())


@app.get("/themes")
def list_themes():
    return [{"key": k, "label": v["label"]} for k, v in prompts.THEMES.items()]


@app.get("/layout")
def get_layout():
    return frames.layout()


@app.post("/frames/generate")
def generate(body: GenerateRequest, request: Request):
    if body.poiId:
        poi = POIS.get(body.poiId)
        if not poi:
            raise HTTPException(404, f"알 수 없는 poiId: {body.poiId}")
        scene, place = poi["scene"], poi["name"]
    elif body.scene:
        scene, place = body.scene, body.placeName or body.region or "드라이브"
    else:
        raise HTTPException(400, "poiId 또는 scene 중 하나는 필요합니다")

    if body.theme not in prompts.THEMES:
        raise HTTPException(400, f"theme 은 {list(prompts.THEMES)} 중 하나")

    prompt = prompts.build_prompt(scene, body.theme, body.extra)
    provider = os.getenv("IMAGE_PROVIDER", "mock")
    model = os.getenv("IMAGE_MODEL", "")
    fid = hashlib.sha256(f"{provider}|{model}|{prompt}|{place}".encode()).hexdigest()[:16]
    out_dir = DATA / fid
    meta_path = out_dir / "meta.json"

    with _lock_for(fid):
        if meta_path.exists() and not body.force:
            meta = json.loads(meta_path.read_text("utf-8"))
            return {**meta, **_urls(request, fid), "cached": True, "layout": frames.layout()}

        t0 = time.time()
        try:
            bg = providers.generate(prompt)
        except providers.ImageGenError as e:
            log.warning("generation failed: %s", e)
            raise HTTPException(502, str(e))
        gen_sec = round(time.time() - t0, 1)

        title = "PHOTO LIVE"
        caption = f"{place} · {date.today():%Y.%m.%d}"
        frame_png = frames.compose_frame(bg, title, caption)

        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "background.png").write_bytes(bg)
        (out_dir / "frame.png").write_bytes(frame_png)
        (out_dir / "preview.jpg").write_bytes(frames.preview_with_placeholders(frame_png))
        meta = {
            "frameId": fid,
            "place": place,
            "theme": body.theme,
            "provider": provider,
            "model": model,
            "prompt": prompt,
            "generationSeconds": gen_sec,
        }
        meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), "utf-8")
        log.info("frame %s generated in %ss (%s/%s)", fid, gen_sec, provider, model)
        return {**meta, **_urls(request, fid), "cached": False, "layout": frames.layout()}


@app.post("/frames/opentop")
async def frames_opentop(interior: UploadFile = File(...), exterior: UploadFile | None = File(None)):
    """✨ AI 오픈카: 실내 사진(+ 같은 순간 후방 풍경) → 지붕 연 오픈카 사진(JPEG).
    얼굴이 담긴 사진이라 서버에 저장하지 않고, 로그에도 크기·시간만 남긴다 (CLAUDE.md 규칙 10)."""
    import asyncio as _aio
    import io as _io

    from PIL import Image as _Image

    def _jpeg(raw: bytes, max_side: int) -> bytes:
        try:
            im = _Image.open(_io.BytesIO(raw)).convert("RGB")
        except Exception:
            raise HTTPException(400, "이미지를 읽을 수 없어요")
        im.thumbnail((max_side, max_side))
        buf = _io.BytesIO()
        im.save(buf, "JPEG", quality=90)
        return buf.getvalue()

    raw = await interior.read()
    if not raw or len(raw) > 8 * 1024 * 1024:
        raise HTTPException(413 if raw else 400, "실내 사진은 8MB 이하")
    images = [_jpeg(raw, 1600)]
    if exterior is not None:
        ext = await exterior.read()
        if ext and len(ext) <= 8 * 1024 * 1024:
            images.append(_jpeg(ext, 1024))
    scenery = prompts.OPENTOP_SCENERY_FROM_IMAGE if len(images) > 1 else prompts.OPENTOP_SCENERY_DEFAULT
    prompt = prompts.OPENTOP_PROMPT.format(scenery=scenery)
    model = os.getenv("OPENTOP_MODEL", "google/gemini-3.1-flash-image")

    t0 = time.time()
    try:
        out = await _aio.to_thread(providers.edit_chat_api, prompt, images, model)
    except providers.ImageGenError as e:
        log.warning("opentop failed: %s", str(e)[:200])
        raise HTTPException(502, "AI 편집에 실패했어요")
    sec = round(time.time() - t0, 1)
    jpg = _jpeg(out, 2048)
    log.info("opentop %.1fs in=%d ext=%s out=%d", sec, len(raw), len(images) > 1, len(jpg))
    return Response(jpg, media_type="image/jpeg", headers={"X-Seconds": str(sec), "X-Model": model, "Cache-Control": "no-store"})


@app.post("/frames/mix")
async def frames_mix(
    interior: UploadFile = File(...), exterior: UploadFile = File(...), view: str = Form("FRONT"),
    exterior2: UploadFile | None = File(None),
):
    """🎞 앨범 만들기: 실내 사진 + 같은 순간 바깥 풍경 → 한 장(JPEG).
    view = FRONT|REAR (사람을 그 풍경 앞에) | PANORAMA (exterior=전방, exterior2=후방을 이어 붙이고 가운데에 사람).
    얼굴 사진이라 저장하지 않고 로그엔 크기·시간만."""
    import asyncio as _aio
    import io as _io

    from PIL import Image as _Image

    def _jpeg(raw: bytes, max_side: int) -> bytes:
        try:
            im = _Image.open(_io.BytesIO(raw)).convert("RGB")
        except Exception:
            raise HTTPException(400, "이미지를 읽을 수 없어요")
        im.thumbnail((max_side, max_side))
        buf = _io.BytesIO()
        im.save(buf, "JPEG", quality=90)
        return buf.getvalue()

    if view not in prompts.MIX_VIEW and view != "PANORAMA":
        raise HTTPException(400, "view 는 FRONT, REAR, PANORAMA")
    raws = [await interior.read(), await exterior.read()]
    if view == "PANORAMA":
        if exterior2 is None:
            raise HTTPException(400, "파노라마는 전방·후방 두 장이 필요해요")
        raws.append(await exterior2.read())
    for b in raws:
        if not b or len(b) > 8 * 1024 * 1024:
            raise HTTPException(413 if b else 400, "사진은 8MB 이하")
    images = [_jpeg(raws[0], 1600)] + [_jpeg(b, 1280) for b in raws[1:]]
    prompt = prompts.PANORAMA_PROMPT if view == "PANORAMA" else prompts.MIX_PROMPT.format(view=prompts.MIX_VIEW[view])
    model = os.getenv("MIX_MODEL", os.getenv("OPENTOP_MODEL", "google/gemini-3.1-flash-image"))
    t0 = time.time()
    try:
        out = await _aio.to_thread(providers.edit_chat_api, prompt, images, model)
    except providers.ImageGenError as e:
        log.warning("mix failed: %s", str(e)[:200])
        raise HTTPException(502, "AI 조합에 실패했어요")
    sec = round(time.time() - t0, 1)
    jpg = _jpeg(out, 2048)
    log.info("mix %s %.1fs out=%d", view, sec, len(jpg))
    return Response(jpg, media_type="image/jpeg", headers={"X-Seconds": str(sec), "X-Model": model, "Cache-Control": "no-store"})


TTS_CACHE = ROOT / "data" / "tts"
TTS_CACHE.mkdir(parents=True, exist_ok=True)


class TtsRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=300)
    voice: str | None = None


@app.post("/voice/tts")
def voice_tts(body: TtsRequest):
    """텍스트 → mp3. 같은 문장(예고 멘트, 카운트다운)은 캐시에서 바로 반환."""
    key = hashlib.sha256(
        f"{os.getenv('TTS_MODEL', '')}|{body.voice or os.getenv('TTS_VOICE', '')}|{body.text}".encode()
    ).hexdigest()[:16]
    path = TTS_CACHE / f"{key}.mp3"
    cached = path.exists()
    if not cached:
        t0 = time.time()
        try:
            path.write_bytes(voice.tts(body.text, body.voice))
        except voice.VoiceError as e:
            log.warning("tts failed: %s", e)
            raise HTTPException(502, str(e))
        log.info("tts %s generated in %.1fs", key, time.time() - t0)
    return FileResponse(path, media_type="audio/mpeg", headers={"X-Cached": str(cached).lower()})


@app.post("/voice/stt")
async def voice_stt(file: UploadFile = File(...)):
    """음성 파일(wav/mp3/m4a) → 텍스트 + 촬영 트리거 판정."""
    audio = await file.read()
    if not audio:
        raise HTTPException(400, "빈 오디오")
    if len(audio) > 5 * 1024 * 1024:
        raise HTTPException(413, "오디오는 5MB 이하")
    t0 = time.time()
    try:
        text = voice.stt(audio, file.filename or "audio.wav", file.content_type or "audio/wav")
    except voice.VoiceError as e:
        log.warning("stt failed: %s", e)
        raise HTTPException(502, str(e))
    trigger = voice.find_trigger(text)
    sec = round(time.time() - t0, 1)
    log.info("stt %.1fs text=%r trigger=%s", sec, text, trigger)
    return {"text": text, "trigger": trigger, "shoot": trigger is not None, "seconds": sec}


def _file(fid: str, name: str, media: str) -> FileResponse:
    if not fid.isalnum():
        raise HTTPException(400, "bad id")
    p = DATA / fid / name
    if not p.exists():
        raise HTTPException(404, "not found")
    return FileResponse(p, media_type=media)


class IntentRequest(BaseModel):
    text: str = Field(min_length=1, max_length=200)


@app.post("/intent/parse")
def intent_parse(body: IntentRequest):
    """규칙 파서 실패 시 LLM 분류 (SPEC Edge Function intent-parse 대체)."""
    t0 = time.time()
    try:
        out = intent.classify(body.text)
    except Exception as e:  # 네트워크·모델 오류는 UNKNOWN 으로 (앱이 "다시 말씀해 주세요")
        log.warning("intent parse 실패: %s", e)
        out = {"intent": "UNKNOWN", "durationSec": None, "count": None, "mood": None, "error": str(e)[:200]}
    out["seconds"] = round(time.time() - t0, 2)
    return out


@app.get("/frames/{fid}.png")
def get_frame(fid: str):
    return _file(fid, "frame.png", "image/png")


@app.get("/frames/{fid}/background.png")
def get_background(fid: str):
    return _file(fid, "background.png", "image/png")


@app.get("/frames/{fid}/preview.jpg")
def get_preview(fid: str):
    return _file(fid, "preview.jpg", "image/jpeg")
