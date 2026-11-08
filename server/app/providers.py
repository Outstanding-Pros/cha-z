"""이미지 생성 백엔드. 결과는 항상 PNG/JPEG 바이트로 반환한다.

BizRouter는 OpenAI 호환 API(https://bizrouter.ai/api/v1)지만 이미지 생성 방식이
문서화되어 있지 않아 두 방식을 모두 지원한다.
  - images: POST /images/generations  (OpenAI Images API 형식)
  - chat  : POST /chat/completions + modalities ["image","text"] (OpenRouter 형식)
"""

from __future__ import annotations

import base64
import hashlib
import io
import os
import re

import httpx
from PIL import Image, ImageDraw, ImageFilter


class ImageGenError(RuntimeError):
    pass


def _cfg(name: str, default: str | None = None) -> str:
    val = os.getenv(name, default)
    if val is None or val == "":
        raise ImageGenError(f"환경변수 {name} 가 비어 있습니다 (.env 확인)")
    return val


def _size() -> tuple[int, int]:
    w, h = os.getenv("IMAGE_SIZE", "1024x1536").lower().split("x")
    return int(w), int(h)


def _headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {_cfg('BIZROUTER_API_KEY')}", "Content-Type": "application/json"}


def _base() -> str:
    return _cfg("BIZROUTER_BASE_URL", "https://bizrouter.ai/api/v1").rstrip("/")


def _timeout() -> float:
    return float(os.getenv("IMAGE_TIMEOUT", "180"))


def _decode_image_ref(ref: str, client: httpx.Client) -> bytes:
    """data URL, 순수 base64, http URL 중 무엇이 와도 바이트로 변환."""
    if ref.startswith("data:"):
        return base64.b64decode(ref.split(",", 1)[1])
    if ref.startswith("http://") or ref.startswith("https://"):
        r = client.get(ref, timeout=_timeout())
        r.raise_for_status()
        return r.content
    return base64.b64decode(ref)


def _raise_for(r: httpx.Response) -> None:
    if r.status_code >= 400:
        body = r.text[:500]
        raise ImageGenError(f"BizRouter {r.status_code}: {body}")


def generate_images_api(prompt: str) -> bytes:
    w, h = _size()
    payload = {
        "model": _cfg("IMAGE_MODEL"),
        "prompt": prompt,
        "size": f"{w}x{h}",
        "n": 1,
    }
    quality = os.getenv("IMAGE_QUALITY", "").strip()
    if quality:  # gpt-image 계열: low | medium | high  (low가 가장 저렴)
        payload["quality"] = quality
    with httpx.Client() as c:
        r = c.post(f"{_base()}/images/generations", headers=_headers(), json=payload, timeout=_timeout())
        _raise_for(r)
        data = r.json().get("data") or []
        if not data:
            raise ImageGenError(f"응답에 data 없음: {r.text[:300]}")
        item = data[0]
        ref = item.get("b64_json") or item.get("url")
        if not ref:
            raise ImageGenError(f"응답에 이미지 없음: {str(item)[:300]}")
        return _decode_image_ref(ref, c)


def generate_chat_api(prompt: str) -> bytes:
    w, h = _size()
    payload = {
        "model": _cfg("IMAGE_MODEL"),
        "modalities": ["image", "text"],
        "messages": [
            {
                "role": "user",
                "content": f"Generate one image ({w}x{h}, portrait 2:3). {prompt}",
            }
        ],
    }
    with httpx.Client() as c:
        r = c.post(f"{_base()}/chat/completions", headers=_headers(), json=payload, timeout=_timeout())
        _raise_for(r)
        msg = (r.json().get("choices") or [{}])[0].get("message") or {}

        # OpenRouter 형식: message.images[].image_url.url
        for img in msg.get("images") or []:
            url = (img.get("image_url") or {}).get("url") or img.get("url")
            if url:
                return _decode_image_ref(url, c)

        # content 배열 형식
        content = msg.get("content")
        if isinstance(content, list):
            for part in content:
                url = (part.get("image_url") or {}).get("url") if isinstance(part, dict) else None
                if url:
                    return _decode_image_ref(url, c)
        # 텍스트 안에 data URL/마크다운 이미지가 섞여 오는 경우
        if isinstance(content, str):
            m = re.search(r"(data:image/[a-z]+;base64,[A-Za-z0-9+/=]+)", content) or re.search(
                r"!\[[^\]]*\]\((https?://[^)]+)\)", content
            )
            if m:
                return _decode_image_ref(m.group(1), c)
        raise ImageGenError(f"chat 응답에서 이미지를 찾지 못함: {str(msg)[:300]}")


def generate_mock(prompt: str) -> bytes:
    """API 없이 테스트용: 프롬프트 해시로 색을 정한 그라데이션 + 원형 장식."""
    w, h = _size()
    seed = hashlib.sha256(prompt.encode()).digest()
    top = tuple(seed[0:3])
    bottom = tuple(seed[3:6])
    img = Image.new("RGB", (w, h))
    px = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        px.line([(0, y), (w, y)], fill=tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)))
    deco = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(deco)
    for i in range(14):
        b = seed[(6 + i) % 32]
        r = 30 + b % 90
        x = (seed[(i * 3) % 32] * 7) % w
        y = (seed[(i * 5 + 1) % 32] * 11) % h
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, 70))
    img = Image.alpha_composite(img.convert("RGBA"), deco.filter(ImageFilter.GaussianBlur(6)))
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "PNG")
    return buf.getvalue()


def generate(prompt: str) -> bytes:
    provider = os.getenv("IMAGE_PROVIDER", "mock").lower()
    if provider == "mock":
        return generate_mock(prompt)
    if provider == "images":
        return generate_images_api(prompt)
    if provider == "chat":
        return generate_chat_api(prompt)
    raise ImageGenError(f"알 수 없는 IMAGE_PROVIDER: {provider}")
