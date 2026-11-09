"""intent-parse 대체: 규칙 파서(앱)가 실패한 발화를 텍스트 모델로 분류한다 (SPEC 파서 사양 6).

모델은 TEXT_MODEL (기본 google/gemini-3.1-flash-lite — 실측 ~1.1초, 2초 타임아웃 안).
앱은 2초를 넘기면 UNKNOWN 으로 처리한다.
"""
from __future__ import annotations

import json
import os
import re

import httpx

INTENTS = [
    "CAPTURE_DEFAULT", "CAPTURE_CUSTOM", "RETAKE", "STOP", "BGM_PLAY", "BGM_STOP",
    "HANDOFF_PHONE", "SEND_PHONE", "LIGHT_MOOD", "HELP", "UNKNOWN",
]

SYSTEM = (
    "너는 차량용 사진 앱 '차~즈'의 음성 명령 분류기다. 사용자의 한국어 발화를 아래 인텐트 중 하나로 분류하고 JSON 한 줄만 출력한다.\n"
    "CAPTURE_DEFAULT: 그냥 찍어 달라 / CAPTURE_CUSTOM: 시간이나 장수를 지정해 찍기 / RETAKE: 다시 찍기 / STOP: 촬영 멈춤\n"
    "BGM_PLAY: 노래 틀기(mood: 신나는|잔잔한|감성|드라이브|null) / BGM_STOP: 노래 끄기 / HANDOFF_PHONE: 폰에서 편집\n"
    "SEND_PHONE: 완성본을 폰으로 보내기 / LIGHT_MOOD: 조명·분위기 바꾸기 / HELP: 할 수 있는 일 묻기 / UNKNOWN: 그 외\n"
    '형식: {"intent": "...", "durationSec": int|null, "count": int|null, "mood": str|null}'
)


def classify(text: str) -> dict:
    base = os.getenv("BIZROUTER_BASE_URL", "https://bizrouter.ai/api/v1").rstrip("/")
    model = os.getenv("TEXT_MODEL", "google/gemini-3.1-flash-lite")
    r = httpx.post(
        f"{base}/chat/completions",
        headers={"Authorization": f"Bearer {os.getenv('BIZROUTER_API_KEY', '')}"},
        json={
            "model": model,
            "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": text}],
            "max_completion_tokens": 120,
            "temperature": 0,
        },
        timeout=float(os.getenv("TEXT_TIMEOUT", "4")),
    )
    r.raise_for_status()
    content = r.json()["choices"][0]["message"]["content"] or ""
    m = re.search(r"\{.*\}", content, re.S)
    data = json.loads(m.group(0)) if m else {}
    intent = data.get("intent") if data.get("intent") in INTENTS else "UNKNOWN"
    def _int(v):
        return v if isinstance(v, int) and v > 0 else None
    return {
        "intent": intent,
        "durationSec": _int(data.get("durationSec")),
        "count": _int(data.get("count")),
        "mood": data.get("mood") if isinstance(data.get("mood"), str) else None,
        "model": model,
    }
