"""생성된 배경을 네컷(2x2) 프레임 PNG로 가공한다.

출력: 1200x1800 RGBA PNG. 사진 칸 4개는 완전 투명(alpha=0)이고, 칸 좌표는
레이아웃 JSON으로 같이 돌려줘서 차량 앱이 사진을 정확히 끼울 수 있게 한다.
"""

from __future__ import annotations

import io
import os
from dataclasses import asdict, dataclass

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

CANVAS_W, CANVAS_H = 1200, 1800
MARGIN_X = 70
TOP = 150
BOTTOM = 230
GAP = 40
BORDER = 12
RADIUS = 28


@dataclass
class Slot:
    x: int
    y: int
    w: int
    h: int


def slots() -> list[Slot]:
    sw = (CANVAS_W - 2 * MARGIN_X - GAP) // 2
    sh = (CANVAS_H - TOP - BOTTOM - GAP) // 2
    out = []
    for row in range(2):
        for col in range(2):
            out.append(Slot(MARGIN_X + col * (sw + GAP), TOP + row * (sh + GAP), sw, sh))
    return out


def layout() -> dict:
    return {
        "canvas": {"w": CANVAS_W, "h": CANVAS_H},
        "slots": [asdict(s) for s in slots()],
        "radius": RADIUS,
    }


def _font(size: int, bold: bool = True) -> ImageFont.ImageFont:
    candidates = [
        os.getenv("FRAME_FONT", ""),
        r"C:\Windows\Fonts\malgunbd.ttf" if bold else r"C:\Windows\Fonts\malgun.ttf",
        "/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf",
        "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    ]
    for path in candidates:
        if path and os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size)


def _text_with_shadow(draw: ImageDraw.ImageDraw, xy, text, font, anchor="mm"):
    x, y = xy
    draw.text((x + 3, y + 3), text, font=font, fill=(0, 0, 0, 110), anchor=anchor)
    draw.text((x, y), text, font=font, fill=(255, 255, 255, 255), anchor=anchor)


def compose_frame(background: bytes, title: str, caption: str) -> bytes:
    bg = Image.open(io.BytesIO(background)).convert("RGBA")
    frame = ImageOps.fit(bg, (CANVAS_W, CANVAS_H), Image.LANCZOS)

    # 칸 주변 그림자
    shadow = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    for s in slots():
        sd.rounded_rectangle(
            [s.x - BORDER + 6, s.y - BORDER + 10, s.x + s.w + BORDER + 6, s.y + s.h + BORDER + 10],
            radius=RADIUS + BORDER,
            fill=(0, 0, 0, 90),
        )
    frame = Image.alpha_composite(frame, shadow.filter(ImageFilter.GaussianBlur(10)))

    # 흰 테두리
    d = ImageDraw.Draw(frame)
    for s in slots():
        d.rounded_rectangle(
            [s.x - BORDER, s.y - BORDER, s.x + s.w + BORDER, s.y + s.h + BORDER],
            radius=RADIUS + BORDER,
            fill=(255, 255, 255, 255),
        )

    # 사진 칸 투명 처리
    alpha = frame.getchannel("A")
    ad = ImageDraw.Draw(alpha)
    for s in slots():
        ad.rounded_rectangle([s.x, s.y, s.x + s.w, s.y + s.h], radius=RADIUS, fill=0)
    frame.putalpha(alpha)

    # 상단 타이틀, 하단 캡션
    txt = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    td = ImageDraw.Draw(txt)
    _text_with_shadow(td, (CANVAS_W // 2, TOP // 2 + 5), title, _font(64))
    _text_with_shadow(td, (CANVAS_W // 2, CANVAS_H - BOTTOM // 2), caption, _font(48, bold=False))
    frame = Image.alpha_composite(frame, txt)

    buf = io.BytesIO()
    frame.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def preview_with_placeholders(frame_png: bytes) -> bytes:
    """확인용: 투명 칸 아래에 회색 체크무늬를 깔아 JPG로."""
    frame = Image.open(io.BytesIO(frame_png)).convert("RGBA")
    base = Image.new("RGBA", frame.size, (200, 200, 200, 255))
    bd = ImageDraw.Draw(base)
    for y in range(0, CANVAS_H, 40):
        for x in range(0, CANVAS_W, 40):
            if (x // 40 + y // 40) % 2:
                bd.rectangle([x, y, x + 39, y + 39], fill=(170, 170, 170, 255))
    out = Image.alpha_composite(base, frame).convert("RGB")
    buf = io.BytesIO()
    out.save(buf, "JPEG", quality=88)
    return buf.getvalue()
