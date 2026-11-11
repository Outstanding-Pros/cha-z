# PhotoLive AI 프레임 서버

명소·테마로 AI 배경을 만들고, 사진 칸 4개가 투명한 네컷 프레임 PNG(1200x1800)로 가공해 주는 로컬 서버.

## 실행

```powershell
cd server
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
copy .env.example .env      # BIZROUTER_API_KEY, IMAGE_PROVIDER 설정
.\run.ps1                   # http://localhost:8787  (에뮬레이터: http://10.0.2.2:8787)
```

8000번 포트는 이 PC에서 다른 앱이 쓰고 있어서 8787 포트를 사용한다.

같은 프로세스가 **폰용 HTTPS(8788, 자체 서명)** 도 함께 연다(T11). 폰 브라우저는 HTTPS 에서만 카메라를 열어 주기 때문이다.
- 인증서는 처음 실행할 때 `data/tls/` 에 만들어지고(gitignore), LAN IP 가 바뀌면 다시 만든다.
- 차량 화면 QR 은 `https://<LAN IP>:8788/app/?pair=…` 를 가리킨다. 폰에서 처음 한 번 "안전하지 않음 → 계속" 을 눌러 허용한다.
- 폰과 PC 는 같은 네트워크여야 하고, Windows 방화벽에서 8787·8788 인바운드를 허용해야 한다.
- `CHAZ_HTTPS_PORT=0` 이면 HTTPS 를 끈다(QR 은 http, 폰 카메라 모드는 쓸 수 없음).

### 외부 공개 (Cloudflare Tunnel, `<서버 도메인>`)
`app/guard.py` 가 외부 요청(Cloudflare 경유 또는 공인 IP)만 막는다. 이 PC·LAN 요청은 그대로.
- 폰 경로(`/app`, `/s/`, 페어링 수락, 사진 받기·공유, 카메라 프레임, `/health`)는 토큰 없이 허용.
- 나머지(차량 전용·AI)는 `X-Chaz-Token` 이 `.env` 의 `CHAZ_APP_TOKEN` 과 같아야 한다. 차량 앱은 `local.properties` 의 같은 값을 쓴다.
- AI 경로(이미지·TTS·STT·인텐트)는 IP 당 분당 `CHAZ_AI_PER_MIN`(20), 하루 전체 `CHAZ_DAILY_AI_LIMIT`(500) 회 상한.
- `.env` 에 `PUBLIC_BASE_URL=https://<서버 도메인>` 를 넣으면 페어링 QR·공유 링크가 도메인 주소가 되고, 자체 서명 HTTPS(8788)는 자동으로 꺼진다.
- AI 중 이미지 생성은 하루 `CHAZ_DAILY_IMAGE_LIMIT`(30) 회로 따로 제한. 외부 `/health` 는 `{"ok": true}` 만.
- 서버는 `127.0.0.1` 에만 바인드(run.ps1) → 공인 IP 로 8787 에 직접 들어올 수 없고 Cloudflare 경유만 가능.
- 터널: `cloudflared tunnel --config ~/.cloudflared/chaz.yml run chaz` (터널 `chaz` → `localhost:8787`). 인증 파일은 `~/.cloudflared/` 에만.
- 실측(11/11): Cloudflare 경유 시 폰 촬영 신호 지연 0.3초(가끔 1~1.8초), 업로드 포함 0.5~3초 → 셔터보다 늦게 찍힐 수 있음.

## 설정 (.env)

| 키 | 설명 |
|---|---|
| `IMAGE_PROVIDER` | `mock`(API 호출 없음) / `images`(`/images/generations`) / `chat`(`/chat/completions` + 이미지 출력) |
| `IMAGE_MODEL` | BizRouter 모델 ID. `scripts/list_models.py`로 확인 |
| `IMAGE_SIZE` | 기본값 `1024x1536` (2:3) |

키를 넣은 뒤 `.venv\Scripts\python scripts\list_models.py`를 실행하면 이미지 모델 목록이 나온다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/health` | provider, model, 키 설정 여부 |
| GET | `/pois` | 데모 명소(제주 3곳) |
| GET | `/themes` | `sunset`, `fantasy`, `cute`, `retro` |
| GET | `/layout` | 캔버스 크기와 사진 칸 좌표 |
| POST | `/frames/generate` | `{ "poiId": "jeju-aewol-coast", "theme": "sunset" }` → `frameId`, `frameUrl`, `previewUrl`, `layout` |
| GET | `/frames/{id}.png` | 투명 칸이 있는 프레임 |
| GET | `/frames/{id}/background.png` | 원본 AI 배경 |
| GET | `/frames/{id}/preview.jpg` | 칸을 체크무늬로 채운 확인용 이미지 |

같은 요청(provider + model + prompt + 장소)은 `data/frames/`에 캐시되어 곧바로 반환된다. `"force": true`를 주면 다시 생성한다.
