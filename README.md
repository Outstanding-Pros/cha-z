# 차~즈 (Cha~z)

차량 실내의 인물과 차창 밖 풍경을 한 장에 담는 **차량용 포토부스 앱** — Pleos Connect(현대차그룹 AAOS)용.
음성 호출어 "차~즈"로 촬영하고, 주행 맥락(위치·시간·날씨·풍경)에 맞춰 앱이 먼저 촬영을 제안하고 조명을 연출합니다.

> 1차 심사용 개발 중 (2026-11). 진행 현황은 아래 '태스크'.

## 구성

| 폴더 | 내용 |
|---|---|
| `car-app/` | 차량 앱 (Android, Kotlin, Jetpack Compose) |
| `server/` | 로컬 개발 서버 (FastAPI) — 비즈라우터 AI 이미지·TTS·STT 중계. 키는 서버에만 |
| `companion-web/` | 동승자 폰 PWA (예정) |
| `supabase/` | 백엔드 스키마·Edge Functions (예정) |
| `assets/` | BGM·TTS 클립·프레임·외부 카메라 목 영상 |
| `docs/` | 사양서 `SPEC.md`, 실측 결과 `spike.md` |

## 실행

**테스트 환경**: Pleos Connect Emulator (API 34 Pleos Connect system image), Android Studio.

1. 서버
   ```powershell
   cd server
   python -m venv .venv; .venv\Scripts\pip install -r requirements.txt
   copy .env.example .env   # BIZROUTER_API_KEY 입력
   .\run.ps1                # http://localhost:8787
   ```
2. 차량 앱: `car-app/` 을 Android Studio로 열고 실행하거나
   ```bash
   cd car-app && ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   `local.properties.example` 참고 (기본 서버 주소 `http://10.0.2.2:8787`).
3. 에뮬레이터 마이크: `adb emu avd hostmicon`

## SDK 가용성과 어댑터 구조

기능마다 **Pleos SDK → 플랫폼 API → 비즈라우터 → 대체** 순서로 실행 시점에 시도해, 실제로 동작하는 구현을 고릅니다 (`CapabilityProbe` → `SdkAvailability`). 결과는 앱 **설정 → SDK 연동 상태** 패널에 사유와 함께 표시됩니다. 커밍순 SDK가 열리면 어댑터 구현만 바뀌고 기능 코드는 그대로입니다.

에뮬레이터 실측 결과(요약, 자세히는 `docs/spike.md`):

| 기능 | 선택된 구현 |
|---|---|
| 주행 규제 | AAOS CarUxRestrictions |
| 음성 안내 / 명령 인식 | Pleos TextToSpeech·SpeechToText SDK (온디바이스) |
| 앰비언트 라이트 | Pleos Vehicle SDK Light |
| 위치·경로 | Pleos NaviHelper SDK |
| 실내 카메라 | 에뮬레이터 웹캠 (→ 폰 컴패니언, AI 가상 컷) |
| 전·후방 카메라 | 사전 녹화 주행 영상 (에뮬레이터에 차량 카메라 없음) |

## 음성 명령

호출어 **"차즈"** (변형 "차즈야", "차 즈" 인정, "치즈"는 받지 않음). 정차·주행 중 모두 앱이 화면에 있을 때 동작합니다.

| 말하기 | 동작 |
|---|---|
| 차즈 | 12초 동안 4컷 촬영 |
| 차즈 1분 동안 10번 찍어줘 / 차즈 30초 동안 다섯 장 | 설정을 바꿔 촬영 |
| 차즈 다시 찍어줘 | 직전 설정으로 다시 촬영 |
| 차즈 그만 / 차즈 멈춰 | 촬영 중지 (찍은 컷은 저장) |
| 차즈 뭐 할 수 있어? | 명령 예시 안내 |

마이크가 없으면 주행 화면의 큰 '차~즈' 버튼(음성 버튼)이나 '점검' 탭의 명령 주입으로 시험할 수 있습니다.

## 태스크

- [x] T01 프로젝트·Pleos SDK 의존성 · T02 CapabilityProbe·SDK 연동 상태 패널 · T03 DrivingGate · T04 스파이크(일부)
- [x] T05 카메라 소스 (실내 웹캠 + 전·후방 시뮬레이션)
- [x] T06 촬영 세션 엔진 (12초 4컷, TTS 안내 + 카운트다운, 컷·클립 저장)
- [x] T07 음성 체인 (호출어 '차즈', 규칙 파서 + LLM 폴백, Pleos STT/TTS)
- [x] T08 인물 분리(MediaPipe) + L1 차창 합성 · L2 컷형 프레임, 갤러리 저장
- [ ] T09 라이브 미리보기 · T10 폰 전송
