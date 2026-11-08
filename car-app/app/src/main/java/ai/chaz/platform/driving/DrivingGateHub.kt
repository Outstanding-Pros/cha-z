package ai.chaz.platform.driving

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 화면·기능이 구독하는 단일 진입점. 선택된 DrivingGate 와 개발용 오버라이드를 합친다. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrivingGateHub(scope: CoroutineScope) {

    enum class Override(val label: String) { AUTO("자동"), FORCE_DRIVING("주행 고정"), FORCE_PARKED("정차 고정") }

    data class Status(val restricted: Boolean, val source: String, val detail: String, val override: Override)

    private val gate = MutableStateFlow<DrivingGate?>(null)
    private val _override = MutableStateFlow(Override.AUTO)
    val override: StateFlow<Override> = _override.asStateFlow()

    private val gateStatus = gate.flatMapLatest { g ->
        if (g == null) flowOf(Triple(false, "판정 중", "-"))
        else combine(g.restricted, g.detail) { r, d -> Triple(r, g.name, d) }
    }

    val status: StateFlow<Status> = combine(gateStatus, _override) { (r, src, d), o ->
        val restricted = when (o) {
            Override.AUTO -> r
            Override.FORCE_DRIVING -> true
            Override.FORCE_PARKED -> false
        }
        Status(restricted, src, d, o)
    }.stateIn(scope, SharingStarted.Eagerly, Status(false, "판정 중", "-", Override.AUTO))

    val restricted: StateFlow<Boolean> = status.map { it.restricted }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val current: DrivingGate? get() = gate.value

    fun attach(newGate: DrivingGate) {
        if (gate.value === newGate) return
        gate.value?.stop()
        newGate.start()
        gate.value = newGate
    }

    fun setOverride(o: Override) {
        _override.value = o
    }
}
