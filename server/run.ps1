# 포토라이브 프레임 서버 실행 (PowerShell)
# 최초 1회: python -m venv .venv; .venv\Scripts\pip install -r requirements.txt; copy .env.example .env
Set-Location $PSScriptRoot
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8787 --reload
