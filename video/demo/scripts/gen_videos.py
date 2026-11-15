"""인트로 시안 영상을 비즈라우터 동영상 API로 생성한다 (Seedance 2.5, 9:16, 첫 프레임 이미지 → 영상).

API (bizrouter.ai/llms-full.txt '동영상 생성', 2026-11-15 확인):
  POST /video/generation {model, prompt, duration_seconds, resolution, aspect_ratio, image{mime_type,data_base64}}
  → 202 {operation_id, status}
  GET  /video/generation/{id} → status pending|processing|completed|failed, completed 면 video_uri
  GET  /video/generation/{id}/download → mp4 (완료 후 30분 안에)

  server/.venv/bin/python video/demo/scripts/gen_videos.py            # 없는 것만
  server/.venv/bin/python video/demo/scripts/gen_videos.py v_family   # 지정한 것만 다시

결과: video/demo/public/intro/<id>.mp4 (git 제외)
"""
from __future__ import annotations

import base64
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "server"))
from dotenv import load_dotenv  # noqa: E402

load_dotenv(ROOT / "server/.env")
OUT = ROOT / "video/demo/public/intro"
BASE = os.environ.get("BIZROUTER_BASE_URL", "https://bizrouter.ai/api/v1").rstrip("/")
H = {"Authorization": f"Bearer {os.environ['BIZROUTER_API_KEY']}"}
MODEL = os.environ.get("VIDEO_MODEL", "bytedance/seedance-2.5")

STYLE = ("Photorealistic, natural motion, smartphone car-camera look, no text, no logos, no license plates. "
         "The driver keeps both hands on the steering wheel and eyes on the road; nobody holds a phone.")

# id: (첫 프레임 이미지, 길이(초), 프롬프트)
JOBS: dict[str, tuple[str | None, int, str]] = {
    # 첫 프레임 없이(텍스트→영상) 21:9 파노라마. 운전자·차체가 안 보이는 와이드 뷰
    "v_pano": (None, 8,
               "Ultra-wide cinematic panorama of a long suspension bridge crossing a wide turquoise sea on a bright clear day, "
               "seen from road level while moving forward smoothly along the bridge deck; the bridge towers and cables sweep past, "
               "open sea and distant coastline stretch to both sides of the frame, sparkling water, gentle lateral drift. "
               "No car hood, no dashboard, no people, no text, no logos."),
    "v_bridge": ("ocean_dark", 10,
                 "Front dashcam view. The car drives through a dark highway tunnel with orange lights streaking past, then bursts "
                 "out into bright daylight onto a long suspension bridge over a wide turquoise sea. As the view opens up, the camera "
                 "slowly pans sideways across the sparkling sea panorama and the bridge cables while the car keeps driving. "
                 f"Smooth, cinematic, steady speed. {STYLE}"),
    "v_friends": ("ocean_friends1", 5,
                  "Inside the car on a sunny bridge over the sea: the passenger raises both arms and cheers, the back-seat friend "
                  f"laughs and claps, the driver smiles while looking at the road. Sea light flickers through the windows. {STYLE}"),
    "v_friends2": ("ocean_friends2", 5,
                   "Inside the car crossing a bridge over the sea: the back-seat friend leans forward making a V sign and laughing, "
                   f"the passenger turns around laughing, the driver smiles while looking at the road. Bright sea light. {STYLE}"),
    # 가족 여행 7초 = 차 안(v_family_in) → 와이프 → 주변 풍경(v_family_open)
    "v_family_in": ("trip_family", 4,
                    "Inside the car on a Jeju coastal road: the kids in the back seat laugh and point out the window, the mother turns "
                    f"around smiling at them, the father smiles while driving. Normal car interior with roof, sunny afternoon. {STYLE}"),
    "v_family_open": ("family_open", 4,
                      "The family rides with the open coastal landscape all around them like a convertible: wind in their hair, the kids "
                      f"cheer with arms up, the mother laughs, the father smiles while driving; sea and green hills slide past. {STYLE}"),
    # 반려견 7초 = 사람이 운전하다 오른손가락을 튕김(v_pet_snap) → 번쩍 → 반려견이 운전석(v_pet)
    "v_pet_snap": ("pet_orig_blossom", 4,
                   "Inside the car on a cherry blossom road, pink petals drifting past. The woman driving smiles at the camera, then "
                   "briefly lifts her right hand and snaps her fingers with a playful confident expression, then puts the hand back "
                   "on the wheel. The golden retriever in its harness on the passenger seat looks at her. Photorealistic, no text, no logos."),
    # 한 영상 안에서 변환: 천장만 하늘·풍경으로 걷히고, 아이들은 만세만
    # 가족 여행 뒤 컷: 지붕·필러 없는 열린 풍경. 아이들은 앉은 채 만세
    "v_family_open2": ("family_open", 4,
                       "The family rides in the open air with the coastal landscape all around them like a convertible: blue sea, "
                       "volcanic rocks and green hills slide past. Everyone stays seated with seatbelts fastened: the two kids in the "
                       "back seat stay sitting down and raise both arms in a hurray pose and hold it; they never stand up or bounce. "
                       f"The mother laughs, the father smiles while driving with both hands on the wheel. {STYLE}"),
    "v_family7": ("trip_family", 7,
                  "Inside the car on a Jeju coastal road, normal interior with the roof at first. Around the 3-second mark the whole "
                  "upper car body smoothly dissolves away — roof, roof frame, pillars and window frames all vanish completely — so the "
                  "family is in the open air like a convertible, with a wide unobstructed view of blue sea, black volcanic rocks, green "
                  "hills and sky all around them. Only the seats, dashboard and steering wheel remain. Everyone stays seated with "
                  "seatbelts fastened the whole time: the two kids in the back seat, still sitting down, raise both arms in a cheerful "
                  "hurray pose and hold it; they never stand up or bounce. The mother smiles, the father keeps driving with both hands "
                  f"on the wheel. Smooth, natural, photorealistic. {STYLE}"),
    # 한 영상 안에서 변환: 운전자가 전방을 보며 엄지·검지로 '딱' → 운전자와 반려견 자리만 바뀜
    "v_pet7": ("pet_orig_blossom", 7,
               "Inside the car on a cherry blossom road, pink petals drifting past. The woman drives with her eyes on the road the "
               "whole time. Around the 2.5-second mark, still looking forward, she lifts her right hand slightly and snaps her thumb "
               "and middle finger once with a confident little smile. Instantly, with a quick sparkle, she and the golden retriever "
               "swap seats: now the dog in its harness sits in the driver's seat with its front paws on the steering wheel, and the "
               "woman sits in the passenger seat laughing. The car interior, camera angle and background stay exactly the same; "
               "only the two of them change places. Photorealistic, no text, no logos."),
    "v_family": ("trip_family", 6,
                 "Inside the car on a Jeju coastal road: the kids in the back seat laugh and point at the sea outside, the mother "
                 f"turns around smiling at them, the father smiles while driving. Bright sunny afternoon, sea passing by. {STYLE}"),
    "v_pet": ("pet_blossom", 6,
              "Inside the car on a road lined with cherry blossom trees, pink petals drifting past the windows. A happy golden "
              "retriever wearing a car safety harness sits in the driver's seat with its front paws resting on the steering wheel, "
              "tongue out, looking proud; the woman in the passenger seat laughs and pets the dog. The car moves slowly and gently. "
              "Playful, heart-warming. Photorealistic, no text, no logos."),
}


# Seedance 는 사람이 있는 첫 프레임을 거절한다(InputImageSensitiveContentDetected.PrivacyInformation, 11/15 실측)
# → 인물 영상은 Grok Imagine Video 로
PEOPLE_MODEL = os.environ.get("VIDEO_PEOPLE_MODEL", "x-ai/grok-imagine-video-1.5")
MODEL_OF = {"v_family_open2": PEOPLE_MODEL, "v_family7": PEOPLE_MODEL, "v_pet7": PEOPLE_MODEL, "v_family_in": PEOPLE_MODEL, "v_family_open": PEOPLE_MODEL, "v_pet_snap": PEOPLE_MODEL, "v_friends": PEOPLE_MODEL, "v_friends2": PEOPLE_MODEL, "v_family": PEOPLE_MODEL, "v_pet": PEOPLE_MODEL}


ASPECT_OF = {"v_pano": "21:9"}


def submit(img_id: str, image: str | None, sec: int, prompt: str) -> str:
    body = {"model": MODEL_OF.get(img_id, MODEL), "prompt": prompt, "duration_seconds": sec, "resolution": "720p",
            "aspect_ratio": ASPECT_OF.get(img_id, "9:16")}
    if image:
        body["image"] = {"mime_type": "image/jpeg", "data_base64": base64.b64encode((OUT / f"{image}.jpg").read_bytes()).decode()}
    r = httpx.post(f"{BASE}/video/generation", headers=H, json=body, timeout=120)
    if r.status_code >= 400:
        raise RuntimeError(f"submit {r.status_code}: {r.text[:300]}")
    j = r.json()
    print(f"submitted {img_id} op={j.get('operation_id')} cost_krw={j.get('cost_krw')}", flush=True)
    return j["operation_id"]


def wait_and_download(img_id: str, op: str) -> None:
    for _ in range(120):  # 최대 10분
        time.sleep(5)
        r = httpx.get(f"{BASE}/video/generation/{op}", headers=H, timeout=60)
        j = r.json() if r.status_code < 500 else {"status": "retry"}
        st = j.get("status")
        if st == "completed":
            v = httpx.get(f"{BASE}/video/generation/{op}/download", headers=H, timeout=300, follow_redirects=True)
            v.raise_for_status()
            (OUT / f"{img_id}.mp4").write_bytes(v.content)
            print(f"ok {img_id} {len(v.content) // 1024}KB {j.get('duration_seconds')}s cost_krw={j.get('cost_krw')}", flush=True)
            return
        if st == "failed":
            print(f"FAIL {img_id} {str(j.get('error'))[:300]} cost_krw={j.get('cost_krw')}", flush=True)
            return
    print(f"TIMEOUT {img_id} op={op}", flush=True)


def run(img_id: str) -> None:
    image, sec, prompt = JOBS[img_id]
    try:
        wait_and_download(img_id, submit(img_id, image, sec, prompt))
    except Exception as e:  # noqa: BLE001
        print(f"FAIL {img_id} {type(e).__name__}: {str(e)[:300]}", flush=True)


if __name__ == "__main__":
    want = sys.argv[1:]
    ids = want or [k for k in JOBS if not (OUT / f"{k}.mp4").exists()]
    with ThreadPoolExecutor(4) as ex:
        list(ex.map(run, ids))
