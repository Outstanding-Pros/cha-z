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
