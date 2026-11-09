"""차~즈 로컬 서버: AI 프레임 생성, 음성(TTS/STT), 인텐트 LLM 폴백, 차량↔폰 동기화(Supabase 대체), 폰 PWA 서빙.

실행:  uvicorn app.main:app --host 0.0.0.0 --port 8787
에뮬레이터에서 접근: http://10.0.2.2:8787  (8000번은 이 PC에서 다른 앱이 사용 중)
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
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

from fastapi import File, UploadFile  # noqa: E402
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

from . import sync  # noqa: E402

app.include_router(sync.router)
_PWA = ROOT.parent / "companion-web" / "public"
_PWA.mkdir(parents=True, exist_ok=True)
app.mount("/app", StaticFiles(directory=_PWA, html=True), name="pwa")


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
def health():
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
