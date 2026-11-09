package ai.chaz.platform.navi

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

object Geo {
    /** 두 점 사이 거리(m), 하버사인. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** a→b 를 t(0~1) 만큼 간 점 (짧은 구간이라 선형 보간). */
    fun lerp(a: GeoPoint, b: GeoPoint, t: Double) = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}

/** 위치·경로 어댑터 (LocationSource / RouteSource). 좌표는 로그에 남기지 않는다 (CLAUDE.md 규칙 10). */
interface LocationSource {
    val name: String
    val location: StateFlow<GeoPoint?>
    /** 다음 회전 안내까지 거리(m). 모르면 null. */
    val nextTurnM: StateFlow<Double?>
    fun start()
    fun stop()
}

open class FakeLocationSource(override val name: String = "fake") : LocationSource {
    private val _loc = MutableStateFlow<GeoPoint?>(null)
    private val _turn = MutableStateFlow<Double?>(null)
    override val location: StateFlow<GeoPoint?> = _loc.asStateFlow()
    override val nextTurnM: StateFlow<Double?> = _turn.asStateFlow()
    fun set(p: GeoPoint?) { _loc.value = p }
    fun setTurn(m: Double?) { _turn.value = m }
    override fun start() = Unit
    override fun stop() = Unit
}

/**
 * 1순위: NaviHelper SDK. initialize → addListener → getCurrentLocationInfo(2초마다) → removeListener → release (규칙 5).
 * 경로 안내 중이면 onTBTInfo 로 다음 회전까지 거리를 받는다.
 */
class NaviHelperLocationSource(context: Context, private val scope: CoroutineScope) : LocationSource {
    override val name = "pleos-navihelper"
    private val appContext = context.applicationContext
    private val _loc = MutableStateFlow<GeoPoint?>(null)
    private val _turn = MutableStateFlow<Double?>(null)
    override val location: StateFlow<GeoPoint?> = _loc.asStateFlow()
    override val nextTurnM: StateFlow<Double?> = _turn.asStateFlow()
    private var helper: NaviHelper? = null
    private var poll: Job? = null

    /** 경로 계산이 끝나면 스팟을 다시 모은다 (SPEC F2). */
    var onRoutingCompleted: () -> Unit = {}

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            _loc.value = GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)
        }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            _turn.value = tbtInfoList.minOfOrNull { it.distance }
        }
        override fun onRoutingCompleted() = this@NaviHelperLocationSource.onRoutingCompleted()
        override fun onRouteEnded() { _turn.value = null }
    }

    override fun start() {
        if (helper != null) return
        val h = NaviHelper(appContext)
        h.initialize()
        h.addListener(listener)
        helper = h
        poll = scope.launch {
            while (isActive) {
                runCatching { h.getCurrentLocationInfo() }
                delay(2_000)
            }
        }
    }

    override fun stop() {
        poll?.cancel()
        helper?.let { runCatching { it.removeListener(listener) }; runCatching { it.release() } }
        helper = null
    }
}

/** 3순위: Android LocationManager (GPS). 에뮬레이터 Extended Controls·geo fix 위치를 받는다. */
class AndroidLocationSource(context: Context) : LocationSource {
    override val name = "android-location"
    private val lm = context.getSystemService(LocationManager::class.java)
    private val _loc = MutableStateFlow<GeoPoint?>(null)
    override val location: StateFlow<GeoPoint?> = _loc.asStateFlow()
    override val nextTurnM: StateFlow<Double?> = MutableStateFlow(null)
    private val listener = LocationListener { _loc.value = GeoPoint(it.latitude, it.longitude) }

    @SuppressLint("MissingPermission")
    override fun start() {
        runCatching {
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { _loc.value = GeoPoint(it.latitude, it.longitude) }
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 5f, listener, Looper.getMainLooper())
        }
    }

    override fun stop() { runCatching { lm?.removeUpdates(listener) } }
}

/**
 * 최후 수단: 데모 경로 재생 (SPEC 후보 체인). 저장된 경로를 일정 속도로 따라간다.
 * 시연 영상에서 '명소 근처로 가면 가이드'를 확실히 보여 주는 용도 — 화면에 '데모 경로'로 표기.
 */
class DemoRouteSource(
    private val route: List<GeoPoint>,
    private val scope: CoroutineScope,
    private val speedMps: Double = 25.0,
    private val tickMs: Long = 1_000,
) : LocationSource {
    override val name = "demo-route"
    private val _loc = MutableStateFlow<GeoPoint?>(null)
    override val location: StateFlow<GeoPoint?> = _loc.asStateFlow()
    override val nextTurnM: StateFlow<Double?> = MutableStateFlow(null)
    private var job: Job? = null
    val running get() = job?.isActive == true

    override fun start() {
        if (running || route.size < 2) return
        job = scope.launch {
            var seg = 0
            var along = 0.0
            _loc.value = route[0]
            while (isActive && seg < route.size - 1) {
                delay(tickMs)
                along += speedMps * tickMs / 1000.0
                var len = Geo.distanceM(route[seg], route[seg + 1])
                while (along >= len && seg < route.size - 1) {
                    along -= len
                    seg++
                    if (seg >= route.size - 1) break
                    len = Geo.distanceM(route[seg], route[seg + 1])
                }
                _loc.value = if (seg >= route.size - 1) route.last() else Geo.lerp(route[seg], route[seg + 1], along / len)
            }
        }
    }

    override fun stop() { job?.cancel(); job = null }
}
