# 차~즈 개발 사양서 (1차 심사 산출물용)

> 원본: `docs/차즈_개발사양서_v1.pdf` (2026-11-08, @정승원). 이 파일은 그 Markdown 판이며 **구현의 기준**이다.
> 사양과 다르게 구현해야 하면 먼저 이 문서를 고친다. 원본과 달라진 곳은 `> 변경:` 으로 표시한다.

## 개요

11월 14일까지 Pleos Connect Emulator에서 동작하는 차~즈 APK, GitHub 저장소, 시연 영상을 만든다. 제출용 기획서는 별도 문서.

**한 줄 정의** — 차~즈는 차량 실내의 인물과 차창 밖 풍경을 동시에 담아, 인생네컷처럼 사진과 영상을 남기는 차량용 포토부스 앱이다. 음성 호출어 '차~즈'로 촬영하고, 주행 맥락(위치, 경로, 시간, 날씨, 풍경)에 맞춰 앱이 먼저 촬영을 제안하고 조명을 연출한다.

**기본 컨셉: 차량 컨셉**
- 모든 컷은 외부 풍경 + 내부 인물 한 쌍. 사용자가 테마를 미리 고르지 않는다.
- 사진과 영상을 동시에 촬영. 영상을 계속 녹화하면서 일정 간격으로 사진을 찍고, 타이밍은 TTS로 안내.
- 기본값은 '12초 동안 4컷'. 음성으로 바꿀 수 있다.

### 1차 심사 산출물

| 산출물 | 형태 | 완성 기준 |
|---|---|---|
| APK | release 빌드, Pleos Connect Emulator(API 34)에서 설치·실행 | 핵심 루프(호출어 → 촬영 → 레이아웃 합성 → 폰 전송)가 끝까지 동작 |
| GitHub 저장소 | 소스 + README + APK(Releases 또는 apk/) | 심사위원이 README만 보고 실행 가능 |
| 시연 영상 | 에뮬레이터 화면 녹화 + Virtual Console 조작 + 폰 화면 | 9장 콘티의 장면을 모두 포함 |
| 숏폼(선택) | 30~60초 세로 영상 | 시연 영상 재편집 |

## Pleos 용어집

| 용어 | 뜻 |
|---|---|
| Pleos Connect | 현대차그룹 인포테인먼트 플랫폼. AAOS 기반 |
| Pleos Playground | 개발자 포털. 프로젝트 생성, SDK 사용 신청, 문서 |
| Pleos App Market | 차량용 앱 유통 플랫폼 |
| Vehicle SDK | 차량 상태 조회·제어 SDK. Playground 'Vehicle State'. 진입점 `Vehicle(context)`, `initialize()` / `release()` |
| 도메인 | Vehicle SDK 기능 단위(Light, Door, Window, Brake, DrivingMode 등). `getLight()` 처럼 `getXxx()` 로 획득 |
| Capability / Get / Set / Callback API | 지원 여부 확인(`checkXXXCapabilities`), 조회, 제어, 구독. 결과는 `onComplete` / `onFailed` 비동기 |
| NaviHelper SDK | 내비 연동. `NaviHelper`, `initialize()`, `addListener()`, `removeListener()`, `release()` |
| NaviHelperEventListener | 경로·목적지·위치 이벤트. 예: `onRoutingCompleted()`, `onCurrentLocationInfo()`, `onDestinationArrived()` |
| user-permission | Manifest의 `pleos.car.permission.*`. 공모전 개발 단계에서는 선언만으로 사용 가능 |
| Pleos Connect Emulator | API 34 Pleos Connect system image Automotive AVD |
| Virtual Console | 에뮬레이터의 차량 상태(기어, 도어, 배터리 등)를 바꾸는 도구 |
| CarUxRestrictions | 주행 규제 상태. `CarUxRestrictionsManager.registerListener()` → `onUxRestrictionsChanged()` |
| DO (Distraction Optimization) | 주행 중 사용 가능하도록 최적화했다고 선언한 앱. 미선언 앱은 주차 중에만 사용 가능 |
| Driving view / 앱 window | 왼쪽 고정 주행 정보 영역 / 오른쪽 앱 영역. portrait(window 1개) 또는 landscape(window 2개) |
| 오디오 포커스 | 미디어·안내 음성 재생 우선순위 정책 |
| Gleo AI / Gleo Interface | 차량 음성 에이전트와 서드파티 앱 연동 규격. 확장 단계 |

## SDK 가용성과 대체 전략

개발자 문서와 Playground에서 실제로 열린 SDK가 다르다. 기능마다 '실제로 동작하는 것'을 앱 실행 시점에 확인해 고른다. 시도 순서: **Pleos SDK → AAOS·Android 플랫폼 API → 비즈라우터 API → 외부·오픈소스**.

### 런타임 가용성 판정
1. 앱 시작 시 `CapabilityProbe` 가 기능별 후보를 위에서부터 시도한다.
   - Pleos SDK: 클래스 로딩 → `initialize()` 성공 → 해당 도메인 `checkXXXCapabilities` 통과까지 되어야 '사용 가능'
   - 플랫폼 API: 서비스·하드웨어 존재 확인. 예: `CameraManager.getCameraIdList()`, `SpeechRecognizer.isRecognitionAvailable()`, `TextToSpeech` 한국어 `isLanguageAvailable()`
   - 비즈라우터·외부 API: 네트워크 연결 + Edge Function health 응답
2. 결과는 `SdkAvailability`(기능 → 선택된 구현체 + 사유)에 저장하고, 설정 화면 'SDK 연동 상태' 패널에 그대로 보여 준다.
3. 실행 중 실패(`onFailed`, 네트워크 끊김)가 3회 연속되면 다음 후보로 내려가고 패널에 기록한다.
4. 문서와 실제가 다르면 실제 결과를 따르고, 차이는 `docs/spike.md` 에 적는다.

### 기능별 후보 체인 (왼쪽부터 시도)

| 기능 | 1순위 | 2순위 | 3순위 | 최후 수단 | 어댑터 |
|---|---|---|---|---|---|
| 주행 규제 | CarUxRestrictions | Vehicle SDK 주차 브레이크·드라이브 상태 | — | 개발용 디버그 토글 | `DrivingGate` |
| 차량 상태(도어, 드라이브 모드, 주행거리) | Vehicle SDK | AAOS CarPropertyManager | — | 표시 생략 | `VehicleStateProvider` |
| 앰비언트 라이트 | Vehicle SDK Light Set | — | — | 화면 테두리 색 시뮬레이션 | `AmbientLightController` |
| 위치·경로 | NaviHelper SDK | FusedLocation SDK | Android LocationManager | 데모 경로 재생 | `LocationSource`, `RouteSource` |
| 호출어 | SpeechToText SDK(호출어 지원 시) | Vosk 한국어(오프라인, 문법 제한) | — | 음성 버튼 | `WakeWordDetector` |
| 명령 인식(호출어 뒤 발화) | SpeechToText SDK | Android SpeechRecognizer | 비즈라우터 오디오(전사) | Vosk 자유 발화 | `SpeechInput` |
| 음성 안내 | TextToSpeech SDK | Android TextToSpeech(한국어) | 비즈라우터 오디오(TTS, 결과 캐시) | 미리 녹음한 클립 | `SpeechOutput` |
| 전방·후방 외부 카메라 | 차량 카메라(Camera2) | 사전 녹화 주행 영상 | — | — | `CameraSource` |
| 실내 카메라 | 차량 실내 카메라(있으면) | 폰 컴패니언 카메라 | 에뮬레이터 웹캠 | 비즈라우터 이미지 생성 '가상 실내 컷' | `CameraSource` |
| 서라운드·파노라마 | 차량 측면·서라운드 카메라(있으면) | 전후방 프레임 합성 + 비즈라우터 아웃페인팅 | 비즈라우터 이미지 생성 | — | `SurroundComposer` |
| 인물 분리 | MediaPipe Image Segmenter(온디바이스) | 비즈라우터 이미지 편집 | — | — | `PersonSegmenter` |
| 사진 스팟 | Overpass + TourAPI | 비즈라우터 텍스트(주변 명소 추천) | — | 데모 스팟 목록 | `SpotRepository` |
| 날씨·일몰 | Open-Meteo | — | — | 시각으로 추정 | `ContextProvider` |
| 영상 합성 | Media3 Transformer | — | — | — | `MediaComposer` |
| AI 필터·영상 연장 | 비즈라우터 이미지·영상 | — | — | 필터 숨김 | `GenAiClient` |
| BGM | 인앱 CC0 음원 | — | — | — | `BgmPlayer` |

### 구현 규칙
1. 어댑터마다 인터페이스 하나, 후보 구현체들, 테스트용 `Fake*` 구현체를 둔다.
2. 구현체 선택은 `CapabilityProbe` → `SdkAvailability` 에서만 한다. 기능 코드에 SDK 가용 여부 분기를 쓰지 않는다.
3. Pleos SDK 호출 전에는 반드시 `checkXXXCapabilities` 를 확인한다.
4. 비즈라우터 오디오·이미지·영상 모델은 Edge Function을 거쳐 호출하고, 키는 서버에만 둔다. 고정 문구(카운트다운 등)는 앱 시작 때 미리 합성해 캐시한다.
5. 대체 소스나 AI 생성을 쓴 결과물에는 메타데이터 `sourceKinds` 를 남기고, 화면과 시연 영상에 '시뮬레이션' 또는 'AI 생성'을 표기한다.

## 시스템 아키텍처

차량 앱의 기능 모듈은 어댑터 계층만 본다. 어댑터 뒤에 Pleos SDK, 대체 구현, 외부 API가 있고, 폰과 비즈라우터는 Supabase를 통해서만 연결된다. 커밍순 SDK가 열리면 '대체 구현' 항목이 'Pleos SDK' 쪽으로 옮겨 가고 기능 모듈 코드는 바뀌지 않는다. 차량 앱과 폰은 직접 통신하지 않고 Supabase Realtime 이벤트로만 주고받는다.

> 변경(2026-11-09): T10 전까지 Edge Function 역할(health, tts-synthesize, stt-transcribe, image-generate)은 로컬 개발 서버 `server/`(FastAPI)가 대신한다. 앱은 `ChazServer` 클라이언트로만 접근하고, 키는 서버 `.env` 에만 있다.

## 앱 상태와 동작 모델

6개 상태. 촬영이 끝난 뒤 어느 상태로 갈지는 `DrivingGate` 가 주행/정차로 정한다. '저장 · 편집 잠금'이 안전 설계의 핵심: 주행 중에 끝난 세션은 Saved 에서 멈추고, 정차하면 편집으로, 음성 명령을 받으면 폰으로 넘어간다.

```kotlin
sealed interface AppState {
    object Standby : AppState
    data class Capturing(val session: Session, val shotsTaken: Int) : AppState
    data class Saved(val sessionId: String) : AppState      // 주행 중 편집 잠금
    data class Editing(val sessionId: String) : AppState    // 정차 중만
    data class Handoff(val sessionId: String) : AppState
    data class Delivered(val resultId: String) : AppState
}
sealed interface AppEvent {
    data class Trigger(val source: TriggerSource, val config: CaptureConfig) : AppEvent // T1~T4
    data class ShotTaken(val shotsTaken: Int) : AppEvent // > 변경(11/9): 컷 카운터 갱신용 추가
    object SessionFinished : AppEvent
    data class DrivingChanged(val restricted: Boolean) : AppEvent // DrivingGate
    object HandoffRequested : AppEvent
    object SendToPhone : AppEvent
    object Stop : AppEvent
}
```
- 전이는 `AppStateMachine.reduce(state, event): AppState` 순수 함수 하나로 만들고 단위 테스트한다.
- Editing 중 `DrivingChanged(restricted = true)` → 편집 내용 임시 저장 후 Saved.
- Capturing 중에는 주행 여부와 상관없이 촬영을 이어간다. 화면 표시만 바뀐다.
- 백그라운드로 가면 화면 갱신·카메라 미리보기를 멈추고, 진행 중 세션은 저장해 프로세스 재생성 시 복원한다.

## 기능 명세

F1 촬영 세션이 핵심. F2는 F1을 시작시키고, F3·F4는 분위기, F5·F6은 결과물을 완성한다.

### F1. 촬영 세션

**트리거**

| ID | 트리거 | 조건 | 구현 확실도 |
|---|---|---|---|
| T1 | 호출어 "차~즈" | 앱이 window에 표시 중일 때 항상. 백그라운드는 스파이크 B2 결과에 따름 | 포그라운드 확실 / 백그라운드 검증 필요 |
| T2 | 음성 버튼 1회 탭 후 명령 | 촬영 설정 변경·재촬영용 | 확실 |
| T3 | 스팟 가이드 수락 | F2가 TTS로 제안한 뒤 15초 안에 호출어 | 앱 표시 중 확실 / 백그라운드 검증 필요 |
| T4 | 앱 직접 실행 | 앱을 열면 '촬영 대기' 화면. 호출어 대기 시작 | 확실 |

권장 사용법: 출발 전에 차~즈를 내비 옆 window(portrait)에 띄워 둔다. 이 상태에서 T1·T3는 백그라운드 검증 없이 동작.

**백그라운드 스파이크 (Day 1 검증)**
- B1: 화면에 없을 때 Foreground Service(type `location`)로 스팟 근접을 감지하고 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 으로 TTS 재생 가능한가
- B2: Foreground Service(type `microphone`)로 화면에 없을 때도 호출어를 감지할 수 있는가

| B1 | B2 | 동작 |
|---|---|---|
| 가능 | 가능 | 완전 백그라운드: 내비 전체 화면에서도 가이드와 호출어 동작 |
| 가능 | 불가 | 가이드는 백그라운드 TTS, 호출어는 window에 있을 때만. 가이드에 "차~즈 화면을 열어 두면 말로 찍을 수 있어요" 추가 |
| 불가 | — | window에 있을 때만 가이드·호출어 (T4 중심) |

**기본 흐름 (12초 4컷)**
1. 호출어 인식 → TTS "12초 동안 4컷 찍을게요. 창밖과 함께 웃어 주세요."
2. 안내가 끝나면 t=0에 실내·외부 영상 녹화를 동시에 시작.
3. 간격 = 총 시간 ÷ 컷 수(기본 3초). 각 컷 직전 TTS "셋, 둘, 하나" → 셔터음 → 실내 사진과 외부 프레임을 같은 시각에 캡처. t=3, 6, 9, 12초.
4. t=총 시간+0.5초에 녹화 종료.
5. 종료 TTS: 주행 중 "다 찍었어요. 정차하면 편집할 수 있어요." / 정차 중 "편집 화면으로 갈게요."

**촬영 설정 제약** — 컷 수 1~20, 총 시간 4~120초, 간격 최소 2초. 간격 3초 미만이면 카운트다운을 "둘, 하나"로 줄인다. 범위를 벗어나면 가장 가까운 값으로 맞추고 TTS로 알린다.

**카메라 소스** — 한 컷 = 같은 시각의 실내·전방·후방 프레임 세 장(있으면 측면까지).
- 카메라 찾기: `CameraManager.getCameraIdList()` → `CameraCharacteristics.AUTOMOTIVE_LOCATION`(API 33+)으로 전방·후방·실내·측면 구분. 키가 없으면 `LENS_FACING` 과 ID 순서로 추정하고 결과를 SDK 연동 상태 패널에 표시.
- 동시 촬영: `getConcurrentCameraIds()` 로 동시 오픈 조합 확인. 불가하면 컷 시각에 실내 → 전방 → 후방 순서로 150ms 안에 번갈아 캡처.

| 소스 | 1순위 | 대체 1 | 대체 2 | 대체 3 |
|---|---|---|---|---|
| 전방 외부 | 차량 전방 카메라(AUTOMOTIVE_LOCATION=전방 외부) | 사전 녹화 주행 영상 | — | — |
| 후방 외부 | 차량 후방 카메라 | 사전 녹화 영상 | — | — |
| 실내 | 차량 실내 카메라(있으면) | 폰 컴패니언 카메라(대시보드 거치, P5) | 에뮬레이터 웹캠 | 비즈라우터 '가상 실내 컷'(시연용, 표기 필수) |
| 측면·서라운드 | 차량 측면 카메라(있으면) | 전후방 프레임 + 비즈라우터 아웃페인팅 | 비즈라우터 이미지 생성 | — |

- 폰 실내 카메라: 차량이 Realtime으로 `capture.shot`(컷 번호, 시각)을 보내면 폰이 촬영해 업로드. 지연 0.3~1초를 카운트다운에 흡수하도록 폰에는 0.5초 일찍 보낸다.
  > 변경(11/11, T11): 폰은 카메라 앱 촬영 대신 **켜 둔 영상 스트림에서 프레임을 바로 떠서**(지연 0.1초 안팎) 셔터 시각에 요청한다 — 일찍 보내면 셔터보다 먼저 찍힌다. 차량은 `POST /sync/camera/shot` 한 번으로 요청·대기·수신(최대 4초), 실패하면 최근 미리보기 프레임으로 대신한다. 폰은 카메라 모드 동안 0.5초마다 저해상 프레임을 올려 차량 미리보기·클립·온라인 판정에 쓴다. 브라우저 카메라는 보안 컨텍스트가 필요해 서버가 **HTTPS(자체 서명, 8788)** 도 함께 열고 페어링 QR은 HTTPS 주소를 쓴다(폰에서 인증서 경고 한 번 허용). 폰 카메라 모드가 켜지면 차량은 실내 소스를 폰으로 다시 판정한다(촬영 중에는 바꾸지 않음).
  > 변경(11/11, 도메인 공개 후): Cloudflare 경유 시 이벤트 지연이 0.3~1.8초라 셔터 시각 요청으로는 늦게 찍힌다. **예약 촬영**으로 바꾼다 — 엔진이 컷마다 카운트다운 전(2초 이상 앞)에 `scheduleShot` → 차량이 `POST /sync/camera/arm`(서버 시계 기준 셔터 시각 atMs) → 폰이 서버와 맞춘 시계로 그 순간 프레임을 떠서 업로드 → 셔터 시각의 `capture()` 는 `GET /sync/camera/result/{shotId}` 로 받는다. 차량·폰 모두 `GET /sync/time` 으로 시계를 맞춘다(왕복 최소 표본). 예약이 없으면 즉시 촬영(`/shot`), 그것도 실패하면 미리보기 프레임.
- 영상: 사용 가능한 소스마다 녹화. 동시 녹화가 안 되면 실내는 녹화, 외부는 0.5초 간격 스냅샷 시퀀스.
  > 변경(11/9, T06): 에뮬레이터는 동시 오픈이 안 되고 외부가 시뮬레이션이라 **모든 소스를 0.5초 간격 스냅샷 시퀀스**(`clips/{role}/0000.jpg…`)로 저장한다. 영상 파일 변환은 MediaComposer(T14). 실내 VideoCapture 녹화는 실차 카메라 확인 후.
- 카운트다운: "셋, 둘, 하나"는 실시간 TTS 대신 미리 만든 클립(`assets/tts/cd_*.mp3`, 약 0.9초)을 컷 시각 2.7/1.8/0.9초 전에 재생한다. 셔터음은 시스템 `MediaActionSound`.
- 스틸은 CameraX ImageCapture, 안 되면 녹화 영상에서 `MediaMetadataRetriever.getFrameAtTime()`.
- 녹화 중 마이크 오디오는 기본 끔(저작권·프라이버시). 설정에서 켤 수 있다.

**정차 중 라이브 미리보기** — 프레임과 필터를 씌운 화면을 보면서 찍는다.
- 레이아웃 프레임 PNG을 미리보기 위에 오버레이.
- L1·L2는 MediaPipe 라이브 스트림 모드로 인물 마스크 → 차창·배경 교체를 실시간 표시. 목표 15fps, 못 미치면 프레임만.
- 색 필터는 LUT를 GPU(OpenGL ES 셰이더)로 실시간 적용. AI 필터는 가장 비슷한 LUT로 미리보기, 촬영 후 실제 생성.
- 미리보기에서 고른 레이아웃·필터는 편집 화면 기본값.
  > 변경(11/9, T09): 색 필터는 LUT·GL 셰이더 대신 **ColorMatrix 근사**(원본·필름·노을·오션·흑백)로, 원본 카메라 미리보기는 RenderEffect(GPU), 합성 미리보기·결과물은 같은 행렬로 그린다. 라이브 합성은 실내·전방·후방 최신 프레임으로 같은 LayoutRenderer 를 저해상(0.6배) 반복 실행하고, 워밍업 3프레임 뒤 평균 6fps 미만이면 인물 분리를 끄고 프레임만 씌운다(에뮬레이터 CPU 실측: 인물 분리 포함 ~2.8fps → 프레임만 ~7fps). L2 미리보기는 같은 순간을 4칸에 반복해 모양을 보여 준다.
- 주행으로 바뀌면(`DrivingGate`) 미리보기를 즉시 멈추고 고정 텍스트 상태로. 촬영은 계속.

**주행 중** — 미리보기 없음. "촬영 중 · 4컷 중 2" 같은 고정 텍스트만.

### F2. 선제 가이드 (스팟·시간)
- 스팟 수집: NaviHelper `onRoutingCompleted()` 시점과 앱 시작 시 현재 위치 반경 3km 스팟 조회. 이후 2km 이동마다 재조회해 캐시. 경로 좌표를 받을 수 있으면 경로 500m 폭 안의 스팟만.
- 스팟 점수: viewpoint·beach 3점, attraction·peak·TourAPI 관광지 2점. 2점 이상만 가이드.
- 발동: 스팟 400m 이내 접근 중. 가이드 간 최소 5분, 스팟당 여정 1회. `onTBTInfo()` 회전 안내가 300m 안이면 미룸.
- 문구: "곧 {스팟명}이에요. 창밖 풍경과 함께 찍으려면 '차즈'라고 말해 주세요." 15초 동안 호출어 대기, 없으면 조용히 지나감.
- 시간 가이드: Open-Meteo 일몰 전후 30분에 "노을 시간이에요..." 여정당 1회.

> 변경(11/9, T12): 에뮬레이터 위치를 바꾸기 어려워 '데모 경로 재생'(assets/spots/demo_route.json, 애월 해안도로 25m/s)을 조명 화면·adb(`--es demo start`)로 켠다. TourAPI 는 키가 필요해 이후로, Overpass 는 에뮬레이터에서 타임아웃이 잦아 데모 스팟(assets/spots/demo_spots.json)이 함께 쓰인다. 경로 500m 폭 필터는 NaviHelper 경로 좌표 확인 후.

### F3. 앰비언트 연출
입력: 풍경 유형, 시간대, 날씨, BGM 분위기. 우선순위 날씨 > 시간대 > 풍경 > BGM.

| 조건 | 프리셋 | 색 |
|---|---|---|
| 비·눈·흐림 | 코지 | 웜 화이트, 낮은 밝기 |
| 일몰 전후 30분 | 골든아워 | 앰버·오렌지 |
| 밤 + 도시 | 시티나이트 | 퍼플·마젠타 |
| 낮 + 해안 | 오션 | 아쿠아 블루 |
| 낮 + 숲·산 | 포레스트 | 그린 |
| 그 외 | BGM 분위기 색 | 신나는 곡=마젠타, 잔잔한 곡=스카이 블루 |

- 주행 중에는 세션 시작 때 한 번만. 3초 이상 페이드, 밝기는 현재 값 이하, 깜빡임 금지.
- 정차 중에는 모든 프리셋과 '촬영 조명'(야간 얼굴용 밝은 웜 화이트).
- Set 전에 Get으로 원래 상태 저장, 세션 끝나면 복원.
- Light Set을 못 쓰면 앱 화면 테두리 색으로 대신하고 "앰비언트 라이트(시뮬레이션)" 표기.
> 변경(11/9, T13): 스팟 가이드도 '에피소드 시작'으로 본다 — 가이드 때 한 번 바꾸고, 15초 안에 촬영으로 이어지면 같은 에피소드(다시 바꾸지 않음), 아니면 복원. 에뮬레이터에는 눈에 보이는 실내등이 없어서 실제 Light Set 과 함께 화면 테두리에 같은 색을 비춘다(미러). 풍경은 가까운 스팟 종류(데모 스팟은 scene 필드)로 정하고, '도시' 판정은 이후.

### F4. BGM
- 인앱 CC0 음원 8~12곡 + 분위기 태그(신나는, 잔잔한, 감성, 드라이브). 출처·라이선스는 `assets/bgm/LICENSES.md`.
- "차즈 노래 틀어줘" → 맥락에 맞는 곡. "차즈 신나는 노래" → 그 태그에서.
- `AUDIOFOCUS_GAIN` 으로 재생, TTS 안내 중 볼륨 낮춤. 촬영 중 재생된 BGM은 결과 영상 오디오 트랙으로 믹싱.
- 다른 음악 앱 제어는 MVP 제외(Gleo Interface 확장). 결과물에는 인앱 CC0 음원만(저작권).

### F5. 편집과 레이아웃
레이아웃은 '차량 컨셉' 4종, 모두 내부 인물 + 외부 풍경. 정차 중 인포테인먼트에서 편집, 주행 중에는 자동 저장만 하고 편집 잠금.

| ID | 레이아웃 | 재료 | 결과물 | 우선순위 |
|---|---|---|---|---|
| L1 | 차창 합성형 `WINDOW_SWAP` | 실내 컷 + 같은 시각 전방·후방 프레임 | 사진(영상은 확장) | P0 |
| L2 | 컷형 프레임 `CUT_FRAME` | 외부 풍경 1~4장 + 인물 컷아웃 2~4개(셀렉) | 사진 | P0 |
| L3 | 여행 경로형 `ROUTE_OVERLAY` | 전방 풍경 + 경로·현재 위치·목적지 + 인물 원형 컷(선택) | 사진, 영상(경로 진행) | 사진 P1 |
| L4 | 서라운드·순차 배경형 `SURROUND_SEQUENCE` | 차량 3D 모델 렌더 + 서라운드·파노라마 배경 + 여정의 여러 외부 컷 | 사진, 9:16 영상 | P1 |

**L1. 차창 합성형** — 실내 사진에서 인물·시트·핸들은 두고 창밖만 차량 카메라 사진으로 교체. 뒷유리=후방 프레임, 양옆 창=전방 프레임.
1. 바탕: 실내 컷 원본
2. 창문 영역: 실내 카메라 위치 고정이면 정적 마스크 `assets/masks/{cameraId}_windows.json`(창문별 사각형 꼭짓점). 폰·웹캠처럼 위치가 바뀌면 비즈라우터 이미지 편집(인물·시트·핸들 유지, 창밖만 교체).
3. 창문 채우기: 원근 변환, 가장자리 8px 페더, 실내 밝기에 맞춰 노출 보정
4. 인물 레이어: MediaPipe 인물 마스크로 원본 인물을 맨 위에 다시 올림
5. 테두리: 얇은 필름 테두리 + 하단 차량 데이터 스탬프

> 변경(11/9, T08): 인물 분리 모델은 `selfie_multiclass_256x256`(배경 클래스의 반대 = 인물). `selfie_segmenter` 는 넓은 실내에서 좌석·창까지 인물로 잡아 창 교체를 덮었다. 확률 0.5~0.85 구간을 부드럽게 깎고, L2 누끼 경계는 가장 큰 덩어리만 쓴다. 에뮬레이터 실내 카메라 #0 창문 마스크는 `assets/masks/0_windows.json` (좌·뒷좌석 좌·뒷유리·우 4개).

> 변경(11/11): **L1 = 오픈카**. 창문 4개만 바꾸던 차창 합성 대신, 고정 실내 카메라의 좌석 다각형(`assets/masks/{cameraId}_windows.json` 의 `cabin`) ∪ 인물 마스크만 남기고 지붕·기둥·창 전체를 후방 풍경(수평선 = `horizonY`)으로 바꾼다. 하늘에 손글씨 캡션, 주황 날짜, 입자·비네팅. `cabin` 이 없는 카메라는 예전 창문 교체.

> 변경(11/12, T17 일부 앞당김 — 사용자 결정): **✨ AI 오픈카**. 앱 합성(L1)은 무료 즉시 미리보기로 두고, 편집 화면 버튼을 누르면 고른 컷 1장의 실내 사진(+ 같은 순간 후방 풍경)을 서버 `POST /frames/opentop` → 비즈라우터 `google/gemini-3.1-flash-image` 로 보내 '지붕 연 오픈카' 사진을 받는다(약 12초, 장당 약 104 — 비즈라우터 표기). 얼굴·표정·옷은 유지하도록 지시하고, 결과물에 'AI 편집' 표기. 고정 마스크가 필요 없어 **폰 실내 카메라 컷에도 된다**. 서버는 사진을 저장하지 않고, 외부 요청은 앱 토큰 + 하루 이미지 한도(30)에 들어간다. 실패하면 앱 합성 결과를 그대로 쓴다.
> 변경(11/12): **촬영 모드** — 설정·대기 화면에서 고른다. `시연`(기본) = 실내는 에뮬레이터 샘플 카메라(폰이 연결돼 있어도), `실촬영` = 실내는 폰 컴패니언 카메라만(없으면 연결 안내). 실내 소스 판정(CapabilityProbe)에 모드가 반영된다. 외부는 두 모드 모두 시뮬레이션 풍경(실차 카메라 없음).

**L2. 컷형 프레임** — 풍경 배경 + 고른 인물을 칸에 배치하는 인생네컷식.
  > 변경(11/11): '합성 티' 대신 필름 사진처럼 — 배경 살짝 흐림, 인물은 가슴 위만 칸 아래에 붙임(핸들 조각 제거), 마스크 가장자리 깎기, 비네팅·필름 입자·주황 날짜 각인('26 11 12). 프레임 기본 = 필름 스트립(구멍·가장자리 글씨), 종이 프레임은 손글씨 캡션(나눔손글씨 펜, OFL — `assets/fonts`). 실내를 폰 카메라로 찍은 컷은 L1 불가(창문 마스크는 고정 카메라 전용).
- 세로 2칸 또는 4칸. 칸마다 외부 풍경(칸마다 다른 컷 가능)
- 인물 누끼를 칸에 배치. 정차 편집 중 크기·위치 드래그
- 장식: 칸 사이 프레임 색, 양쪽 세로 텍스트(장소명, 날짜, 'CHA~Z', 컷 번호)
- 프레임 디자인은 자체 제작만.

> 변경(11/12, L3 구현): L3 여행 경로형을 1차에 앞당겨 넣었다. 1600×900 한 장, 컷 1장. 전방 풍경 + 손글씨 캡션(장소명 없으면 '오늘의 드라이브') + 왼쪽 아래 경로 카드(지나온 구간 실선, 남은 구간 점선, 현재 위치 파란 점, 다음 스팟 주황 핀, 남은 거리·도착 시각) + 오른쪽 아래 동그란 인물 사진. 지도 타일은 쓰지 않는다. 경로는 데모 경로, 목적지는 다음 스팟, 도착 시각은 직선거리 ÷ 40km/h 추정 — Navi 목적지·경로를 받으면 그 값으로 바꾼다. 경로 카드는 렌더할 때의 위치로 그린다(세션에 경로를 저장하는 건 이후). L4는 2차.

**L3. 여행 경로형** — 전방 풍경 → 반투명 지도 카드(경로선, 현재 위치 핀, 목적지명, 남은 거리·도착 예정) → 손글씨체 캡션 → 인물 원형 컷(선택). 데이터: NaviHelper `onDestinationInfo()`, `onCurrentLocationInfo()`, `onTripInfo()`. 지도 타일 OSM(MapLibre 정적 렌더, 출처 표기). 캡션은 비즈라우터 텍스트로 15자 이내, 사용자 수정 가능. 영상(확장): 경로선 진행 애니메이션.

**L4. 서라운드·순차 배경형** — 차량 모델을 가운데 두고 주변 풍경.
- 차량 모델: 로고 없는 범용 3D 모델(CC0 또는 자체) 미리 렌더 PNG(탑뷰, 3/4뷰). 실시간 3D(Filament)는 확장.
- 배경(사진): 전방 가운데, 후방 양끝, 빈 좌우는 비즈라우터 아웃페인팅 → 파노라마. 측면 카메라 있으면 우선.
- 서라운드 탑뷰: 1차는 비즈라우터 이미지 생성(전후방 참조) + 차량 모델 합성.
- 순차 배경 영상(9:16): 차량 고정, 배경은 세션별 외부 컷을 시간순 1~1.5초씩, BGM 비트 전환, 하단 장소명. 끝 장면은 인물 컷 + 여정 요약(스팟 수, 주행거리).

**공통 편집 단계**
1. 컷 셀렉(기본값은 미리보기에서 고른 레이아웃·필터)
2. 레이아웃 선택(L1~L4)
3. AI 필터(선택, 비동기): 이미지 스타일 3종(필름, 애니메이션, 수채화), 영상 연장 2종(구름 위, 바닷속). 사고·추격·위험운전 연출 금지.
4. BGM 선택(영상)
5. 자동 스탬프: 장소명, 날짜, 날씨, 드라이브 모드(DrivingMode), 주행거리(Odometer)
6. 저장 / 폰으로 보내기

합성: 사진은 Canvas, 영상은 Media3 Transformer. 레이아웃마다 `LayoutRenderer` 하나, 미리보기(저해상)와 최종(고해상)에 같은 렌더러.

### F6. 폰 핸드오프·전송
- 페어링(최초 1회): 정차 중 결과·설정 화면에 QR(페어링 토큰이 담긴 PWA URL). 이후 자동 재연결. **QR은 주행 중 표시하지 않는다.**
- 핸드오프: "차즈 스마트폰에서 편집할게" → 세션 원본 업로드 + 폰 실시간 이벤트. TTS "폰으로 보냈어요. 조수석에서 편집해 주세요." 미페어링이면 "정차 후 화면의 QR로 폰을 연결해 주세요."
- 폰 편집 범위(MVP): 사진 레이아웃 + AI 이미지 필터. 영상 편집은 차량에서만.
- 완성본 전송: "폰으로 보내기" 버튼 또는 "차즈 폰으로 보내줘" → 업로드 → 폰에서 저장·공유.

## 음성 인터페이스

호출어 '차~즈' 단독은 기본 촬영, 뒤에 명령을 붙이면 인텐트. 규칙 파서가 먼저, 맞는 규칙이 없을 때만 LLM.

**호출어 감지**
- 엔진: Vosk 한국어(오프라인, 문법 제한). 감지되면 뒤 명령을 최대 5초 녹음해 SpeechToText SDK → Android SpeechRecognizer → 비즈라우터 전사 → Vosk 자유 발화 순으로 인식.
  > 변경(11/9, T07): Pleos SpeechToText SDK 가 에뮬레이터에서 **온디바이스(ko_KR)로 동작**해서 호출어 1순위를 'SpeechToText 연속 인식 결과에서 "차즈" 찾기'로 한다(무료·로컬). Vosk 는 APK 용량 때문에 넣지 않고, 최후 수단은 음성 버튼. 한 문장에 명령까지 있으면("차즈 1분 동안 10번 찍어줘") 바로 실행한다.
  > Android SpeechRecognizer 는 실측 ERROR 11 이라 후보에서 '사용 불가'로 둔다. LLM 폴백 모델은 `google/gemini-3.1-flash-lite`(실측 ~1.1초).
- 변형: "차즈", "차즈야", "차 즈". "치즈"는 받지 않는다(오인식 방지).
- 신뢰도 임계값, 마지막 세션 종료 후 3초 무시.
- 감지 피드백: 짧은 효과음 → 명령이 없으면 1.5초 뒤 기본 촬영.
- 마이크 사용 중 마이크 아이콘 항상 표시. 음성 원본은 저장·전송하지 않는다.

**인텐트**

| 인텐트 | 발화 예 | 슬롯 | 주행 중 | 응답 TTS |
|---|---|---|---|---|
| CAPTURE_DEFAULT | "차~즈" | — | 허용 | "12초 동안 4컷 찍을게요." |
| CAPTURE_CUSTOM | "차즈 1분 동안 10번 찍어줘", "차즈 30초 동안 다섯 장" | durationSec, count | 허용 | "1분 동안 10컷 찍을게요." |
| RETAKE | "차즈 다시 찍어줘" | 직전 설정 재사용 | 허용 | "다시 찍을게요." |
| STOP | "차즈 그만", "차즈 멈춰" | — | 허용 | "촬영을 멈췄어요." |
| BGM_PLAY | "차즈 노래 틀어줘", "차즈 잔잔한 노래" | mood? | 허용 | "{분위기} 노래를 틀게요." |
| BGM_STOP | "차즈 노래 꺼줘" | — | 허용 | "노래를 껐어요." |
| HANDOFF_PHONE | "차즈 스마트폰에서 편집할게" | 대상 세션(기본 최신) | 허용 | "폰으로 보냈어요." |
| SEND_PHONE | "차즈 폰으로 보내줘" | 대상 결과물 | 허용 | "완성본을 폰으로 보냈어요." |
| LIGHT_MOOD | "차즈 분위기 바꿔줘" | preset? | 정차 중만 | "{프리셋} 조명으로 바꿨어요." / 주행 중 "정차 후에 바꿀 수 있어요." |
| HELP | "차즈 뭐 할 수 있어?" | — | 허용 | 명령 예시 3개 낭독 |
| UNKNOWN | 그 외 | — | — | "다시 말씀해 주세요." |

**파서 사양**
1. 정규화: 공백·물결표 제거, 호출어 변형을 "차즈"로 통일
2. 한글 숫자: 한·하나=1, 두·둘=2, 세·셋=3, 네·넷=4, 다섯=5 ~ 스무=20, 일~십, 아라비아 숫자
3. 시간 패턴 `(\d+)\s*(초|분)` → 초
4. 횟수 패턴 `(\d+)\s*(번|컷|장)`
5. 키워드로 나머지 인텐트(다시, 그만/멈춰, 노래, 폰/스마트폰, 분위기/조명)
6. 실패 시 LLM 폴백: 비즈라우터 텍스트 모델에 인텐트 목록 + JSON 스키마. 타임아웃 2초, 넘으면 UNKNOWN
7. 슬롯 검증: F1 촬영 설정 제약 적용, 조정했으면 TTS로 알림

파서는 순수 함수 `IntentParser.parse(text): Intent`, 위 표의 발화 예 전부를 단위 테스트 케이스로.

**TTS 문구 관리** — 모든 안내 문구는 `res/values/strings_tts.xml` 한 곳. TextToSpeech SDK → Android TTS → 비즈라우터 TTS 순. 고정 문구는 앱 시작 때 미리 합성해 캐시, 네트워크가 없으면 `assets/tts/*.ogg`. 스팟명처럼 바뀌는 문구는 대체 시 "곧 멋진 스팟이에요". 안내는 한 문장, 3초 이내.

## 화면 명세

디자인 방향 '촬영 장비 콘솔'. 어두운 배경 위 큰 뷰파인더 카드, 상태 카드, 하단 컨트롤 패널. 터치 영역·대비는 Pleos Connect UI Design Guide.

**디자인 토큰**

| 토큰 | 값 | 용도 |
|---|---|---|
| bg | #0B0C0E | 화면 배경 |
| surface | #16181B | 카드 |
| surface-2 | #1F2226 | 카드 안 컨트롤 |
| line | #2A2E33 | 구분선·테두리 |
| text / text-2 | #FFFFFF / #9AA0A6 | 본문 / 보조 |
| accent | #3D8BFF | 선택된 칩, 현재 값 |
| rec | #FF3B30 | 녹화 중 |
| 모서리 | 카드 24, 컨트롤 16, 셔터·패드 원형 | |
| 간격 | 8 단위 | |
| 숫자 | 고정폭(tabular) | 타이머·거리·컷 카운터 |

**정차 중 촬영 화면 (landscape)**
1. 왼쪽 레일: 홈, 촬영, 갤러리, 조명, 설정. 아래에 페어링된 폰 상태
2. 뷰파인더 카드: 라이브 미리보기 + 레이아웃 프레임 + 필터. 왼쪽 위 카메라 소스 칩, 오른쪽 위 REC 타이머·컷 카운터, 3분할 그리드
3. 오른쪽 위 '카메라' 카드: 소스별 연결 상태, 저장 공간, 조명 프리셋 원형 버튼
4. 오른쪽 아래 '맥락' 카드: 나침반, 날씨, 일몰까지 남은 시간, 다음 스팟까지 거리
5. 하단 컨트롤 패널: 모드 토글(사진·영상·동시), 미니맵, 촬영 설정(시간·컷 수), 레이아웃 칩 L1~L4, 필터 칩, 원형 컨트롤 패드(가운데 '차~즈' 셔터, 좌우 레이아웃, 상하 필터)

portrait에서는 뷰파인더 위, 하단 패널 아래, 오른쪽 카드 2개는 상태 칩 한 줄로 접는다.

> 변경(11/12, 디자인 정비): 촬영 대기를 위 목업 배치(레일 아이콘·뷰파인더·하단 컨트롤 패널·원형 패드·카메라/맥락 카드)로 맞췄다. 아직 없는 기능(영상·동시 모드, L3·L4)은 칩을 흐리게 비활성으로 보인다. 나침반은 방향 API가 없어 위치 변화로 진행 방향을 추정하고, 미니맵은 지도 타일 없이 데모 경로·스팟·현재 위치만 그린다. SDK 연동 같은 개발용 정보는 설정 탭으로만 보낸다. 글자 크기 단계 추가: 제목 22 / 본문 18 / 라벨 15sp, 터치 최소 56dp.

**주행 중 화면** — 뷰파인더·미니맵·칩·카드를 숨기고 큰 '차~즈' 음성 버튼 + 상태 텍스트 두 줄("촬영 대기" / "다음 스팟 1.2km"). 애니메이션 없는 정적 화면. 공통 컴포넌트 `DrivingLockOverlay` 가 `DrivingGate` 를 구독해 처리. 주행 중 영상 재생 시작·재개 금지(`UX_RESTRICTIONS_NO_VIDEO`).

**차량 화면 목록**

| ID | 화면 | 정차 중 | 주행 중 |
|---|---|---|---|
| C1 | 촬영 대기 | 콘솔 레이아웃, 프레임·필터 라이브 미리보기 | 음성 버튼 + 상태 텍스트 |
| C2 | 촬영 중 | C1 + REC 타이머·컷 카운터 + 중지 버튼 | "촬영 중 · 4컷 중 2" + 중지 버튼 |
| C3 | 셀렉·편집 | 왼쪽 결과 미리보기, 오른쪽 레이아웃·필터·BGM·스탬프 패널, 아래 컷 필름스트립 | 잠금: "저장했어요. 정차 후 편집하거나 '차즈 스마트폰에서 편집할게'라고 말해 주세요." |
| C4 | 결과·전송 | 완성본 미리보기, '폰으로 보내기', 미연결 시 페어링 QR | 잠금(음성 SEND_PHONE은 동작) |
| C5 | 갤러리 | 세션 카드 그리드, AI 작업 상태 | 잠금 |
| C6 | 설정 | 기본 촬영 설정, 마이크 오디오, 스팟 가이드, 페어링, SDK 연동 상태 패널 | 잠금 |

> 변경(11/12, 여정 앨범): 도착 = **정차 중 승객 도어가 닫힘→열림**(Vehicle SDK Door `registerDoorPosition`, 에뮬레이터 동작 확인). 목적지 도착 판정(Navi)은 아직 없어 도어만 본다. 설정 '여정 앨범' 카드에서 자동 전송을 켜고 끈다(기본 켜짐). 자동 전송과 별개로 '지금 앨범 보내기'·음성 "폰으로 보내줘"는 언제든 된다. 앨범 = 오늘 저장한 결과물 중 아직 폰에 안 보낸 것(보낸 id 는 기기에 기록). 문을 여닫을 때 중복 전송을 막으려 2분 쿨다운. 판정은 `ArrivalAlbumPolicy`(단위 테스트).

**폰 (컴패니언 PWA)** — 같은 디자인 토큰.

| ID | 화면 | 내용 |
|---|---|---|
| P1 | 페어링 | QR 랜딩. "차량과 연결됐어요", 알림·카메라 권한 |
| P2 | 받은 세션 | 차에서 넘어온 세션·완성본 목록, 실시간 갱신 |
| P3 | 편집 | 컷 셀렉, 사진 레이아웃 L1~L3, AI 이미지 필터 |
| P4 | 저장·공유 | 기기 저장, 공유 링크, Web Share API |
| P5 | 실내 카메라 모드 | 대시보드 거치. `capture.shot` 받으면 촬영·업로드. 화면 꺼짐 방지 |

> 변경(11/12, 폰 음성 리모컨): P2 위에 '말로 조작하기' 추가. 폰 브라우저 음성 인식(Web Speech API, ko-KR)으로 글자로 바꿔 `POST /sync/voice {phoneDeviceId, text≤80자}` → 서버가 연결된 차량에만 SSE `voice.command` → 차량 앱이 차 마이크로 들은 것과 같은 `handleText`로 처리(버튼을 눌렀으니 호출어 생략). 차량 앱은 `/sync/events?deviceId=차량`을 상시 구독한다. 음성 인식을 지원하지 않는 브라우저는 빠른 명령 버튼(찍어줘·다시·그만·폰으로)만. 문장은 로그에 남기지 않는다. 차 마이크·Pleos STT 를 대신하는 게 아니라 동승자용 추가 입력이다(원격 시연·뒷좌석).

## 데이터 모델과 API

> 변경(11/9, T10 — 사용자 결정 "로컬 서버로 일단 진행"): 1차는 Supabase 대신 **로컬 서버(server/app/sync.py)** 가 같은 역할을 한다.
> SQLite(devices·pairings·results·shares), Edge Function 과 같은 이름의 엔드포인트(`/sync/pairing-create`, `/sync/pairing-claim`, `/sync/results`=deliver, `/sync/share`=share-link), Realtime 채널 대신 SSE(`/sync/events?deviceId=`), Storage signed URL 대신 HMAC 서명·만료 URL.
> 폰 PWA 는 Next.js 대신 서버가 `/app/` 으로 서빙하는 의존성 없는 정적 PWA(`companion-web/public`). 폰과 PC 가 같은 네트워크여야 한다. 차량 업로드는 아직 즉시 전송(WorkManager 큐·Room 은 이후).

백엔드는 Supabase(Postgres, Storage, Realtime, Edge Functions). 차량 앱은 로컬 Room DB에 먼저 저장하고 WorkManager 큐로 업로드. 네트워크가 끊겨도 촬영과 차량 편집은 동작.

```kotlin
data class CaptureConfig(val durationSec: Int = 12, val count: Int = 4) // 간격 = durationSec / count
enum class CameraRole { INTERIOR, FRONT, REAR, LEFT, RIGHT }
enum class SourceKind { VEHICLE_CAMERA, PHONE_CAMERA, WEBCAM, RECORDED_VIDEO, AI_GENERATED }
data class Frame(val role: CameraRole, val path: String, val source: SourceKind)
data class Session(
    val id: String, val startedAt: Instant, val config: CaptureConfig,
    val location: LatLng?, val spotName: String?, val tripId: String?, // tripId: NaviHelper 경로 시작~도착 또는 같은 날
    val context: CaptureContext, val bgmTrackId: String?,
    val status: SessionStatus // RAW, EDITED, DELIVERED
)
data class CaptureContext(
    val scene: SceneType,      // COAST, FOREST, MOUNTAIN, CITY, RURAL, UNKNOWN
    val timeBand: TimeBand,    // DAY, GOLDEN_HOUR, NIGHT
    val weather: Weather,      // CLEAR, CLOUDY, RAIN, SNOW
    val driveMode: String?,    // Vehicle SDK DrivingMode
    val odometerKm: Double?    // Vehicle SDK Odometer
)
data class Cut(val sessionId: String, val index: Int, val tMs: Long, val frames: List<Frame>)
data class Clip(val sessionId: String, val role: CameraRole, val source: SourceKind, val path: String, val durationMs: Long)
enum class Layout { WINDOW_SWAP, CUT_FRAME, ROUTE_OVERLAY, SURROUND_SEQUENCE } // L1~L4
data class Result(val id: String, val sessionIds: List<String>, val layout: Layout, val kind: ResultKind /* PHOTO, VIDEO */,
                  val path: String, val filters: List<String>, val sourceKinds: Set<SourceKind>, val createdOn: Device /* CAR, PHONE */)

interface LayoutRenderer {
    val layout: Layout
    suspend fun renderPreview(live: LiveFrames, opts: RenderOptions): Bitmap   // 저해상도, 정차 중 라이브
    suspend fun renderPhoto(cuts: List<Cut>, opts: RenderOptions): File
    suspend fun renderVideo(sessions: List<Session>, opts: RenderOptions): File? // 미지원이면 null
}
```

**서버 테이블**

| 테이블 | 주요 컬럼 |
|---|---|
| devices | id, kind(car·phone), created_at |
| pairings | id, car_device_id, phone_device_id, token, status, expires_at |
| sessions | Session + car_device_id |
| cuts | session_id, index, t_ms, frames(jsonb: role·path·source) |
| clips | session_id, role, source, path, duration_ms |
| results | session_ids, layout, kind, path, filters(jsonb), source_kinds, created_on |
| genai_jobs | session_id, result_id, kind(image_style·video_extend), preset, status(queued·running·done·failed), input_path, output_path, error |

- 인증: 기기마다 Supabase 익명 로그인. RLS로 자기 기기와 페어링된 기기의 행만.
- Storage: `raw/{sessionId}/`(컷·클립), `results/{sessionId}/`(완성본). 만료되는 signed URL로만 접근.
- 원본 보관: 업로드 30일 뒤 `raw/` 삭제(앱 내 고지).

**Edge Functions**

| 함수 | 호출자 | 하는 일 |
|---|---|---|
| pairing-create | 차량 | 페어링 토큰 발급(10분 만료) → QR URL |
| pairing-claim | 폰 | 토큰으로 폰을 차량에 연결 |
| handoff | 차량 | 세션 업로드 후 `session.handoff` 발행 |
| deliver | 차량·폰 | 완성본 등록 후 `result.delivered` 발행 |
| genai-submit | 차량·폰 | 비즈라우터 이미지·영상 생성 요청, genai_jobs 기록 |
| genai-poll | 스케줄러 | 작업 상태 갱신, 완료 시 저장 + `job.updated` |
| intent-parse | 차량 | 규칙 파서 실패 시 LLM 인텐트 분류(JSON) |
| share-link | 폰 | 공개 링크(7일 만료) |
| image-generate | 차량·폰 | 가상 실내 컷, L1 창문 교체, 파노라마 아웃페인팅, 서라운드 탑뷰 |
| stt-transcribe | 차량 | 비즈라우터 오디오 모델로 명령 음성 전사 |
| tts-synthesize | 차량 | 비즈라우터 오디오 모델로 안내 음성 합성, 문구 해시 캐시 |
| health | 차량 | CapabilityProbe용 서버·비즈라우터 연결 확인 |

Realtime 채널 `device:{deviceId}` 하나, 이벤트 `session.handoff`, `result.delivered`, `job.updated` + 폰 실내 카메라용 차량 → 폰 `capture.shot`(세션 ID, 컷 번호, 촬영 시각).

**비즈라우터 연동**
```kotlin
interface GenAiClient { // 앱에서는 Edge Function만 호출. API 키는 서버 환경 변수
    suspend fun submitImageStyle(inputPath: String, preset: ImagePreset): JobId
    suspend fun submitVideoExtend(inputPath: String, preset: VideoPreset): JobId
    suspend fun submitImageGenerate(kind: GenKind, refPaths: List<String>, prompt: String): JobId
    fun observeJob(id: JobId): Flow<JobStatus>
    suspend fun parseIntent(text: String): Intent
    suspend fun transcribe(audio: File, lang: String = "ko"): String
    suspend fun synthesize(text: String, voice: String): File // 결과는 캐시
}
enum class ImagePreset { FILM, ANIME, WATERCOLOR }
enum class VideoPreset { ABOVE_CLOUDS, UNDERSEA }
enum class GenKind { VIRTUAL_INTERIOR, WINDOW_SWAP, OUTPAINT_PANORAMA, SURROUND_TOPVIEW }
```
- 모델명·엔드포인트는 서버 환경 변수(`BIZROUTER_BASE_URL`, `BIZROUTER_API_KEY`, `BIZROUTER_IMAGE_MODEL`, `BIZROUTER_VIDEO_MODEL`, `BIZROUTER_TEXT_MODEL`).
- 프리셋별 프롬프트는 한 곳(`supabase/functions/_shared/prompts.ts`, T10 전까지 `server/app/prompts.py`). 모든 프롬프트에 "인물 얼굴과 정체성 유지, 사고·폭력·위험운전 묘사 금지".
- 비즈라우터 형식(확인 완료: OpenAI 호환, `/images/generations`, `/audio/speech`, `/audio/transcriptions`)은 한 파일에만 반영.

## 저장소 구조와 작업 지침

```
cha-z/
├─ CLAUDE.md
├─ README.md
├─ docs/SPEC.md
├─ car-app/                      # Android (Kotlin, Jetpack Compose)
│  └─ app/src/main/java/ai/chaz/
│     ├─ platform/               # Pleos·AAOS 어댑터
│     │  ├─ vehicle/             # VehicleStateProvider, AmbientLightController
│     │  ├─ navi/                # RouteSource, LocationSource (NaviHelper)
│     │  ├─ driving/             # DrivingGate (CarUxRestrictions)
│     │  └─ SdkAvailability.kt
│     ├─ capture/                # CaptureSessionEngine, CameraSource
│     ├─ voice/                  # WakeWordDetector, SpeechInput, SpeechOutput, IntentParser
│     ├─ spot/                   # SpotRepository, SpotGuide
│     ├─ ambience/               # ContextProvider, LightRuleEngine, BgmPlayer
│     ├─ media/                  # PersonSegmenter, LayoutRenderer(L1~L4), SurroundComposer, MediaComposer
│     ├─ sync/                   # SupabaseClient, UploadWorker, GenAiClient
│     ├─ data/                   # Room 엔티티·DAO
│     └─ ui/                     # C1~C6 화면, DrivingLockOverlay
├─ companion-web/                # Next.js PWA (P1~P4)
├─ supabase/
│  ├─ migrations/
│  └─ functions/
├─ server/                       # > 변경: 로컬 개발 서버 (T10 전 Edge Function 대체)
└─ assets/
   ├─ bgm/ (+ LICENSES.md)
   ├─ tts/                       # 고정 문구 오디오 클립
   ├─ frames/                    # 레이아웃 프레임 PNG
   └─ exterior-mock/             # 외부 카메라 시뮬레이션용 주행 영상
```

**빌드 환경**
- Kotlin, minSdk 28, 타깃 에뮬레이터 API 34 Pleos Connect system image
- `settings.gradle.kts`: `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, `google()`, `mavenCentral()`, `https://nexus-playground.pleos.ai/repository/maven-releases/`
- 의존성: `ai.pleos.playground:Vehicle` (> 변경: 2.3.2 사용), `NaviHelper:3.0.5`, `TextToSpeech:3.3.1`, `SpeechToText:3.3.1`, CameraX, Media3, MediaPipe Tasks Vision, Vosk Android, Room, WorkManager, Supabase Kotlin — 각 태스크에서 필요할 때 추가
- `CarUxRestrictionsManager` 를 위해 `android { useLibrary("android.car") }`
- 비밀값: `local.properties` 의 `SUPABASE_URL`, `SUPABASE_ANON_KEY` → BuildConfig. 커밋 금지.

**AndroidManifest**
- Pleos: 사용하는 도메인의 `pleos.car.permission.*`, NaviHelper `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, (> 변경: 스파이크로 확인) `TTS_SERVICE`, `STT_SERVICE`
- Android: `CAMERA`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `INTERNET`, `FOREGROUND_SERVICE(_LOCATION/_MICROPHONE)`, `POST_NOTIFICATIONS`
- 단일 Activity에 DO 선언(`<meta-data android:name="distractionOptimized" android:value="true"/>`). 주행 중 기능 제한은 `DrivingGate`.
- `screenOrientation` 지정 안 함, 반응형으로 landscape까지.

## 구현 태스크 (순서대로)

| # | 태스크 | 완료 기준 | 우선순위 |
|---|---|---|---|
| T01 | 프로젝트 생성, Pleos 저장소·Vehicle·NaviHelper 의존성 | 에뮬레이터에서 빈 화면 실행 | P0 |
| T02 | CapabilityProbe + SdkAvailability + SDK 연동 상태 패널(기본) | 기능별 선택 구현체와 사유가 패널에 표시 | P0 |
| T03 | DrivingGate | Virtual Console에서 기어를 바꾸면 주행/정차가 화면에 반영 | P0 |
| T04 | 스파이크: 카메라 목록·AUTOMOTIVE_LOCATION·동시 오픈, 마이크, TTS, 백그라운드(B1·B2) | 항목별 결과를 docs/spike.md 에 기록 | P0 |
| T05 | CameraSource: 차량 전방·후방 + 실내 후보 체인(웹캠) | 세 소스 미리보기가 C1에 표시 | P0 |
| T06 | CaptureSessionEngine + TTS 카운트다운 | 12초 4컷: 컷마다 실내·전방·후방 프레임 + 클립 저장 | P0 |
| T07 | WakeWordDetector + IntentParser + SpeechInput·SpeechOutput 체인 | "차즈"로 촬영 시작, "1분 동안 10번" 반영, 파서 테스트 통과 | P0 |
| T08 | PersonSegmenter + L1·L2 LayoutRenderer | 차창 합성·컷형 사진 생성, C3에서 저장 | P0 |
| T09 | 정차 중 라이브 미리보기(프레임 + LUT 필터) | L1·L2 미리보기를 보며 촬영, 주행 전환 시 즉시 중단 | P0 |
| T10 | Supabase 스키마·페어링·업로드 + PWA P1·P2·P4 | 폰으로 보내기 → 폰에서 저장 | P0 |
| T11 | PWA P5 실내 카메라 모드 + capture.shot | 차량 카운트다운에 맞춰 폰이 촬영·업로드 | P1 |
| T12 | SpotRepository + SpotGuide | 위치를 바꾸면 스팟 TTS 가이드 | P1 |
| T13 | ContextProvider + LightRuleEngine + AmbientLightController | 맥락별 프리셋 적용, 세션 후 복원 | P1 |
| T14 | L3·L4 렌더러 + SurroundComposer + BGM 영상 | 경로형 사진, 순차 배경 9:16 영상 | P1 |
| T15 | 핸드오프 + PWA P3 편집 | 주행 중 음성으로 폰에 넘겨 편집 | P1 |
| T16 | 비즈라우터 이미지: 가상 실내 컷·서라운드 탑뷰·아웃페인팅 | 실내·측면 카메라가 없어도 L1·L4 완성 | P1 |
| T17 | AI 필터(이미지 1종 + 영상 연장 1종) | 비동기 완료 알림 | P2 |

일정이 밀리면 P2 → P1 순서로 자른다. P0만 완성해도 핵심 루프는 시연할 수 있다.

## 1차 제출 일정·체크리스트

11월 14일(토) 제출, 15일(일) 버퍼.

| 일정 | 작업 |
|---|---|
| 11/9(월) | T01~T04 / 사무국에 카메라·Light Set 문의 |
| 11/10(화) | T05~T06 / CC0 BGM·차량 모델 PNG·창문 마스크 준비 |
| 11/11(수) | T07~T08 |
| 11/12(목) | T09~T10 |
| 11/13(금) | T11~T16 가능한 만큼 / 시연 영상 녹화 |
| 11/14(토) | release APK, README, 영상 편집, 제출 |

- [x] release 빌드(자체 키스토어 서명), 깨끗한 에뮬레이터에 처음부터 설치·실행 확인 (11/10, 앱 삭제 후 설치 → 권한 3개 허용 → 촬영)
- [x] 네트워크 없이도 촬영·합성 동작 확인 (11/10, Wi-Fi·데이터 끔: 촬영 4/4·L2·갤러리 저장, 폰 전송은 '서버에 연결할 수 없어요')
- [x] 외부 목 영상·오프라인 음성 모델 포함 여부, APK 용량 확인 (104MB — docs/spike.md)
- [ ] GitHub Releases 또는 apk/ 폴더에 업로드
- [ ] README: 앱 이름, 한 줄 소개, 실행 방법, 테스트 환경(Pleos Connect Emulator API 34, 웹캠), 필요한 설정, APK 위치
- [ ] README: Virtual Console 시연 절차(기어, 위치 변경)와 음성 명령 목록
- [ ] README: SDK 가용성 표와 어댑터 구조 요약
- [ ] 비밀값 커밋 점검, `local.properties.example` 제공
- [ ] 프라이빗 저장소면 **11/16(월)까지 Public 전환**, 1차 발표(11/23)까지 유지 (공식 안내 11/11 확인: collaborator 초대 불필요)
- [ ] APK 는 `apk/팀명_앱이름_release.apk`, 이메일에 저장소 주소 + 최종 commit hash (appdevchallenge@hyundai-partners.com, 제목 "[차량용 앱 개발 공모전] 팀명_대표자명")
- [ ] 숏폼(선택, 가산점 없음, 별도 상금): 30초~1분 세로, Instagram 릴스/YouTube Shorts, 해시태그 2~3개(#HMG차량용앱개발공모전 필수), 링크는 접수양식에 기재
- [ ] BGM·외부 영상·오픈소스 라이선스 표기

## 시연 영상 콘티 (약 2분)
1. 문제: 차 안에서는 사람과 풍경을 한 장에 담기 어렵다 (10초, 그래픽)
2. 출발: 차~즈를 내비 옆 window에 띄운 화면 (정차)
3. 주행 전환: Virtual Console에서 기어를 주행으로 → 미리보기가 사라지고 음성 모드
4. 스팟 가이드: 위치를 명소 근처로 → TTS 제안 + 앰비언트 라이트 변경
5. "차~즈" → "12초 동안 4컷 찍을게요" → 카운트다운과 촬영 (실내 인물 + 차량 전후방)
6. "차즈 30초 동안 6번 찍어줘" 설정 변경 (짧게)
7. "차즈 스마트폰에서 편집할게" → 폰에 세션 도착, 편집
8. 정차: 기어 P → 프레임·필터 미리보기로 한 번 더 촬영 → L1 차창 합성·L4 순차 배경 영상, AI 필터, 폰으로 보내기
9. 설정의 SDK 연동 상태 패널 + 확장 로드맵(차량 카메라, Gleo Interface, 스팟 맵) (15초)

구현하지 못한 기능은 화면 목업으로 대신하고 "콘셉" 자막. 숏폼은 4~5번 장면을 세로로 재편집, #HMG차량용앱개발공모전 포함 해시태그 2~3개.

## 차~즈 자체 SNS 기획 검토
결론: 범용 SNS(피드·팔로우·댓글)는 만들지 않는다. 대신 **'차~즈 스팟 맵'** — 공개에 동의한 풍경 중심 결과물을 지도 위 스팟에 모은다. 1차 제출에서는 기획서·영상의 비전으로만 넣고 구현은 공유 링크까지.

- 득: 리텐션, 바이럴, 스팟별 데이터 / 실: 콜드 스타트, 운영 부담, 개인정보(동승자·아이 얼굴, 집 근처 위치), UGC 심사·브랜드 리스크, 2인 팀 범위
- 스팟 맵: '스팟에 공유'를 고른 결과물만, 좋아요만(팔로우·댓글 없음). 인물 기본 블러, 위치는 스팟 단위만, 스팟 밖 결과물은 업로드 불가. 올라온 사진이 F2 가이드 예시가 되는 플라이휠. 지자체·관광공사 프레임, 휴게소·카페 제휴 지면. 외부 공유는 PWA 웹 공유 시트·공개 링크.
- 단계: 1차 = 공유 링크 + 웹 공유 시트, 스팟 맵 목업 1장 / 2차 PT = 스팟 맵 읽기 전용 PWA + 가이드 예시 연동 / 사업화 = 신고·검수, 지자체 프레임 스폰서십
- 지표: 결과물 공유율, 스팟 맵 기여율, 스팟 가이드 수락률(가이드 후 15초 안에 촬영한 비율)

## 리스크와 확인 필요 항목
가장 큰 불확실성은 에뮬레이터의 카메라·마이크. Day 1 스파이크에서 확인 (결과는 `docs/spike.md`).

**확인 필요** — 전·후방 카메라 AUTOMOTIVE_LOCATION·동시 오픈·웹캠 / RECORD_AUDIO 마이크 입력 / 백그라운드 TTS(B1)·호출어(B2) / Android TTS 한국어 / Light Set 범위·메서드·permission / Vehicle 도메인 permission / NaviHelper 좌표·데이터 구조 / Virtual Console 위치 변경 / Pleos DO 선언 방식 / 비즈라우터 형식·모델·비용·지연 / 사무국: 차량 카메라 API, 커밍순 SDK 공개 일정

| 리스크 | 영향 | 대응 |
|---|---|---|
| 실내·측면 카메라 없음 | 핵심 데모 불가 | 실내는 폰(P5) → 웹캠 → AI 가상 실내 컷, 측면은 전후방 합성 + 아웃페인팅. AI 결과물 표기 |
| 에뮬레이터 마이크 불가 | 호출어 데모 불가 | 음성 버튼 + 텍스트 입력 디버그 패널, 영상에는 음성 더빙 |
| Vosk 한국어 인식률 | 호출어 미인식·오인식 | 문법 제한 모드, 임계값 튜닝. 대안 sherpa-onnx 키워드 감지 |
| 바이브코딩 코드를 2차 PT에서 설명 못 함 | 신뢰도 하락 | 어댑터 구조 고정, 태스크별 커밋 메시지에 설계 의도 기록 |
| AI 생성 지연·비용 | 데모 끊김 | 비동기 처리, 시연용 결과물 미리 생성 |
| APK 용량 | 설치·업로드 지연 | 소형 모델, 목 영상 720p·30초 이내 |
| 주행 중 조명 변경이 주의 분산 | 안전 감점 | 세션 시작 1회, 3초 이상 페이드, 깜빡임 금지 |

## 출처
Pleos Connect 앱 개발 시작하기 · Vehicle SDK 소개 · NaviHelper SDK 소개 · NaviHelper Event Listeners · NaviHelper Declaring Permissions · 주행 규제 개발 가이드 · 주행 안전을 고려한 설계 · 지역별 주행 규제 정책 · 앱 구조 및 동작 모델 · 보안 및 개인정보 보호를 고려한 설계 · 오디오 포커스 개발 가이드 · AAOS Camera2 마이그레이션 · 통합 온라인 설명회 다시보기
