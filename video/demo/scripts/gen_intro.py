"""인트로 시안 이미지를 비즈라우터로 생성한다. 서버 코드(app.providers)를 그대로 쓰고 키는 server/.env 에서 읽는다.

  server/.venv/bin/python video/demo/scripts/gen_intro.py            # 없는 것만 생성
  server/.venv/bin/python video/demo/scripts/gen_intro.py pet_swap   # 지정한 것만 다시

결과: video/demo/public/intro/<id>.jpg  (AI 생성 이미지 — 화면에 'AI' 표기)
"""
from __future__ import annotations

import io
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "server"))
from dotenv import load_dotenv  # noqa: E402

load_dotenv(ROOT / "server/.env")
os.environ["IMAGE_QUALITY"] = "medium"  # 영상에 크게 나오므로 앱 기본(low)보다 한 단계 위
from PIL import Image  # noqa: E402

from app import providers  # noqa: E402

OUT = ROOT / "video/demo/public/intro"
OUT.mkdir(parents=True, exist_ok=True)

COMMON = (
    "Photorealistic, natural light, shot on a smartphone-quality car camera, no text, no logos, no brand marks, "
    "no license plates. Korean people. The driver keeps both hands on the steering wheel and looks at the road; "
    "nobody holds a phone."
)
INDOOR = "Wide-angle view from a camera mounted at the center of the dashboard, looking back at the front seats."

PORTRAIT, LANDSCAPE = "1024x1536", "1536x1024"

JOBS: dict[str, tuple[str, str]] = {
    # 정지 시안 (트립 로그 · 차박 · 계절 컨셉)
    "trip_family": (PORTRAIT, f"{INDOOR} A Korean family of four on a road trip, parents in front, two kids in the back seat laughing, "
                              f"bright blue sea and a coastal road of Jeju island visible through the side windows. Sunny afternoon. {COMMON}"),
    "night_camp": (PORTRAIT, "A white electric SUV parked at a quiet campsite at night with the rear tailgate open, a young Korean couple "
                             "sitting in the trunk under warm string lights and a blanket, a dense starry sky with the Milky Way above, "
                             f"pine trees silhouette. {COMMON}"),
    "blossom_friends": (PORTRAIT, f"{INDOOR} Three young Korean women friends in a car, the passenger and back-seat friend smiling, "
                                  f"a tunnel of pink cherry blossom trees visible through the windows and windshield reflection, spring morning. {COMMON}"),
    "snow_couple": (PORTRAIT, f"{INDOOR} A Korean couple in their thirties in a warm car interior, the passenger smiling softly, "
                              f"first snow falling outside on a quiet mountain road, cozy evening light. {COMMON}"),
    "sunset_solo": (PORTRAIT, f"{INDOOR} A Korean woman in her late twenties driving alone, calm content expression, "
                              f"golden sunset light flooding through the windshield, city highway and river visible outside. {COMMON}"),
    # 움직이는 시안 ② 오션 브릿지
    "ocean_dark": (PORTRAIT, "Front dashcam view from inside a car driving through a long dark highway tunnel near its exit, orange tunnel "
                             f"lights streaking, a bright opening ahead. Motion feel. {COMMON}"),
    "ocean_bridge": (LANDSCAPE, "Front dashcam view from a car driving onto a long suspension bridge over a wide turquoise sea, bright clear sky, "
                                f"horizon wide open, cables framing the road, sparkling water. Busan-like coastline far away. {COMMON}"),
    "ocean_friends1": (PORTRAIT, f"{INDOOR} Three Korean friends in their twenties, the passenger raising both arms in excitement, "
                                 f"bright sea light pouring through the windows as the car crosses a bridge. {COMMON}"),
    "ocean_friends2": (PORTRAIT, f"{INDOOR} Three Korean friends in their twenties laughing together, back-seat friend leaning forward "
                                 f"making a V sign, blue sea and bridge cables visible outside. {COMMON}"),
    # 움직이는 시안 ④ 펫 드라이버 (원본 → AI 필터로 자리 바꾸기)
    "pet_orig": (PORTRAIT, f"{INDOOR} A Korean woman driving, a happy golden retriever wearing a car safety harness sitting in the front "
                           f"passenger seat with tongue out, sunny countryside road outside. {COMMON}"),
    # 움직이는 시안 ⑤ 로드 무비 (예고편)
    "movie_road": (LANDSCAPE, "Cinematic front view from a car on an empty two-lane coastal highway at golden hour, long shadows, warm film "
                              f"grade, anamorphic feel, mountains and sea on the horizon. {COMMON}"),
    "movie_couple1": (PORTRAIT, f"{INDOOR} A young Korean couple, the passenger looking out the window with wind in her hair, "
                                f"warm golden-hour light, cinematic film grade. {COMMON}"),
    "movie_couple2": (PORTRAIT, f"{INDOOR} A young Korean couple laughing at a shared joke, the passenger turned toward the driver, "
                                f"warm golden-hour backlight, cinematic film grade. {COMMON}"),
}

# 이미지 편집(자리 바꾸기). 기획서 ④: 반려견은 하네스 착용, 결과물에 'AI 필터' 표기
EDITS = {
    # 가족 여행: 차 안 → 주변 풍경으로 (앱 L1 차창 합성처럼 지붕·차체 안쪽이 풍경으로 바뀐 컷)
    "family_open": ("trip_family",
                    "Edit this photo: keep the four people, their seats, seatbelts, poses and faces exactly the same, but remove the car "
                    "roof, pillars and rear interior so that the whole background becomes an open bright Jeju coastal landscape — blue sea, "
                    "black volcanic rocks, green hills and sky all around them, like riding in an open convertible. Photorealistic, same camera angle."),
    # 반려견: 사람이 운전 중인 원본 컷을 벚꽃길로 (손가락 튕기기 전)
    "pet_orig_blossom": ("pet_orig",
                         "Edit this photo: keep the woman driving and the golden retriever in its harness on the passenger seat exactly as "
                         "they are, but change the scenery outside every window to a road lined with blooming pink cherry blossom trees on a "
                         "bright spring day, a few petals in the air. Same camera angle. Photorealistic."),
    # 영상 v_pet 의 첫 프레임: 자리 바꾼 컷 + 벚꽃길
    "pet_blossom": ("pet_swap",
                    "Edit this photo: keep the golden retriever in the driver's seat with paws on the steering wheel and the woman "
                    "in the passenger seat exactly as they are, but change the scenery outside every window to a road lined with "
                    "blooming pink cherry blossom trees on a bright spring day, a few petals in the air. Same camera angle. Photorealistic."),
    "pet_swap": ("pet_orig",
                 "Edit this photo: swap the seats of the woman and the dog. The golden retriever (still wearing its harness) now sits "
                 "in the driver's seat with both front paws resting on the steering wheel, looking proud; the woman sits in the "
                 "passenger seat laughing. Keep the same car interior, camera angle, lighting and background. Photorealistic."),
}


def save(img_id: str, data: bytes) -> None:
    im = Image.open(io.BytesIO(data)).convert("RGB")
    im.save(OUT / f"{img_id}.jpg", quality=90)
    print(f"ok {img_id} {im.size}", flush=True)


def gen(img_id: str) -> None:
    size, prompt = JOBS[img_id]
    os.environ["IMAGE_SIZE"] = size  # 스레드 사이 경합이 없도록 size 별로 묶어 돌린다
    try:
        save(img_id, providers.generate_images_api(prompt))
    except Exception as e:  # noqa: BLE001
        print(f"FAIL {img_id} {type(e).__name__}: {str(e)[:200]}", flush=True)


def edit(img_id: str) -> None:
    src, prompt = EDITS[img_id]
    try:
        save(img_id, providers.edit_chat_api(prompt, [(OUT / f"{src}.jpg").read_bytes()]))
    except Exception as e:  # noqa: BLE001
        print(f"FAIL {img_id} {type(e).__name__}: {str(e)[:200]}", flush=True)


if __name__ == "__main__":
    want = set(sys.argv[1:])
    def todo(ids: list[str]) -> list[str]:
        return [i for i in ids if (i in want if want else not (OUT / f"{i}.jpg").exists())]

    for size in (PORTRAIT, LANDSCAPE):
        ids = todo([k for k, (s, _) in JOBS.items() if s == size])
        if ids:
            os.environ["IMAGE_SIZE"] = size
            with ThreadPoolExecutor(4) as ex:
                list(ex.map(gen, ids))
    for i in todo(list(EDITS)):
        edit(i)
