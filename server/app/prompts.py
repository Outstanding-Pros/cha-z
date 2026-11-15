"""AI 프레임 배경 프롬프트 생성.

기획서 원칙: 판타지·귀여움 톤만 사용, 사고·추격·위험운전 연상 금지.
프레임 가운데(사진 칸)는 서버가 투명하게 뚫으므로, 배경은 가장자리에 장식이
몰리도록 요청한다.
"""

THEMES: dict[str, dict[str, str]] = {
    "sunset": {
        "label": "노을 드라이브",
        "style": "warm golden-hour sunset palette, soft peach and lavender sky, dreamy watercolor illustration",
    },
    "fantasy": {
        "label": "구름 위 드라이브",
        "style": "whimsical fantasy, fluffy pastel clouds, tiny sparkles, storybook illustration",
    },
    "cute": {
        "label": "귀여운 여행",
        "style": "cute kawaii doodle style, rounded shapes, pastel colors, playful stickers like tangerines and seagulls",
    },
    "retro": {
        "label": "레트로 필름",
        "style": "retro 90s film postcard, slightly faded colors, grain texture, vintage travel poster",
    },
}

DEFAULT_THEME = "sunset"

_SAFETY = (
    "Peaceful and joyful mood only. No people, no faces, no text, no letters, no logos, "
    "no license plates, no car crashes, no police, no chases, no speeding, no danger."
)

_LAYOUT = (
    "Vertical decorative photo-booth frame background. Keep the illustration rich along the "
    "outer edges and corners; the central area will be covered by four photos, so keep the "
    "center simple and evenly lit."
)


def build_prompt(scene: str, theme_key: str, extra: str | None = None) -> str:
    theme = THEMES.get(theme_key, THEMES[DEFAULT_THEME])
    parts = [
        f"Scenery: {scene}.",
        f"Style: {theme['style']}.",
        _LAYOUT,
        _SAFETY,
    ]
    if extra:
        parts.insert(1, f"Details: {extra.strip()[:200]}.")
    return " ".join(parts)


# ✨ AI 오픈카 (POST /frames/opentop). 사람은 그대로, 지붕만 연다 — 얼굴이 바뀌면 의미가 없다.
OPENTOP_PROMPT = (
    "Edit this photo taken inside a car from the front, looking back at the cabin. "
    "Turn the car into a convertible with the top fully down: remove the roof, headliner, rear-view mirror, dashcam, "
    "pillars and all windows above the door line. Keep the seats, door panels, seatbelts, dashboard and every person "
    "exactly as they are — identical face, expression, hair, pose, hands and clothes. Do not add, remove or beautify anyone. "
    "Show the car's own body (door tops, rear deck) around the open cabin so the car is clearly on the road. "
    "{scenery} Big open sky. Soft warm late-afternoon sunlight falling on the seats and the people, with consistent shadows. "
    "Natural candid film-photo look, same framing and aspect ratio as the input."
)
OPENTOP_SCENERY_FROM_IMAGE = (
    "Behind the car, use the scenery from the second image (a view from the back of this same car at the same moment)."
)
# 🎞 앨범 '만들기' (POST /frames/mix). 실내 사진의 사람들을 차 밖 풍경 앞에 세운다 (11/13 사용자 요청: 사람이 풍경 앞에).
MIX_PROMPT = (
    "Make one natural travel photo from these photos taken at the same moment on a road trip. "
    "Image 1 is the inside of the car with the people. Image 2 is the view {view} of the same car. "
    "Take the people out of the car and show them outdoors, standing close together in the foreground, "
    "with the scenery from image 2 behind them as the background — like a friend took their picture at a roadside viewpoint. "
    "No car interior, seats, seatbelts or steering wheel. Keep every person exactly as they are — identical face, expression, "
    "hair and clothes; show them from about the waist up if their lower body is not visible. Do not add, remove or beautify anyone. "
    "Match the light and colour of the people to the scenery, with consistent shadows. "
    "Candid film-photo look, landscape 16:9 framing."
)
MIX_VIEW = {"FRONT": "out of the front windshield (the road ahead)", "REAR": "out of the back of the car (the road behind)"}
# 🌄 파노라마: 전방 → 후방을 한 장의 넓은 풍경으로 잇고, 가운데에 실내 사진의 사람들.
PANORAMA_PROMPT = (
    "Make one wide panoramic travel photo (about 2.4:1, much wider than tall) from these photos taken at the same moment on a road trip. "
    "Image 1 is the inside of the car with the people. Image 2 is the road ahead of the car, image 3 is the road behind it. "
    "Stitch image 2 on the left and image 3 on the right into one continuous, seamless landscape with a single horizon and sky. "
    "Place the people from image 1, taken out of the car, standing together in the centre of the panorama in the foreground, "
    "about waist up, as if they stopped on the road between the two views. No car interior, seats or seatbelts. "
    "Keep every person exactly as they are — identical face, expression, hair and clothes. Do not add, remove or beautify anyone. "
    "Match light and colour to the scenery with consistent shadows. Cinematic, warm, candid film-photo look."
)
OPENTOP_SCENERY_DEFAULT = "Behind the car: a coastal road with the sea on one side and green hills on the other."

# ---- 릴스 스타일 (11/15) ----
SCENERY_PANORAMA_PROMPT = (
    "Make one wide panoramic landscape photo (about 2.4:1, much wider than tall) from two photos taken at the same moment "
    "from a car on a coastal road trip. Image 1 is the view ahead of the car, image 2 is the view behind it. "
    "Stitch image 1 on the left and image 2 on the right into one continuous, seamless landscape with a single horizon and sky, "
    "and naturally extend the scenery at both outer edges. Keep the real road, sea, bridge and hills from the photos. "
    "No people, no cars in the foreground, no text. Bright, clear, cinematic travel-photo look."
)
NIGHT_PROMPT = (
    "Edit this photo taken inside a parked car. Keep the people, their faces, expressions, hair and clothes, and the whole car interior exactly as they are. "
    "Change only what is seen through the windows into a clear night sky full of stars with a soft Milky Way, as if parked at a quiet campsite. "
    "STRICT: the number of people must stay exactly the same as in the original photo. Never add any new person, face, passenger, reflection of a person or figure anywhere, including the back seats; empty seats must stay empty. Do not remove anyone. "
    "Make the interior lighting a warm, cosy amber glow, as from a small lamp. Do not beautify anyone. Natural, believable photo."
)
PETSWAP_PROMPT = (
    "Edit this photo taken inside a car. A person sits in the driver's seat and a dog sits in the front passenger seat. "
    "Swap their seats: put the same dog in the driver's seat with its front paws resting on the steering wheel, wearing a pet harness, "
    "and put the same person in the passenger seat, relaxed and smiling. Keep the dog's breed, fur and face, and the person's face, hair and clothes identical. "
    "STRICT: the number of people must stay exactly the same as in the original photo. Never add any new person, face, passenger, reflection of a person or figure anywhere, including the back seats; empty seats must stay empty. Do not remove anyone. "
    "Keep the car interior, light and window view the same. Playful, clearly a fun filter photo."
)
TRIP_COPY_SYSTEM = (
    "너는 드라이브 사진 앱 '차~즈'의 카피라이터다. 입력 JSON(장소, 날짜, 시각, 들른 스팟 목록)을 보고 "
    "하루 여행 릴스의 제목(12자 이내)과 한 줄 카피(25자 이내)를 한국어로 쓴다. 과장·이모지·해시태그 금지. "
    "출력은 JSON 한 줄만: {\"title\": \"...\", \"line\": \"...\"}"
)
MOVIE_COPY_SYSTEM = (
    "너는 드라이브 사진 앱 '차~즈'의 영화 예고편 카피라이터다. 입력 JSON(장소, 날짜, 시각)을 보고 그날 드라이브를 "
    "가상의 영화 예고편처럼 쓴다. 실존 영화·드라마 제목, 대사, 인물 이름은 절대 쓰지 않는다. 사고·추격·위험 운전 묘사 금지. "
    "가상 제목(10자 이내), 예고편 자막 3줄(각 16자 이내, 도입·질주·여운 순서), 태그라인(18자 이내)을 한국어로. "
    "출력은 JSON 한 줄만: {\"title\": \"...\", \"lines\": [\"...\", \"...\", \"...\"], \"tagline\": \"...\"}"
)
