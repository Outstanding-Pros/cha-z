"""나레이션 음성 생성: src/narration.json 의 say 를 비즈라우터 TTS(server app.voice.tts)로 읽어 mp3 로 저장하고,
길이(초)를 같은 파일의 seconds 에 적는다. 자막(text)과 음성(say)은 같은 문장 (발음만 '차즈'·'에이아이'처럼 풀어 씀).

  server/.venv/bin/python video/demo/scripts/gen_narration.py          # 바뀌었거나 없는 것만
  server/.venv/bin/python video/demo/scripts/gen_narration.py spot     # 지정한 것만 다시

결과: video/demo/public/audio/narr/<id>.mp3
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "server"))
from dotenv import load_dotenv  # noqa: E402

load_dotenv(ROOT / "server/.env")
NARR_INSTR = "차분하고 친근한 설명 톤으로, 또박또박 자연스럽게 읽어 주세요. 제품 소개 영상의 내레이션입니다."
from app import voice  # noqa: E402

DEMO = ROOT / "video/demo"
LINES = DEMO / "src/narration.json"
OUT = DEMO / "public/audio/narr"
OUT.mkdir(parents=True, exist_ok=True)


def duration(p: Path) -> float:
    r = subprocess.run([str(DEMO / "scripts/ff.sh"), "ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "csv=p=0", str(p)], capture_output=True, text=True, check=True)
    return round(float(r.stdout.strip()), 2)


lines = json.loads(LINES.read_text())
want = set(sys.argv[1:])
for ln in lines:
    p = OUT / f"{ln['id']}.mp3"
    # 동승자 대사(u_*)는 voice·instr 로 목소리를 바꾼다
    instr = ln.get("instr", NARR_INSTR)
    h = hashlib.sha1((ln["say"] + instr + ln.get("voice", "")).encode()).hexdigest()[:10]
    if (want and ln["id"] not in want) or (not want and p.exists() and ln.get("hash") == h):
        continue
    os.environ["TTS_INSTRUCTIONS"] = instr
    p.write_bytes(voice.tts(ln["say"], ln.get("voice")))
    ln["seconds"], ln["hash"] = duration(p), h
    print(f"ok {ln['id']} {ln['seconds']}s", flush=True)
LINES.write_text(json.dumps(lines, ensure_ascii=False, indent=2) + "\n")
