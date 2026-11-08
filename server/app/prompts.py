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
