# 포토라이브 프레임 서버 실행 (PowerShell)
# HTTP 8787(차량) + HTTPS 8788(폰 카메라 모드, 자체 서명 — data/tls/)
# 최초 1회: python -m venv .venv; .venv\Scripts\pip install -r requirements.txt; copy .env.example .env
Set-Location $PSScriptRoot
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8787 --reload
