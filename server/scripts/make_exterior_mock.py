"""외부 카메라 시뮬레이션용 이미지 생성 (assets/exterior-mock/).

에뮬레이터에 차량 전·후방 카메라가 없어서(docs/spike.md) 비즈라우터 이미지 모델로
대시캠 시점 풍경을 만든다. 앱은 이 이미지를 천천히 패닝해 '사전 녹화 주행 영상(시뮬레이션)'으로 쓴다.
실제 주행 영상(mp4)이 assets/exterior-mock/ 에 들어오면 그쪽이 우선이다.

실행: server/.venv/Scripts/python scripts/make_exterior_mock.py
"""
import io
import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
load_dotenv(ROOT / ".env", override=True)
os.environ["IMAGE_SIZE"] = "1536x1024"  # 가로 (카메라 화면비에 가깝게)

from app.providers import generate  # noqa: E402

OUT = ROOT.parent / "assets" / "exterior-mock"

COMMON = (
    "Photorealistic wide-angle automotive camera footage still, 16:9, natural daylight colors, "
    "no people, no text, no logos, no license plates, no other cars close by, no accidents, calm safe driving."
)
SHOTS = {
    "front_01.jpg": "View from a car's front windshield-mounted camera driving along a calm Jeju island coastal road, "
                    "turquoise sea on the right, black volcanic rocks, road center line leading to the horizon, "
                    "late afternoon golden light. " + COMMON,
    "rear_01.jpg": "View from a car's rear-facing camera looking back along a Jeju island coastal road the car just drove, "
                   "road receding into the distance, sea on the left, green hills and stone walls, soft afternoon light. " + COMMON,
}


def to_720p(raw: bytes) -> Image.Image:
    im = Image.open(io.BytesIO(raw)).convert("RGB")
    w = 1280
    h = round(im.height * w / im.width)
    im = im.resize((w, h), Image.LANCZOS)
    top = max(0, (h - 720) // 2)
    return im.crop((0, top, 1280, top + 720))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, prompt in SHOTS.items():
        path = OUT / name
        if path.exists() and "--force" not in sys.argv:
            print("skip", name)
            continue
        print("generating", name, "...", flush=True)
        to_720p(generate(prompt)).save(path, quality=88)
        print("saved", path, path.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
