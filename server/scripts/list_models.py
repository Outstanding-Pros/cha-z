"""BizRouter에서 쓸 수 있는 모델 목록 중 이미지 관련 모델만 출력.

사용:  .venv\\Scripts\\python scripts\\list_models.py
"""

import json
import os
from pathlib import Path

import httpx
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent / ".env")
key = os.getenv("BIZROUTER_API_KEY", "")
base = os.getenv("BIZROUTER_BASE_URL", "https://bizrouter.ai/api/v1").rstrip("/")
if not key:
    raise SystemExit("server/.env 에 BIZROUTER_API_KEY 를 넣어주세요")

r = httpx.get(f"{base}/models", headers={"Authorization": f"Bearer {key}"}, timeout=30)
print("HTTP", r.status_code)
if r.status_code >= 400:
    raise SystemExit(r.text[:500])

models = r.json().get("data", r.json())
print("전체 모델 수:", len(models))
hints = ("image", "dall", "imagen", "flux", "stable", "sd", "seedream", "ideogram", "recraft")
for m in models:
    mid = m.get("id", "") if isinstance(m, dict) else str(m)
    blob = json.dumps(m, ensure_ascii=False).lower()
    out_mod = (m.get("architecture") or {}).get("output_modalities") if isinstance(m, dict) else None
    if any(h in blob for h in hints) or (out_mod and "image" in out_mod):
        print("-", mid, "| output:", out_mod)
