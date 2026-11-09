"""고정 안내 문구 오디오 클립 생성 (assets/tts/).

SPEC 'TTS 문구 관리': 카운트다운 같은 고정 문구는 미리 합성해 두고, 네트워크가 없어도 재생한다.
카운트다운은 컷 시각에 정확히 맞춰야 해서 실시간 합성 대신 이 클립을 SoundPool 로 재생한다.

실행: server/.venv/Scripts/python scripts/make_tts_clips.py [--force]
"""
import sys
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
load_dotenv(ROOT / ".env", override=True)

from app import voice  # noqa: E402

OUT = ROOT.parent / "assets" / "tts"
CLIPS = {
    "cd_3.mp3": "셋!",
    "cd_2.mp3": "둘!",
    "cd_1.mp3": "하나!",
}

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, text in CLIPS.items():
        p = OUT / name
        if p.exists() and "--force" not in sys.argv:
            print("skip", name); continue
        p.write_bytes(voice.tts(text))
        print("saved", name, p.stat().st_size, "bytes")
