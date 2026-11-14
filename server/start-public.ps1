# 차~즈 서버(127.0.0.1:8787) + Cloudflare 터널(도메인)을 띄우고, 죽으면 다시 띄운다.
# 작업 스케줄러 'ChazBoot'(로그인 시)가 숨김 창으로 실행한다. 비밀값은 .env / ~/.cloudflared 에만 있고 여기엔 없다.
$ErrorActionPreference = 'Continue'
$server = $PSScriptRoot
$logs = Join-Path $server 'data\logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$cloudflared = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
$tunnelCfg = Join-Path $env:USERPROFILE '.cloudflared\chaz.yml'
$env:PYTHONIOENCODING = 'utf-8'

function Log($msg) { Add-Content -Encoding utf8 (Join-Path $logs 'supervisor.log') "$(Get-Date -Format s) $msg" }

function Start-Server {
    Start-Process -FilePath (Join-Path $server '.venv\Scripts\python.exe') `
        -ArgumentList '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8787' `
        -WorkingDirectory $server -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs 'server.out.log') -RedirectStandardError (Join-Path $logs 'server.err.log')
}

function Start-Tunnel {
    Start-Process -FilePath $cloudflared -ArgumentList 'tunnel', '--config', "`"$tunnelCfg`"", 'run', 'chaz' `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs 'tunnel.out.log') -RedirectStandardError (Join-Path $logs 'tunnel.err.log')
}

# 이미 떠 있으면(수동 실행 등) 겹쳐 띄우지 않는다.
function Port-Busy { [bool](Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue) }

Log 'supervisor start'
$srv = $null; $tun = $null
while ($true) {
    if ((-not $srv -or $srv.HasExited) -and -not (Port-Busy)) { $srv = Start-Server; Log "server started pid=$($srv.Id)" }
    if (-not $tun -or $tun.HasExited) {
        $running = Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" | Where-Object { $_.CommandLine -like '*chaz.yml*' }
        if (-not $running) { $tun = Start-Tunnel; Log "tunnel started pid=$($tun.Id)" }
    }
    Start-Sleep -Seconds 15
}
