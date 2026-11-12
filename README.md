# 차~즈 (Cha~z)

**달리는 차를 포토부스로.** Pleos Connect(현대차그룹 AAOS)용 차량 포토부스 앱입니다.

차가 촬영 타이밍과 연출을 맡습니다. 명소가 가까워지면 음성으로 미리 알려 주고, 동승자가 "차~즈"라고 말하면 실내 인물과 창밖 풍경을 함께 찍습니다. 정차하면 대화면이 스튜디오가 되어 네컷·오픈카·여행 경로 사진을 만들고, 도착해서 문을 열면 오늘의 여정 앨범이 동승자 폰으로 전송됩니다.

> 2026 현대자동차그룹 차량용 앱 개발 공모전 1차 제출본. 테스트 환경은 **Pleos Connect Emulator** (API 34 Pleos Connect system image)입니다.

---

## 핵심 흐름

```
명소 접근 → TTS 예고 → "차즈" 촬영 → 정차 시 편집·완성 → 도착(도어 열림) 시 폰으로 앨범 전송
```

| 차량 상태 | 차량 화면 | 하는 일 |
|---|---|---|
| **주행 중** | 큰 음성 버튼 + 상태 두 줄만 (미리보기·영상·터치 조작 없음) | 스팟 예고, 음성 촬영, 음성으로 폰 전송 |
| **정차 중** | 촬영 콘솔 전체 UI | 라이브 미리보기, 레이아웃·필터 편집, 조명 연출, AI 오픈카 |
| **도착** (정차 + 도어 열림) | — | 오늘 찍은 사진 중 아직 안 보낸 것을 폰으로 자동 전송 |

주행/정차 전환은 `CarUxRestrictions` 를 구독해 즉시 반영합니다. 주행 중에 열려 있던 미리보기는 바로 닫힙니다.

## 기능

| 기능 | 내용 | 사용 SDK·API |
|---|---|---|
| 음성 촬영 | 호출어 "차즈" → 12초 동안 4컷 (TTS 안내 + 카운트다운). "1분 동안 10번", "다시", "그만", "폰으로 보내줘", "오션 분위기로" | Pleos SpeechToText·TextToSpeech SDK (온디바이스), 규칙 파서 + LLM 폴백 |
| 3방향 동시 촬영 | 컷마다 실내·전방·후방 프레임을 함께 저장 | CameraX (실내), 전·후방은 시뮬레이션 (아래 참고) |
| L1 오픈카 | 좌석과 사람은 그대로 두고 지붕·창 위쪽을 같은 순간의 바깥 풍경으로 교체 | MediaPipe 인물 분리 + 고정 카메라 실내 마스크 |
| L2 필름 네컷 | 풍경 배경 칸에 인물을 오려 넣은 35mm 필름 스트립·종이 프레임 | MediaPipe 인물 분리 |
| L3 여행 경로 | 전방 풍경 + 지나온 길·다음 스팟·도착 시각 카드 + 동그란 인물 사진 | NaviHelper 위치, 스팟 DB |
| ✨ AI 오픈카 | 고른 컷 1장을 생성형 AI가 실제 오픈카 사진처럼 다시 그림. 결과에 'AI 편집' 표기 | 서버 → 비즈라우터 (Gemini 이미지 모델) |
| 라이브 미리보기 | 정차 중 레이아웃·색 필터(원본·필름·노을·오션·흑백)를 실시간 적용 | — |
| 스팟 가이드 | 명소 400m 전 TTS 예고, 5분 간격, 회전 300m 안에서는 미룸, 노을 시간 안내 | NaviHelper SDK, Overpass(OSM) / 데모 스팟 |
| 앰비언트 조명 | 날씨·시간·풍경으로 프리셋 선택 (골든아워·오션·포레스트·시티나이트·코지), 끝나면 원래대로 | Vehicle SDK Light, Open-Meteo |
| 폰 연결 | QR 페어링 → 완성본 전송, 폰에서 저장·공유 링크(7일) | 서버 + 폰 웹앱(PWA) |
| 여정 앨범 자동 전송 | 정차 중 승객 도어가 열리면 오늘 안 보낸 사진을 폰으로. 설정에서 끄고 켬, '지금 앨범 보내기'로 언제든 전송 | Vehicle SDK Door |
| 폰 실내 카메라 | 대시보드에 세운 폰이 차량 카운트다운에 맞춰 실내를 촬영 | 폰 웹앱 카메라 |
| 폰 음성 리모컨 | 동승자 폰에서 말하거나 버튼을 누르면 차량 앱이 같은 명령으로 처리 | 폰 브라우저 음성 인식 |

**준비 중 (2차):** L4 서라운드·순차 배경, 영상 결과물·BGM, 주행 중 폰으로 넘겨 편집(핸드오프), AI 필터 확대. 앱에는 '준비 중'으로 표시됩니다.

## 주행 안전

- 주행 중 차량 화면에는 큰 음성 버튼과 상태 텍스트만 보입니다. 미리보기·결과·QR·텍스트 입력·다단계 터치는 모두 막힙니다 (공통 `DrivingLockOverlay`).
- 모든 화면과 기능은 `DrivingGate` 를 구독합니다. 조명·편집은 정차 중에만 됩니다.
- TTS는 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 로 오디오 포커스를 요청합니다.
- 운전자에게 카메라를 보라고 안내하지 않습니다. 사고·추격 연상 연출은 만들지 않습니다.

## SDK 가용성과 어댑터 구조

기능마다 **Pleos SDK → 플랫폼 API → 비즈라우터 → 대체** 순서로 실행 시점에 시도해, 실제로 동작하는 구현을 고릅니다 (`CapabilityProbe` → `SdkAvailability`, 후보 목록은 `platform/ProbeChains.kt`). 결과와 사유는 앱 **설정 → SDK 연동 상태** 패널에 표시됩니다. 차량 SDK는 어댑터(`platform/`, `voice/`) 뒤에서만 호출하므로, 공개되지 않은 SDK가 열리면 어댑터만 바꾸면 됩니다.

에뮬레이터에서 선택되는 구현 (자세한 실측은 `docs/spike.md`):

| 기능 | 선택된 구현 |
|---|---|
| 주행 규제 | AAOS CarUxRestrictions |
| 음성 안내 / 명령 인식 | Pleos TextToSpeech·SpeechToText SDK (온디바이스) |
| 앰비언트 라이트 | Pleos Vehicle SDK Light |
| 도어 (도착 감지) | Pleos Vehicle SDK Door |
| 위치·경로 | Pleos NaviHelper SDK (데모 경로 재생 지원) |
| 실내 카메라 | **시연** 모드: 에뮬레이터 기본 카메라 / **실촬영** 모드: 동승자 폰 카메라 |
| 전·후방 카메라 | 시뮬레이션 풍경 (공개 SDK·에뮬레이터에 차량 외부 카메라 없음) — 화면과 결과물에 '시뮬레이션' 표기 |

## 구성

| 폴더 | 내용 |
|---|---|
| `car-app/` | 차량 앱 (Kotlin, Jetpack Compose). 패키지 `ai.chaz` |
| `server/` | 서버 (FastAPI). 폰 연결·전송 중계, 비즈라우터 AI(이미지·TTS·STT·LLM) 중계. API 키는 서버에만 둡니다 |
| `companion-web/` | 동승자 폰 웹앱 (PWA, 의존성 없는 바닐라 JS). 서버가 `/app/` 으로 서빙 |
| `assets/` | 폰트, 인물 분리 모델, 데모 스팟·경로, 시뮬레이션 풍경, TTS 카운트다운 |
| `docs/` | 개발 사양서 `SPEC.md`, 에뮬레이터 실측 `spike.md` |
| `supabase/` | (2차) 백엔드 스키마. 1차는 `server/` 가 같은 역할 |

## 실행

### 1. 서버

```powershell
cd server
python -m venv .venv; .venv\Scripts\pip install -r requirements.txt
copy .env.example .env   # BIZROUTER_API_KEY 등 입력 (.env 는 커밋하지 않음)
.\run.ps1                # http://localhost:8787
```

서버가 필요한 기능: 폰 연결·전송·음성 리모컨·폰 카메라, ✨ AI 오픈카, 음성 명령 LLM 폴백.
외부 공개(폰이 다른 네트워크) 시에는 차량 전용 경로에 `X-Chaz-Token` 이 필요하고, AI 경로는 IP당·하루 호출 수를 제한합니다 (`server/app/guard.py`).

### 2. 차량 앱

```bash
cd car-app
cp local.properties.example local.properties   # CHAZ_SERVER_URL 등 (에뮬레이터 기본 http://10.0.2.2:8787)
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant --user 10 ai.chaz android.permission.RECORD_AUDIO
adb shell pm grant --user 10 ai.chaz android.permission.CAMERA
adb shell am start --user 10 -n ai.chaz/.MainActivity
```

- 앱은 에뮬레이터의 **user 10(운전자 프로필)** 에서 실행됩니다.
- 에뮬레이터 마이크: `adb emu avd hostmicon`
- 기어·도어는 에뮬레이터 **Virtual Console** 에서 바꿉니다.

### 3. 폰 연결

차량 앱 **설정 → 폰 연결 → QR 띄우기** → 동승자 폰 카메라로 스캔. 폰 웹앱에서 받은 사진 저장·공유, 실내 카메라 모드, 음성 리모컨을 쓸 수 있습니다. 폰 마이크·카메라는 HTTPS 주소에서만 동작합니다.

## 시연 순서 (기획서 데모 4장면)

1. **출발 전** — Virtual Console에서 기어 P. 대화면 촬영 콘솔, 폰 QR 연결.
2. **주행 시작** — 기어 D. 화면이 음성 버튼만 남기고 사라짐.
3. **명소 접근** — 조명 탭 '데모 드라이브 시작'. 약 1분 뒤 "곧 애월 해안도로 전망대예요" 예고와 조명 변경 → "차즈".
4. **정차** — 기어 P. 편집에서 L1·L2·L3 선택·저장 → Virtual Console에서 도어 열기 → 폰으로 앨범 전송.

개발·시연용 명령 (모두 `adb shell am start --user 10 -f 0x20000000 -n ai.chaz/.MainActivity` 뒤에 붙임):

| 인자 | 동작 |
|---|---|
| `--es say64 <base64>` | 발화 주입 (예: `printf '%s' '차즈 찍어줘' \| base64 -w0`) |
| `--es drive on\|off\|auto` | 주행/정차 강제 전환 (개발용 오버라이드) |
| `--es demo start\|stop` | 데모 경로 재생 (스팟 가이드 시연) |
| `--es loc 33.46,126.31\|off` | 위치 수동 지정 |
| `--es mode demo\|live` | 촬영 모드: 시연(에뮬레이터 카메라) / 실촬영(폰 카메라) |
| `--es door open\|close\|auto` | 도어 신호 시뮬레이션 (`auto` = 실제 Vehicle SDK 값) |
| `--es tab SETTINGS` | 시작 탭 |

## 음성 명령

호출어 **"차즈"** ("차즈야", "차 즈" 인정, "치즈"는 받지 않음). 정차·주행 중 모두 동작합니다.

| 말하기 | 동작 |
|---|---|
| 차즈 | 12초 동안 4컷 촬영 |
| 차즈 1분 동안 10번 찍어줘 / 차즈 30초 동안 다섯 장 | 설정을 바꿔 촬영 |
| 차즈 다시 찍어줘 | 직전 설정으로 다시 촬영 |
| 차즈 그만 / 차즈 멈춰 | 촬영 중지 (찍은 컷은 저장) |
| 차즈 폰으로 보내줘 | 마지막 완성본을 폰으로 |
| 차즈 오션 분위기로 / 차즈 조명 바꿔줘 | 앰비언트 조명 변경 (프리셋 이름 없으면 맥락에 맞게, 정차 중) |
| 차즈 뭐 할 수 있어? | 명령 예시 안내 |

## 개인정보와 AI 사용

- 서버 로그에 위치 좌표, 얼굴 이미지 경로, 음성 문장 내용을 남기지 않습니다.
- ✨ AI 오픈카를 누르면 고른 컷 1장이 서버를 거쳐 AI 서비스(비즈라우터 → Google Gemini)로 전송됩니다. 앱 화면에 이 사실을 안내하고, 서버는 사진을 저장하지 않습니다. 결과물에는 'AI 편집' 표기가 남습니다.
- 폰 음성 리모컨은 폰 브라우저의 음성 인식을 사용합니다 (Chrome은 Google 음성 인식 서버).
- 공유 링크는 7일 뒤 만료됩니다. 폰에서 사진을 받는 주소는 서명된 만료 URL입니다.

## 테스트

```bash
cd car-app && ./gradlew :app:testDebugUnitTest
```

`IntentParser`, `LightRuleEngine`, 스팟 가이드 정책, 촬영 세션 엔진, 여정 앨범 판정 등은 모든 어댑터의 `Fake*` 구현으로 에뮬레이터 없이 테스트합니다.

## 라이선스·출처

| 항목 | 출처 | 라이선스 |
|---|---|---|
| 손글씨 폰트 `NanumPenScript` | 네이버 나눔글꼴 | SIL Open Font License 1.1 (`assets/fonts/NanumPenScript-OFL.txt`) |
| 인물 분리 모델 `selfie_multiclass_256x256` | Google MediaPipe | Apache License 2.0 (`assets/models/LICENSES.md`) |
| MediaPipe Tasks Vision 1.2.1 | Google | Apache License 2.0 |
| ZXing core 3.5.3 (QR) | ZXing | Apache License 2.0 |
| Pleos SDK (Vehicle, NaviHelper, TextToSpeech, SpeechToText) | 현대자동차그룹 Pleos | 공모전 제공 SDK |
| 스팟 데이터 | OpenStreetMap (Overpass API), 데모 스팟은 OSM 좌표 | © OpenStreetMap contributors, ODbL |
| 날씨·일몰 | Open-Meteo | CC BY 4.0 |
| 서버 Python 패키지 | FastAPI, Uvicorn, HTTPX, Pillow, python-dotenv, python-multipart, cryptography | MIT / BSD / Apache 2.0 계열 (`server/requirements.txt`) |

**AI 생성 자산:** 전·후방 시뮬레이션 풍경(`assets/exterior-mock/`)과 TTS 카운트다운 음성(`assets/tts/`)은 비즈라우터를 통해 생성형 AI로 만들었습니다 (생성 스크립트 `server/scripts/`). 프레임 디자인과 레이아웃은 모두 자체 제작입니다.

**에뮬레이터 카메라 이미지:** 시연 모드의 실내 인물은 Pleos Connect 에뮬레이터 시스템 이미지에 들어 있는 기본 카메라 이미지(`/vendor/etc/mock_camera.png`)이며, 이 저장소에는 포함하지 않습니다.
