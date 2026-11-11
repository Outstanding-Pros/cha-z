"""폰 실내 카메라 중계 (SPEC P5 · T11).

폰 PWA 가 '실내 카메라 모드'에서 켜 둔 영상 스트림을 차량 촬영에 쓴다. 차량·폰은 직접 통신하지 않고 이 서버를 거친다.

| 누가 | 요청                              | 하는 일                                                  |
|------|-----------------------------------|----------------------------------------------------------|
| 폰   | POST /sync/camera/frame (preview) | 0.5초마다 저해상 프레임 → 차량 미리보기·클립·온라인 판정 |
| 차량 | GET  /sync/camera/status          | 폰 카메라가 켜져 있는지 (최근 4초 안에 프레임)           |
| 차량 | GET  /sync/camera/latest          | 최신 미리보기 프레임 (after=seq 보다 새것만)             |
| 차량 | POST /sync/camera/shot            | 폰에 capture.shot 이벤트 → 폰이 고해상 프레임 업로드 → 그 JPEG 를 응답으로 |
| 폰   | POST /sync/camera/frame (shot)    | shotId 에 해당하는 고해상 프레임                          |

얼굴이 담긴 프레임이라 디스크에 쓰지 않고 메모리에만 잠깐 둔다. 로그에도 남기지 않는다 (CLAUDE.md 규칙 10).
"""
from __future__ import annotations

import asyncio
import logging
import secrets
import time
from dataclasses import dataclass

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field

from .sync import _car_of, _db, _paired_phones, publish

log = logging.getLogger("photolive.camera")
router = APIRouter()

ONLINE_SEC = 4.0          # 이 시간 안에 미리보기가 왔으면 카메라 켜짐
SHOT_TIMEOUT_SEC = 3.5    # 고해상 프레임 기다리는 시간
MAX_PREVIEW = 1 * 1024 * 1024
MAX_SHOT = 8 * 1024 * 1024


@dataclass
class _Latest:
    seq: int
    at: float
    jpeg: bytes
    phone: str


_latest: dict[str, _Latest] = {}                    # carDeviceId → 최신 미리보기
_shots: dict[str, tuple[str, asyncio.Future]] = {}  # shotId → (carDeviceId, 결과)


def _online(car: str) -> _Latest | None:
    l = _latest.get(car)
    return l if l and time.time() - l.at < ONLINE_SEC else None


@router.post("/sync/camera/frame")
async def upload_frame(
    phoneDeviceId: str = Form(..., min_length=8, max_length=64),
    kind: str = Form(..., pattern="^(preview|shot)$"),
    shotId: str | None = Form(None, max_length=64),
    file: UploadFile = File(...),
):
    with _db() as con:
        car = _car_of(con, phoneDeviceId)
    if not car:
        raise HTTPException(404, "연결된 차량이 없어요. 차량 화면의 QR로 먼저 연결해 주세요")
    data = await file.read(MAX_SHOT + 1)
    if kind == "preview":
        if len(data) > MAX_PREVIEW:
            raise HTTPException(413, "미리보기 프레임이 너무 커요")
        prev = _latest.get(car)
        _latest[car] = _Latest((prev.seq + 1) if prev else 1, time.time(), data, phoneDeviceId)
        return {"ok": True}
    if len(data) > MAX_SHOT:
        raise HTTPException(413, "사진이 너무 커요")
    entry = _shots.get(shotId or "")
    if not entry or entry[0] != car:
        raise HTTPException(410, "이미 끝난 촬영이에요")
    fut = entry[1]
    if not fut.done():
        fut.set_result(data)
    return {"ok": True}


@router.get("/sync/camera/status")
def camera_status(carDeviceId: str):
    l = _online(carDeviceId)
    return {"online": l is not None, "ageMs": int((time.time() - l.at) * 1000) if l else None}


@router.get("/sync/camera/latest")
def camera_latest(carDeviceId: str, after: int = 0):
    l = _online(carDeviceId)
    if not l or l.seq <= after:
        return Response(status_code=204)
    return Response(l.jpeg, media_type="image/jpeg", headers={"X-Seq": str(l.seq), "Cache-Control": "no-store"})


class ShotRequest(BaseModel):
    carDeviceId: str = Field(min_length=8, max_length=64)
    sessionId: str | None = Field(None, max_length=64)
    index: int | None = None


@router.post("/sync/camera/shot")
async def camera_shot(body: ShotRequest):
    car = body.carDeviceId
    if not _online(car):
        raise HTTPException(409, "폰 카메라가 꺼져 있어요")
    with _db() as con:
        phones = _paired_phones(con, car)
    shot_id = secrets.token_urlsafe(9)
    fut: asyncio.Future = asyncio.get_running_loop().create_future()
    _shots[shot_id] = (car, fut)
    try:
        payload = {"shotId": shot_id, "sessionId": body.sessionId, "index": body.index, "at": time.time()}
        for p in phones:
            await publish(p, "capture.shot", payload)
        data = await asyncio.wait_for(fut, SHOT_TIMEOUT_SEC)
    except asyncio.TimeoutError:
        raise HTTPException(504, "폰이 제때 사진을 보내지 않았어요")
    finally:
        _shots.pop(shot_id, None)
    return Response(data, media_type="image/jpeg", headers={"Cache-Control": "no-store"})
