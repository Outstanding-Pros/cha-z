"""서버 음성 처리 (Pleos TextToSpeech / SpeechToText SDK 공개 전 대체 구현).

BizRouter OpenAI 호환 엔드포인트 사용:
  - TTS: POST /audio/speech         (기본 openai/gpt-4o-mini-tts)
  - STT: POST /audio/transcriptions (기본 openai/gpt-transcribe)
"""

from __future__ import annotations

import os
import re

import httpx

from .providers import ImageGenError, _base, _cfg

# 촬영 트리거 단어. 공백/문장부호 제거 후 부분 일치로 판정.
# SPEC: "치즈"는 일상 대화 오인식을 막기 위해 받지 않는다
TRIGGER_WORDS = ("차즈", "찍어", "찍자", "촬영")


class VoiceError(ImageGenError):
    pass


def _auth() -> dict[str, str]:
    return {"Authorization": f"Bearer {_cfg('BIZROUTER_API_KEY')}"}


def tts(text: str, voice: str | None = None) -> bytes:
    payload = {
        "model": os.getenv("TTS_MODEL", "openai/gpt-4o-mini-tts"),
        "input": text,
        "voice": voice or os.getenv("TTS_VOICE", "nova"),
        "response_format": "mp3",
    }
    instructions = os.getenv("TTS_INSTRUCTIONS", "밝고 친근한 여행 가이드 톤으로, 또박또박 말해 주세요.")
    if instructions:
        payload["instructions"] = instructions
    r = httpx.post(f"{_base()}/audio/speech", headers=_auth(), json=payload, timeout=60)
    if r.status_code >= 400:
        raise VoiceError(f"TTS {r.status_code}: {r.text[:300]}")
    return r.content


def stt(audio: bytes, filename: str, content_type: str) -> str:
    r = httpx.post(
        f"{_base()}/audio/transcriptions",
        headers=_auth(),
        data={"model": os.getenv("STT_MODEL", "openai/gpt-transcribe"), "language": "ko"},
        files={"file": (filename, audio, content_type)},
        timeout=60,
    )
    if r.status_code >= 400:
        raise VoiceError(f"STT {r.status_code}: {r.text[:300]}")
    return (r.json().get("text") or "").strip()


def find_trigger(text: str) -> str | None:
    norm = re.sub(r"[\s.,!?~]", "", text)
    for w in TRIGGER_WORDS:
        if w in norm:
            return w
    return None
