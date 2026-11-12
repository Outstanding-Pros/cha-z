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
OPENTOP_SCENERY_DEFAULT = "Behind the car: a coastal road with the sea on one side and green hills on the other."
