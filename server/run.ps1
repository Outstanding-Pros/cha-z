# 포토라이브 프레임 서버 실행 (PowerShell)
# 기본: 이 PC 안에서만 받는다(127.0.0.1). 외부는 Cloudflare Tunnel(도메인)로만 — ~/.cloudflared/chaz.yml
# 같은 Wi-Fi 폰으로 직접 붙는 LAN 모드는 --host 0.0.0.0 + .env 의 PUBLIC_BASE_URL 제거(자체 서명 HTTPS 8788 사용)
# 최초 1회: python -m venv .venv; .venv\Scripts\pip install -r requirements.txt; copy .env.example .env
Set-Location $PSScriptRoot
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8787 --reload
