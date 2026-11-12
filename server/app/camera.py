"""폰 실내 카메라 중계 (SPEC P5 · T11).

폰 PWA 가 '실내 카메라 모드'에서 켜 둔 영상 스트림을 차량 촬영에 쓴다. 차량·폰은 직접 통신하지 않고 이 서버를 거친다.

| 누가 | 요청                              | 하는 일                                                  |
|------|-----------------------------------|----------------------------------------------------------|
| 폰   | POST /sync/camera/frame (preview) | 0.5초마다 저해상 프레임 → 차량 미리보기·클립·온라인 판정 |
| 차량 | GET  /sync/camera/status          | 폰 카메라가 켜져 있는지 (최근 4초 안에 프레임)           |
| 차량 | GET  /sync/camera/latest          | 최신 미리보기 프레임 (after=seq 보다 새것만)             |
| 차량 | POST /sync/camera/arm             | 예약 촬영: 서버 시각 atMs 에 찍으라고 폰에 capture.shot (카운트다운 '셋' 무렵) |
| 차량 | GET  /sync/camera/result/{shotId} | 예약한 컷의 JPEG (셔터 시각에 호출, 올 때까지 대기)       |
| 차량 | POST /sync/camera/shot            | 즉시 촬영 (예약 실패 시 대체): capture.shot → 업로드 → 그 JPEG 를 응답으로 |
| 폰   | POST /sync/camera/frame (shot)    | shotId 에 해당하는 고해상 프레임                          |
| 폰   | POST /sync/camera/frame (push)    | 폰 사용자가 직접 셔터 → 차량에 phone.photo 이벤트       |
| 차량 | GET  /sync/camera/pushed/{photoId}| 폰이 직접 찍어 보낸 사진 (한 번 가져가면 지움, 60초 보관) |
| 둘다 | GET  /sync/time                   | 서버 시각(ms) — 차량·폰이 서버 시계에 맞춰 예약 시각을 지킨다 |

예약 촬영이 필요한 이유: Cloudflare 를 거치면 이벤트가 폰에 닿기까지 0.3~1.8초. 셔터 순간에 '찍어'를 보내면
그만큼 늦게 찍힌다. 그래서 2초 이상 앞서 '이 시각에 찍어'를 보내고, 폰이 자기 시계(서버와 맞춘)로 그 순간을 찍는다.

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

from .sync import _car_of, _db, _paired_phones, _subscribers, publish

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
_shots: dict[str, tuple[str, asyncio.Future, float]] = {}  # shotId → (carDeviceId, 결과, 만든 시각)
SHOT_TTL_SEC = 30.0
_shot_at: dict[str, float] = {}
_pushed: dict[str, tuple[str, bytes, float]] = {}  # photoId → (carDeviceId, JPEG, 받은 시각) — 폰 직접 셔터
PUSH_TTL_SEC = 60.0  # 예약 촬영 shotId → 셔터 시각(서버 ms), 오차 로그용


def _new_shot(car: str) -> tuple[str, asyncio.Future]:
    now = time.time()
    for k, v in list(_shots.items()):  # 찾아가지 않은 예약 정리
        if now - v[2] > SHOT_TTL_SEC:
            _shots.pop(k, None)
    shot_id = secrets.token_urlsafe(9)
    fut: asyncio.Future = asyncio.get_running_loop().create_future()
    _shots[shot_id] = (car, fut, now)
    return shot_id, fut


@router.get("/sync/time")
def server_time():
    return {"t": time.time() * 1000}


def _online(car: str) -> _Latest | None:
    l = _latest.get(car)
    return l if l and time.time() - l.at < ONLINE_SEC else None


@router.post("/sync/camera/frame")
async def upload_frame(
    phoneDeviceId: str = Form(..., min_length=8, max_length=64),
    kind: str = Form(..., pattern="^(preview|shot|push)$"),
    shotId: str | None = Form(None, max_length=64),
    takenAtMs: float | None = Form(None, description="폰이 실제로 찍은 시각(서버 시계) — 예약 오차 확인용"),
    role: str = Form("INTERIOR", pattern="^(INTERIOR|FRONT|REAR)$", description="push 전용: 차량 어느 자리(실내·전방·후방) 사진인지"),
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
    if kind == "push":
        return await _push(car, data, role)
    entry = _shots.get(shotId or "")
    if not entry or entry[0] != car:
        raise HTTPException(410, "이미 끝난 촬영이에요")
    fut = entry[1]
    want = _shot_at.pop(shotId or "", None)
    if want and takenAtMs:
        log.info("예약 촬영 오차 %+.0fms (폰이 찍은 시각 - 셔터 시각)", takenAtMs - want)
    if not fut.done():
        fut.set_result(data)
    return {"ok": True}


async def _push(car: str, data: bytes, role: str) -> dict:
    """폰에서 직접 셔터를 누른 사진. 차량 앱이 SSE 를 듣고 있으면 phone.photo 로 알리고, 차가 가져가면 지운다."""
    now = time.time()
    for k, v in list(_pushed.items()):
        if now - v[2] > PUSH_TTL_SEC:
            _pushed.pop(k, None)
    if sum(1 for v in _pushed.values() if v[0] == car) >= 5:
        raise HTTPException(429, "차량이 아직 앞의 사진을 받고 있어요. 잠시 뒤 다시 찍어 주세요")
    photo_id = secrets.token_urlsafe(9)
    _pushed[photo_id] = (car, data, now)
    listening = bool(_subscribers.get(car))
    await publish(car, "phone.photo", {"photoId": photo_id, "role": role, "at": now})
    return {"ok": True, "delivered": listening}


@router.get("/sync/camera/pushed/{photo_id}")
def camera_pushed(photo_id: str, carDeviceId: str):
    entry = _pushed.get(photo_id)
    if not entry or entry[0] != carDeviceId:
        raise HTTPException(404, "없는 사진이에요")
    _pushed.pop(photo_id, None)
    return Response(entry[1], media_type="image/jpeg", headers={"Cache-Control": "no-store"})


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


class ArmRequest(BaseModel):
    carDeviceId: str = Field(min_length=8, max_length=64)
    atMs: float = Field(description="찍을 시각 (서버 시계, epoch ms)")
    index: int | None = None


@router.post("/sync/camera/arm")
async def camera_arm(body: ArmRequest):
    car = body.carDeviceId
    if not _online(car):
        raise HTTPException(409, "폰 카메라가 꺼져 있어요")
    lead = body.atMs - time.time() * 1000
    if not -500 < lead < 15_000:
        raise HTTPException(400, "예약 시각이 맞지 않아요")
    with _db() as con:
        phones = _paired_phones(con, car)
    shot_id, _ = _new_shot(car)
    _shot_at[shot_id] = body.atMs
    if len(_shot_at) > 200:
        _shot_at.pop(next(iter(_shot_at)))
    payload = {"shotId": shot_id, "index": body.index, "atMs": body.atMs, "at": time.time()}
    for p in phones:
        await publish(p, "capture.shot", payload)
    return {"shotId": shot_id, "leadMs": round(lead)}


@router.get("/sync/camera/result/{shot_id}")
async def camera_result(shot_id: str, carDeviceId: str, waitMs: int = 4000):
    entry = _shots.get(shot_id)
    if not entry or entry[0] != carDeviceId:
        raise HTTPException(404, "없는 촬영이에요")
    try:
        data = await asyncio.wait_for(asyncio.shield(entry[1]), min(max(waitMs, 0), 8000) / 1000)
    except asyncio.TimeoutError:
        raise HTTPException(504, "폰이 제때 사진을 보내지 않았어요")
    _shots.pop(shot_id, None)
    return Response(data, media_type="image/jpeg", headers={"Cache-Control": "no-store"})


@router.post("/sync/camera/shot")
async def camera_shot(body: ShotRequest):
    car = body.carDeviceId
    if not _online(car):
        raise HTTPException(409, "폰 카메라가 꺼져 있어요")
    with _db() as con:
        phones = _paired_phones(con, car)
    shot_id, fut = _new_shot(car)
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
