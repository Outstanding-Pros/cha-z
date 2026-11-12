"""외부 공개용 요청 보호 (Cloudflare Tunnel 로 <서버 도메인> 를 열 때).

- 이 PC·LAN(에뮬레이터 10.0.2.2, 같은 Wi-Fi 폰)에서 온 요청은 지금처럼 그대로 통과.
- 외부 요청 = Cloudflare 를 거친 요청(cf-connecting-ip 헤더) 또는 공인 IP 에서 직접 온 요청.
  * 폰에 필요한 경로(PWA·공유 링크·페어링 수락·사진 받기·카메라 프레임)는 토큰 없이 허용.
  * 나머지(차량 전용·비즈라우터 사용 기능)는 X-Chaz-Token == CHAZ_APP_TOKEN 이어야 한다.
  * 비즈라우터 사용 기능은 IP 당 분당 횟수 + 하루 전체 횟수 상한 (토큰이 APK 에서 새어도 요금 폭탄 방지).
토큰 값은 server/.env 와 car-app/local.properties 에만 둔다.
"""
from __future__ import annotations

import hmac
import ipaddress
import os
import time
from collections import defaultdict, deque
from datetime import date

from fastapi import Request
from fastapi.responses import JSONResponse

PUBLIC_PREFIXES = ("/app", "/s/", "/sync/file/", "/sync/events")
PUBLIC_EXACT = {
    ("GET", "/health"),
    ("GET", "/sync/time"),
    ("POST", "/sync/pairing-claim"),
    ("GET", "/sync/results"),
    ("POST", "/sync/share"),
    ("POST", "/sync/camera/frame"),
    ("POST", "/sync/camera/bundle"),  # 폰 직접 촬영 묶음 — 연결된 폰 id 로만 자기 차량에 전달
    ("POST", "/sync/voice"),  # 폰 음성 리모컨 — 연결된 폰 id 로만 자기 차량에 전달
}
AI_PATHS = ("/frames/generate", "/frames/opentop", "/voice/tts", "/voice/stt", "/intent/parse")

PER_MIN_GENERAL = int(os.getenv("CHAZ_RATE_PER_MIN", "600"))   # 폰 미리보기 0.5초 간격 = 분당 120
PER_MIN_AI = int(os.getenv("CHAZ_AI_PER_MIN", "20"))
DAILY_AI = int(os.getenv("CHAZ_DAILY_AI_LIMIT", "500"))
DAILY_IMAGE = int(os.getenv("CHAZ_DAILY_IMAGE_LIMIT", "30"))  # 이미지 생성은 가장 비싸서 따로 낮게

_hits: dict[tuple[str, str], deque] = defaultdict(deque)
_daily = {"day": date.today(), "n": 0, "img": 0}


def client_ip(request: Request) -> str:
    return request.headers.get("cf-connecting-ip") or (request.client.host if request.client else "")


def is_external(request: Request) -> bool:
    if request.headers.get("cf-connecting-ip"):
        return True
    try:
        ip = ipaddress.ip_address(request.client.host if request.client else "")
    except ValueError:
        return True
    return not (ip.is_loopback or ip.is_private)


def _is_public(method: str, path: str) -> bool:
    return (method, path) in PUBLIC_EXACT or any(path == p or path.startswith(p) for p in PUBLIC_PREFIXES)


def _rate_ok(key: tuple[str, str], limit: int) -> bool:
    q = _hits[key]
    now = time.monotonic()
    while q and now - q[0] > 60:
        q.popleft()
    if len(q) >= limit:
        return False
    q.append(now)
    return True


async def guard(request: Request, call_next):
    if not is_external(request):
        return await call_next(request)
    path, method, ip = request.url.path, request.method, client_ip(request)

    if not _rate_ok((ip, "all"), PER_MIN_GENERAL):
        return JSONResponse({"detail": "요청이 너무 많아요. 잠시 후 다시 시도해 주세요"}, status_code=429)

    if not _is_public(method, path):
        want = os.getenv("CHAZ_APP_TOKEN", "")
        got = request.headers.get("x-chaz-token", "")
        if not want or not hmac.compare_digest(want, got):
            return JSONResponse({"detail": "forbidden"}, status_code=403)

    if path.startswith(AI_PATHS):
        if not _rate_ok((ip, "ai"), PER_MIN_AI):
            return JSONResponse({"detail": "AI 요청이 너무 많아요"}, status_code=429)
        today = date.today()
        if _daily["day"] != today:
            _daily.update(day=today, n=0, img=0)
        if _daily["n"] >= DAILY_AI:
            return JSONResponse({"detail": "오늘 AI 사용량을 다 썼어요"}, status_code=429)
        if path.startswith(("/frames/generate", "/frames/opentop")):
            if _daily["img"] >= DAILY_IMAGE:
                return JSONResponse({"detail": "오늘 이미지 생성량을 다 썼어요"}, status_code=429)
            _daily["img"] += 1
        _daily["n"] += 1

    return await call_next(request)
