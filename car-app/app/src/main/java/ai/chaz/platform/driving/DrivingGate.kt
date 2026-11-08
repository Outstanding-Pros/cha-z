package ai.chaz.platform.driving

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 주행 규제 상태. restricted = true 이면 주행 중으로 보고
 * 영상 재생·미리보기·텍스트 입력·QR·다단계 터치를 막는다.
 */
interface DrivingGate {
    /** 패널·로그 표시용 구현체 이름. */
    val name: String
    val restricted: StateFlow<Boolean>
    /** 사람이 읽는 현재 근거. 예: "기어 D", "UX 제한 0x1F". */
    val detail: StateFlow<String>
    fun start()
    fun stop()
}

/** 최후 수단 + 테스트용. 설정 화면 토글이나 테스트 코드가 값을 바꾼다. */
open class FakeDrivingGate(
    override val name: String = "fake",
    initial: Boolean = false,
) : DrivingGate {
    private val _restricted = MutableStateFlow(initial)
    private val _detail = MutableStateFlow(if (initial) "주행(수동)" else "정차(수동)")
    override val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    override val detail: StateFlow<String> = _detail.asStateFlow()

    fun set(restricted: Boolean) {
        _restricted.value = restricted
        _detail.value = if (restricted) "주행(수동)" else "정차(수동)"
    }

    override fun start() = Unit
    override fun stop() = Unit
}

/** 사양서 '개발용 디버그 토글' — 차량 신호를 하나도 못 얻을 때 쓴다. */
class DebugDrivingGate : FakeDrivingGate(name = "debug-toggle")
