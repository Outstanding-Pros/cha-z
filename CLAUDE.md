# 차~즈 (Cha~z) — Claude Code 작업 지침

Pleos Connect(현대차그룹 AAOS) 공모전용 차량 포토부스 앱. 1차 제출 2026-11-14, 2차 PT 2026-12-03.

## 기준 문서
- `docs/SPEC.md` 가 기준이다 (원본 PDF: `docs/차즈_개발사양서_v1.pdf`). 사양과 다르게 구현해야 하면 **먼저 SPEC.md를 고친다** (`> 변경:` 표시).
- 실측 결과와 문서와의 차이는 `docs/spike.md`.

## 규칙
1. `docs/SPEC.md` 가 기준. 다르게 구현하려면 먼저 사양서를 고친다.
2. Pleos API 이름을 추측하지 않는다. document.pleos.ai API Reference 또는 Maven의 `-sources.jar`/javap로 확인하고, 확인 못 한 이름은 `// TODO(pleos-api): 확인 필요` 로 표시한다.
3. 차량 SDK는 어댑터(`platform/`, `voice/`) 뒤에서만 호출한다. UI·도메인 코드에서 `Vehicle`, `NaviHelper`, Pleos TTS/STT를 직접 참조하지 않는다.
4. Vehicle SDK는 `initialize()` → `checkXXXCapabilities` → Get/Set/Callback → `release()` 순서. 콜백이 끝나기 전에 `release()` 하지 않는다 (`PleosVehicleHub` 하나만 쓴다).
5. NaviHelper는 `initialize()` → `addListener()` → 호출 → `removeListener()` → `release()` 순서.
6. 모든 화면과 기능은 `DrivingGate`(`DrivingGateHub`)를 구독한다. 주행 중에는 영상 재생, 미리보기, 텍스트 입력, QR, 다단계 터치를 금지한다.
7. 오디오 재생 전 오디오 포커스 요청. TTS는 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, BGM은 `AUDIOFOCUS_GAIN`.
8. 모든 어댑터에 `Fake*` 구현을 두고 에뮬레이터 없이 도는 단위 테스트를 쓴다. `IntentParser`, `LightRuleEngine` 은 테스트 필수.
9. 비즈라우터 키나 어떤 비밀값도 앱 코드·저장소에 넣지 않는다 (`server/.env`, `car-app/local.properties` 는 gitignore).
10. 로그에 위치 좌표·얼굴 이미지 경로 같은 개인정보를 남기지 않는다.
11. 작업 단위는 SPEC의 태스크(T01~T17) 하나. 완료 기준 확인 후 커밋하고, 커밋 메시지에 설계 의도를 적는다.
12. 구현체 선택은 `CapabilityProbe` → `SdkAvailability` 에서만. 새 후보는 `platform/ProbeChains.kt` 에 추가한다.

## 구조
- `car-app/` Android (Kotlin, Compose). 패키지 `ai.chaz`.
- `server/` 로컬 개발 서버(FastAPI). T10 전까지 Edge Function(health, tts, stt, image-generate) 대체. 포트 8787.
- `companion-web/` 폰 PWA (T10~), `supabase/` (T10~), `assets/` 공용 리소스.

## 빌드·실행 (Windows)
```bash
cd car-app
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 앱은 에뮬레이터 user 10(운전자 프로필)에서 실행된다
adb shell pm grant --user 10 ai.chaz android.permission.RECORD_AUDIO
adb shell am start --user 10 -n ai.chaz/.MainActivity --es tab SETTINGS   # SPIKE 탭: --es spike run|tts|stt
```
- 서버: `server/run.ps1` (또는 `server/.venv/Scripts/python -m uvicorn app.main:app --host 0.0.0.0 --port 8787`). 에뮬레이터에서 `http://10.0.2.2:8787`. 같은 프로세스가 폰용 HTTPS 8788(자체 서명)도 연다 — 폰 카메라 모드(T11)는 HTTPS 필요. 단 `.env` 의 `PUBLIC_BASE_URL` 이 `https://` 면(Cloudflare Tunnel) 8788은 열지 않는다.

## 빌드·실행 (macOS)
```bash
cd car-app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug :app:testDebugUnitTest   # release: :app:assembleRelease
# 서버 venv 는 python3.12 로 (3.14 는 고정 버전 pillow 등과 안 맞을 수 있음)
cd server && python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8787
```
- adb 등 나머지 명령은 Windows와 같다 (macOS에선 `MSYS_NO_PATHCONV` 불필요).
- Git Bash에서 adb shell 경로 인자는 `MSYS_NO_PATHCONV=1` 필요 (단, JAVA_HOME 설정과 같이 export하지 말 것).
- 음성 명령 주입(한글은 base64): `adb shell am start --user 10 -f 0x20000000 -n ai.chaz/.MainActivity --es say64 $(printf '%s' '차즈 그만' | base64 -w0)`
- 주행/정차 강제 전환(개발용 오버라이드): `--es drive on|off|auto` (위와 같은 am start)
- 촬영 모드: `--es mode demo|live` (시연 = 에뮬레이터 샘플 실내, 실촬영 = 폰 카메라만)
- 도어(여정 앨범 자동 전송 시험): `--es door open|close|auto` (auto = 실제 Vehicle SDK Door 값). Virtual Console 도어로도 동작
- 위치: `--es loc 33.46,126.31|off`(수동), `--es demo start|stop`(데모 경로 — 스팟 가이드 시연)
- 에뮬레이터는 user 빌드라 `cmd car_service inject-vhal-event` 불가 → 기어·속도는 Virtual Console로.
