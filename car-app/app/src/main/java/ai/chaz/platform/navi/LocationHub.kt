package ai.chaz.platform.navi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * 화면·기능이 보는 위치 하나. SdkAvailability 가 고른 소스(NaviHelper → LocationManager)를 쓰다가,
 * 데모 경로를 켜면 그걸 쓰고, 개발용 수동 위치(adb --es loc lat,lon)가 있으면 그게 우선한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationHub(scope: CoroutineScope, private val demo: DemoRouteSource) {
    private val primary = MutableStateFlow<LocationSource?>(null)
    private val demoOn = MutableStateFlow(false)
    private val manual = MutableStateFlow<GeoPoint?>(null)

    val sourceLabel: StateFlow<String> = combine(primary, demoOn, manual) { p, d, m ->
        when {
            m != null -> "수동 위치(개발)"
            d -> "데모 경로"
            p != null -> p.name
            else -> "없음"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "없음")

    private val active = combine(primary, demoOn) { p, d -> if (d) demo else p }

    val location: StateFlow<GeoPoint?> = combine(active.flatMapLatest { it?.location ?: flowOf(null) }, manual) { a, m -> m ?: a }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val nextTurnM: StateFlow<Double?> = active.flatMapLatest { it?.nextTurnM ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun attach(source: LocationSource) {
        if (primary.value === source) return
        primary.value?.stop()
        source.start()
        primary.value = source
    }

    fun detach() {
        primary.value?.stop()
        primary.value = null
    }

    fun startDemo() { demo.start(); demoOn.value = true }
    fun stopDemo() { demo.stop(); demoOn.value = false }
    val demoRunning get() = demoOn.value

    fun setManual(p: GeoPoint?) { manual.value = p }
}
