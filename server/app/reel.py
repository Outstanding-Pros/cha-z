"""릴스 스타일용 AI 단계 (스타일 기획서 11/13).

- POST /frames/scenery  전방+후방 풍경 → 넓은 풍경 파노라마(사람 없음)  — ② 오션 브릿지 '스티칭 + 좌우 아웃페인팅'
- POST /frames/night    실내 사진 → 창밖만 별하늘, 실내는 웜톤        — ③ 차박 나이트 '창밖 분리 + 별하늘 합성'
- POST /frames/petswap  실내 사진 → 운전석 사람과 조수석 반려견 자리 바꾸기 — ④ 펫 드라이버
- POST /reel/copy       장소·시각 등 → 제목·카피 (JSON)                 — ① 트립 로그 / ⑤ 로드 무비

얼굴이 담긴 사진은 저장하지 않고 로그엔 크기·시간만. 실패하면 502 → 앱이 기획서의 대체 경로로 만든다.
"""
from __future__ import annotations

import asyncio
import io
import json
import logging
import os
import re
import time

import httpx
from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.responses import Response
from PIL import Image
from pydantic import BaseModel, Field

from . import prompts, providers

log = logging.getLogger("chaz.reel")
router = APIRouter()


def _jpeg(raw: bytes, max_side: int) -> bytes:
    if not raw or len(raw) > 8 * 1024 * 1024:
        raise HTTPException(413 if raw else 400, "사진은 8MB 이하")
    try:
        im = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception:
        raise HTTPException(400, "이미지를 읽을 수 없어요")
    im.thumbnail((max_side, max_side))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=90)
    return buf.getvalue()


async def _edit(kind: str, prompt: str, images: list[bytes]) -> Response:
    model = os.getenv("MIX_MODEL", os.getenv("OPENTOP_MODEL", "google/gemini-3.1-flash-image"))
    t0 = time.time()
    try:
        out = await asyncio.to_thread(providers.edit_chat_api, prompt, images, model)
    except providers.ImageGenError as e:
        log.warning("%s failed: %s", kind, str(e)[:200])
        raise HTTPException(502, "AI 생성에 실패했어요")
    sec = round(time.time() - t0, 1)
    jpg = _jpeg(out, 2560)
    log.info("%s %.1fs out=%d", kind, sec, len(jpg))
    return Response(jpg, media_type="image/jpeg", headers={"X-Seconds": str(sec), "X-Model": model, "Cache-Control": "no-store"})


@router.post("/frames/scenery")
async def frames_scenery(front: UploadFile = File(...), rear: UploadFile = File(...)):
    return await _edit("scenery", prompts.SCENERY_PANORAMA_PROMPT, [_jpeg(await front.read(), 1280), _jpeg(await rear.read(), 1280)])


@router.post("/frames/night")
async def frames_night(interior: UploadFile = File(...)):
    return await _edit("night", prompts.NIGHT_PROMPT, [_jpeg(await interior.read(), 1600)])


@router.post("/frames/petswap")
async def frames_petswap(interior: UploadFile = File(...)):
    return await _edit("petswap", prompts.PETSWAP_PROMPT, [_jpeg(await interior.read(), 1600)])


class CopyRequest(BaseModel):
    style: str = Field(pattern="^(TRIP|MOVIE)$")
    place: str | None = Field(None, max_length=60)
    date: str | None = Field(None, max_length=30)
    time: str | None = Field(None, max_length=20)
    spots: list[str] = Field(default_factory=list, max_length=12)
    people: list[str] = Field(default_factory=list, max_length=6)


@router.post("/reel/copy")
def reel_copy(body: CopyRequest):
    """① 트립 로그: {title, line} / ⑤ 로드 무비: {title, lines[3], tagline}. 실패하면 502 (앱은 템플릿 카피)."""
    system = prompts.TRIP_COPY_SYSTEM if body.style == "TRIP" else prompts.MOVIE_COPY_SYSTEM
    user = json.dumps(body.model_dump(), ensure_ascii=False)
    base = os.getenv("BIZROUTER_BASE_URL", "https://bizrouter.ai/api/v1").rstrip("/")
    model = os.getenv("TEXT_MODEL", "google/gemini-3.1-flash-lite")
    t0 = time.time()
    try:
        r = httpx.post(
            f"{base}/chat/completions",
            headers={"Authorization": f"Bearer {os.getenv('BIZROUTER_API_KEY', '')}"},
            json={"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                  "max_completion_tokens": 300, "temperature": 0.9},
            timeout=float(os.getenv("COPY_TIMEOUT", "12")),
        )
        r.raise_for_status()
        content = r.json()["choices"][0]["message"]["content"] or ""
        m = re.search(r"\{.*\}", content, re.S)
        data = json.loads(m.group(0)) if m else {}
    except Exception as e:
        log.warning("copy 실패: %s", str(e)[:200])
        raise HTTPException(502, "카피를 만들지 못했어요")
    title = str(data.get("title") or "").strip()[:24]
    if not title:
        raise HTTPException(502, "카피를 만들지 못했어요")
    out = {"title": title, "seconds": round(time.time() - t0, 2)}
    if body.style == "TRIP":
        out["line"] = str(data.get("line") or "").strip()[:40]
    else:
        out["lines"] = [str(x).strip()[:30] for x in (data.get("lines") or [])][:3]
        out["tagline"] = str(data.get("tagline") or "").strip()[:30]
    log.info("copy %s %.2fs", body.style, out["seconds"])
    return out
