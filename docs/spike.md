# T04 스파이크 결과 (Pleos Connect Emulator 실측)

환경: AVD `Pleos_Connect_v2.1.0` (빌드 지문 `Pleos/crp_avd/...release_connect_v2.1.0`, user 빌드), 앱은 user 10.
CAAS(`ai.pleos.playground.caas`) 4.2.10-coral-aosp. 측정일 2026-11-09.
앱 내 재현: 설정 → '점검' 탭, 또는 `adb shell am start --user 10 -n ai.chaz/.MainActivity --es tab SPIKE --es spike run` 후 `adb logcat -s ChazSpike ChazProbe`.

## 요약 — 기능별 선택 결과 (CapabilityProbe)

| 기능 | 선택 | 근거 |
|---|---|---|
| 주행 규제 | **CarUxRestrictions** | Car 서비스·UX 제한 매니저 연결, 정차 시 DO 요구=false |
| 차량 상태 | **Vehicle SDK** | 주행거리 READ 가능, 드라이브 모드는 `ERROR_NOT_SUPPORTED_PROPERTY` |
| 앰비언트 라이트 | **Vehicle SDK Light Set** | `checkAmbientLightBrightnessColorCapabilities` → 쓰기 가능, 좌석 영역 1개 |
| 위치·경로 | **NaviHelper SDK** | `getCurrentLocationInfo()` 콜백 수신 |
| 호출어 | 음성 버튼 (임시) | Pleos 커스텀 호출어 API 미확인, Vosk는 T07 |
| 명령 인식 | **Pleos SpeechToText SDK** | `STT_SERVICE` 권한 선언 후 initialize 성공. ON_DEVICE 인식 동작 |
| 음성 안내 | **Pleos TextToSpeech SDK** | `TTS_SERVICE` 권한 선언 후 ON_DEVICE 합성·재생 성공 |
| 전·후방 외부 카메라 | 없음 → 목 영상 필요 | AUTOMOTIVE_LOCATION 카메라 없음 |
| 실내 카메라 | 에뮬레이터 웹캠 | 카메라 #0 FRONT 1376x768 (AUTOMOTIVE_LOCATION 키 없음) |
| 날씨 | Open-Meteo | 에뮬레이터에서 외부 HTTPS 200 |
| AI 생성 | 비즈라우터(로컬 서버 경유) | `/health` keyConfigured=true |

## 항목별

### 카메라
- 카메라 1대: `#0`, `LENS_FACING=FRONT`, JPEG 최대 1376x768. **`AUTOMOTIVE_LOCATION` 키 없음** → 사양대로 LENS_FACING 추정 = 실내(웹캠 후보).
- `getConcurrentCameraIds()` 빈 목록 → 동시 오픈 불가. 컷 시각 순차 캡처(사양 F1) 필요.
- 차량 '카메라' 앱은 `ai.umos.car.evs`(서라운드뷰 EVS). `USE_CAR_EVS_CAMERA` 는 signature|privileged → 서드파티 사용 불가.
- 에뮬레이터 카메라 #0 의 영상은 **운전자가 앉은 차량 실내 가상 장면** → 실내 컷 데모에 그대로 쓸 수 있다 (T05 확인).
- 결론: 전·후방 = `assets/exterior-mock/` 사전 녹화 영상(T05: AI 생성 대시캠 이미지 2장을 줌·패닝, '시뮬레이션' 표기), 실내 = 웹캠 → 폰(P5) → AI 가상 실내 컷. **사무국 문의 필요: 실차 Camera2 노출 여부.**

### 마이크
- `AudioRecord(VOICE_RECOGNITION, 16kHz)` 정상 동작, 2초 32000샘플 수신.
- 호스트 마이크는 `adb emu avd hostmicon` 후 입력됨 (11/8 서버 STT로 실제 "찍어!" 인식 확인). 무발화 시 RMS≈0.5.

### Pleos TextToSpeech SDK 3.3.1 (웹 문서상 '준비중')
- **`pleos.car.permission.TTS_SERVICE` (protectionLevel normal) 선언 필요.** 없으면 `SecurityException`.
- `TextToSpeech(context, Mode)` → `initialize(OnInitializeListener)` → `addEventListener(...)` (**initialize 뒤에** 호출해야 함, 앞이면 `UninitializedPropertyAccessException`) → `speak(text, isFinal)`.
- `speak` 의 두 번째 인자는 스트리밍 텍스트의 마지막 여부. **`false` 면 onDone 이 오지 않는다** → 한 문장은 `true`.
- Mode별:
  - `SERVER`: `onError UNKNOWN`. CAAS 로그 `Client credentials missing for ai.chaz` → `registerApp(clientId, secret, ...)` 에 Playground 프로젝트 자격 증명 필요.
  - `ON_DEVICE`: **동작.** 한국어(ko_KR) 화자 seoyoon, 22.05kHz. 첫 호출 모델 로딩 ~1초, 문장 하나 onDone까지 4.4초.
  - `HYBRID`: 서버 실패 후 온디바이스로 재시도 → 재생됨.
- 결론: SpeechOutput 1순위 = Pleos TTS `ON_DEVICE`(또는 HYBRID). 서버 모드는 자격 증명 확보 시.

### Pleos SpeechToText SDK 3.3.1
- **`pleos.car.permission.STT_SERVICE` 선언 필요.**
- `SpeechToText(context, Mode)` → `addListener(ResultListener)` → `initialize` → `request()` (CAAS가 직접 마이크 녹음, 16kHz).
- `SERVER`: `Client credentials missing` → `onError UNKNOWN`. `HYBRID`/`ON_DEVICE`: 온디바이스 ko_KR 인식 루프 동작 확인(`recognize ... cnt=70`). 무발화라 최종 결과는 미확인 → **사용자 발화로 확인 필요**.
- 호출어: `RecognitionResult.isWakeUpWord`, `WakeUpWordConfig` 타입은 있으나 커스텀 호출어 등록 API는 미확인. TODO(pleos-api).

- T06 실사용: 시작 안내 "12초 동안 4컷 찍을게요. 창밖과 함께 웃어 주세요." ON_DEVICE 합성·재생 3.8초, 종료 안내 1.7초.

### Android 플랫폼 음성
- Android `TextToSpeech`: 초기화 실패(status=-1, 기본 엔진 없음).
- `SpeechRecognizer.isRecognitionAvailable()` = true 이지만 11/8 실측에서 `ERROR 11`(서버 연결 끊김). → Pleos STT 실패 시 비즈라우터 전사로 넘어가야 함(런타임 3회 실패 페일오버).

### 주행 규제 (T03)
- CarUxRestrictions 연결 성공, P단 정차 시 `DO 요구=false`.
- `cmd car_service inject-vhal-event` 는 user 빌드라 `SecurityException` → 기어 변경은 Virtual Console로만 가능.
- TODO: Virtual Console에서 D 전환 시 UX 제한이 걸리는지(속도 0 idling에서도 제한되는지) 확인. 걸리지 않으면 Vehicle SDK 기어를 함께 보는 방식으로 SPEC 변경.

### Vehicle SDK 2.3.2
- `Gear` API는 deprecated(대체: GEAR_SELECTION 속성)지만 `getCurrentGearSelectionState` 동작.
- Odometer `checkCurrentDistanceDrivenCapabilities` READ. DrivingMode `checkDrivingModeStateCapabilities` → `ERROR_NOT_SUPPORTED_PROPERTY`.
- Light `checkAmbientLightBrightnessColorCapabilities` 쓰기 가능(영역 1) → F3 앰비언트 실제 제어 가능성 높음 (T13에서 Set 확인).
- 권한 문자열(`PleosPermission.kt`): `pleos.car.permission.CAR_DRIVE`, `CAR_LIGHTS`, `CONTROL_CAR_LIGHTS`, `CAR_INFO`, `CAR_DOORS` 등, 모두 normal.

### NaviHelper SDK 3.0.5
- 권한 `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`.
- `initialize → addListener → getCurrentLocationInfo` 로 `onCurrentLocationInfo` 수신 확인.

### T07 음성 체인 실측
- 호출어: Pleos STT ON_DEVICE 연속 인식 루프(10초 단위 request 반복) 동작, 화면에 마이크 표시. **실제 "차즈" 발화 인식률은 사용자 확인 필요.**
- 명령 주입(adb `--es say64`, 점검 탭)으로 확인: "30초 동안 6번" → 30초 6컷, "그만" → 1컷 저장 후 중지, "다시 찍어줘" → 직전 설정, "오늘 저녁 뭐 먹지" → LLM 폴백 Unknown → "다시 말씀해 주세요".
- LLM 폴백(`/intent/parse`): gemini-3.1-flash-lite ~1.1초, gpt-5-nano ~1.7초, gpt-5.4-nano ~1.3초 → flash-lite 선택.
- adb shell 로 한글 extra 를 넘기면 깨진다 → base64(`say64`)로 주입. 실행 중인 화면에 보내려면 `-f 0x20000000`(SINGLE_TOP).

### T08 인물 분리·합성 실측
- MediaPipe tasks-vision 1.2.1, x86_64 네이티브 포함 → 에뮬레이터에서 온디바이스 동작.
- 모델 비교(실내 1376x768): selfie_segmenter 평균 0.33·경계가 화면 전체(좌석·창 포함) / deeplab_v3 경계 흐림 / **selfie_multiclass 인물 윤곽 깨끗**(평균 0.24, 좌석 일부 잡음) → 채택.
- 함정: JPEG 디코드 비트맵은 `hasAlpha=false` 라 마스크로 투명하게 만든 부분이 검게 그려짐 → `setHasAlpha(true)`. 경로만 `DST_IN` 으로 그리면 경로 밖 변환 이미지가 남음 → 전체 크기 마스크 비트맵으로 `DST_IN`.
- 디버그 APK 98MB(대부분 MediaPipe·Pleos 네이티브, 비압축 dex). ABI 는 arm64-v8a·x86_64 만 포함.

### T09 라이브 미리보기 실측
- L1 라이브: 인물 분리 포함 ~2.8fps → 자동으로 프레임만(~7fps). L2 는 인물 분리 포함 ~1fps.
- 주행 전환(개발용 오버라이드) → 미리보기 수집 중단까지 75ms, 정차 복귀 → 재시작 0.1초.
- 개발용 주행 전환: `adb shell am start --user 10 -f 0x20000000 -n ai.chaz/.MainActivity --es drive on|off|auto`

### T10 폰 전송 실측 (로컬 서버)
- 시뮬레이터 폰(Python)으로 확인: QR 클레임 → 차량 화면 자동 '연결됨 · 폰 1대' → 주행 중 "차즈" 촬영(Saved, 편집 잠금) → "차즈 폰으로 보내줘" → 기본 레이아웃(L2) 자동 합성·업로드 → 폰 SSE `result.delivered` → 서명 URL 로 1200x2486 JPEG 수신. TTS "완성본을 폰으로 보냈어요."
- 서명 URL 변조 → 403, 이미 연결된 QR 을 다른 폰이 쓰면 409.
- 서버가 알려 주는 QR 주소는 PC 의 기본 경로 IP(이 PC 는 <PC 공인 IP>). 다르면 `PUBLIC_BASE_URL` 로 지정.
- **실제 폰 브라우저 테스트는 사용자 확인 필요** (방화벽·같은 네트워크). http 라 Web Share·서비스 워커는 꺼지고 다운로드·공유 링크로 대신.

### T12·T13 스팟 가이드·앰비언트 실측
- 데모 드라이브(25m/s): 시작 ~78초 뒤 애월 해안도로 전망대 399m 에서 가이드 → 조명 '오션' 3초 페이드(pleos-vehicle-light, Set 실패 로그 없음) → TTS "곧 애월 해안도로 전망대예요…" → 9초 뒤 "차즈" → 세션 trigger=SPOT_GUIDE, spotName 스탬프 → 세션 끝 1초 뒤 조명 복원.
- 주행 화면 2줄째 "다음 스팟 애월 해안도로 전망대 599m".
- NaviHelper 현재 위치는 에뮬레이터 기본값(제주 아님), `adb emu geo fix` 는 즉시 반영되지 않음 → 데모 경로/수동 위치(`--es loc 33.46,126.31`).
- Overpass: Android 기본 User-Agent 는 406 → UA 지정. 에뮬레이터에서 status 요청이 자주 타임아웃 → 데모 스팟으로 대체.
- Open-Meteo 날씨·일몰 정상(일몰 17:26).
- 실제 차량 실내등 색 변화는 에뮬레이터에서 눈으로 확인할 수 없음 → **사무국 문의: 실차 Light Set 허용 범위**.

### 백그라운드 (B1·B2)
- **미검증.** T07 음성 체인 이후 Foreground Service로 확인 예정. 그 전까지 사양의 'window에 있을 때만(T4 중심)' 동작으로 둔다.

## Maven 저장소에서 확인한 Pleos SDK (2026-11-09)
`https://nexus-playground.pleos.ai/repository/maven-releases/` (`/service/rest/v1/search?repository=maven-releases`)
- Vehicle 2.0.3, **2.3.2**(sources·javadoc 포함) / NaviHelper 2.0.2, 2.0.3, **3.0.5**(sources) / FusedLocation 1.0.4, 1.1.3
- TextToSpeech 2.1.x, **3.3.0, 3.3.1** / SpeechToText 2.1.x, **3.3.0, 3.3.1** / LLM 2.1.2, 2.1.3.x
- 보조: vehicle-aidl, vehicle-data, navi-aidl, navi-data, fused-location-*, placesense V1, security/universal-access 0.6.0
- TTS/STT SDK는 CAAS 최소 버전 4.1.29 요구 (에뮬레이터 4.2.10 충족).

## 사무국 문의 목록
1. 실차에서 서드파티 앱의 Camera2 차량 카메라(전·후방·실내) 접근 허용 여부
2. TTS/STT SDK SERVER 모드용 `registerApp` 자격 증명 발급 방법 (Playground 프로젝트?)
3. Light Set(앰비언트) 실차 허용 범위
4. Pleos의 DO(distractionOptimized) 선언 방식
