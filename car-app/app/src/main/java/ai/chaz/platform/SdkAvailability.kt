package ai.chaz.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 사양서 'SDK 가용성과 대체 전략 > 기능별 후보 체인'의 기능 목록. */
enum class Feature(val label: String, val adapter: String) {
    DRIVING_GATE("주행 규제", "DrivingGate"),
    VEHICLE_STATE("차량 상태", "VehicleStateProvider"),
    DOOR("도어 (도착 감지)", "DoorSensor"),
    AMBIENT_LIGHT("앰비언트 라이트", "AmbientLightController"),
    LOCATION("위치·경로", "LocationSource"),
    WAKE_WORD("호출어", "WakeWordDetector"),
    SPEECH_INPUT("명령 인식", "SpeechInput"),
    SPEECH_OUTPUT("음성 안내", "SpeechOutput"),
    CAMERA_EXTERIOR("전·후방 외부 카메라", "CameraSource"),
    CAMERA_INTERIOR("실내 카메라", "CameraSource"),
    SURROUND("서라운드·파노라마", "SurroundComposer"),
    PERSON_SEGMENTER("인물 분리", "PersonSegmenter"),
    SPOTS("사진 스팟", "SpotRepository"),
    WEATHER("날씨·일몰", "ContextProvider"),
    MEDIA_COMPOSER("영상 합성", "MediaComposer"),
    GENAI("AI 필터·생성", "GenAiClient"),
    BGM("BGM", "BgmPlayer"),
}

/** 후보 체인의 단계. 시도 순서 = Pleos SDK → 플랫폼 API → 비즈라우터 → 최후 수단. */
enum class Tier(val label: String) {
    PLEOS_SDK("Pleos SDK"),
    PLATFORM("플랫폼 API"),
    BIZROUTER("비즈라우터"),
    FALLBACK("대체"),
}

enum class AttemptStatus { OK, FAILED, PENDING, SKIPPED }

/** 후보 하나를 시도한 결과. reason 은 패널에 그대로 보인다. */
data class Attempt(
    val candidateId: String,
    val label: String,
    val tier: Tier,
    val status: AttemptStatus,
    val reason: String,
)

/** 기능 하나에 대해 선택된 구현체와 그 근거. */
data class Selection(
    val feature: Feature,
    val selected: Attempt?,
    val attempts: List<Attempt>,
    /** 런타임 3회 연속 실패로 내려간 후보 id. 재판정 때 건너뛴다. */
    val demoted: Set<String> = emptySet(),
    val note: String? = null,
) {
    val selectedId: String? get() = selected?.candidateId
}

/**
 * 기능 → 선택된 구현체 + 사유. 구현체 선택은 여기서만 읽는다
 * (CLAUDE.md 규칙: 기능 코드에 SDK 가용 여부 분기를 쓰지 않는다).
 */
class SdkAvailability {
    private val _state = MutableStateFlow<Map<Feature, Selection>>(emptyMap())
    val state: StateFlow<Map<Feature, Selection>> = _state.asStateFlow()

    fun record(selection: Selection) {
        _state.update { it + (selection.feature to selection) }
    }

    fun selection(feature: Feature): Selection? = _state.value[feature]

    fun selectedId(feature: Feature): String? = selection(feature)?.selectedId
}
