package ai.chaz.ambience

import ai.chaz.platform.navi.Geo
import ai.chaz.platform.navi.GeoPoint
import ai.chaz.platform.navi.LocationHub
import ai.chaz.spot.Spot
import ai.chaz.spot.SpotGuidePolicy
import ai.chaz.spot.SpotRepository
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 앰비언트 연출 (SPEC F3). '에피소드' 하나 = 스팟 가이드 또는 촬영 세션.
 * 시작할 때 원래 조명을 저장하고 맥락 프리셋을 한 번 페이드로 적용, 끝나면 복원한다.
 * 주행 중 조명 변경은 에피소드 시작 때 한 번뿐이다 (스팟 가이드 → 촬영으로 이어지면 같은 에피소드).
 */
class AmbienceDirector(
    private val light: () -> AmbientLightController,
    private val context: suspend () -> CaptureContext,
    private val driving: () -> Boolean,
    /**
     * 주행 중 자동 변경 허용 (설정). false 면 주행 중엔 그대로 두고 정차 중 에피소드(촬영)에서만 바꾼다
     * — 기획서 '주행 중 조명 제어 금지'. true 면 기존 SPEC F3(밝기는 지금 이하, 3초 이상 페이드).
     */
    private val autoWhileDriving: () -> Boolean = { true },
) {
    private val lock = Mutex()
    private var saved: LightLevel? = null
    private var active = false
    private val _preset = MutableStateFlow<LightPreset?>(null)
    val preset: StateFlow<LightPreset?> = _preset.asStateFlow()

    suspend fun begin(reason: String): LightPreset? = lock.withLock {
        if (active) return@withLock _preset.value
        if (driving() && !autoWhileDriving()) return@withLock null
        val ctx = context()
        val preset = LightRuleEngine.choose(ctx, parked = !driving()) ?: return@withLock null
        applyLocked(preset, reason)
    }

    /** 정차 중 수동(조명 화면·음성 "분위기 바꿔줘"). preset == null 이면 맥락으로 고른다. */
    suspend fun manual(preset: LightPreset?): LightPreset? = lock.withLock {
        if (driving()) return@withLock null
        val p = preset ?: LightRuleEngine.choose(context(), parked = true) ?: LightPreset.OCEAN
        applyLocked(p, "manual")
    }

    private suspend fun applyLocked(preset: LightPreset, reason: String): LightPreset? {
        val l = light()
        if (!active) saved = l.snapshot()
        val (minB, maxB) = l.bounds()
        val plan = LightRuleEngine.plan(preset, driving(), saved, minB, maxB) ?: return null
        active = true
        _preset.value = preset
        Log.i(TAG, "조명 $reason → ${preset.label} (${l.name}, 페이드 ${plan.fadeMs}ms)")
        l.apply(plan)
        return preset
    }

    suspend fun end() = lock.withLock {
        if (!active) return@withLock
        light().restore(saved)
        saved = null
        active = false
        _preset.value = null
        Log.i(TAG, "조명 복원")
    }

    companion object { private const val TAG = "ChazLight" }
}

/**
 * 선제 가이드 실행 (SPEC F2): 위치가 바뀔 때마다 스팟을 모으고 SpotGuidePolicy 로 판단해
 * TTS 로 제안한다. 앱이 화면에 있을 때만 돈다 (백그라운드 B1 미검증).
 */
class SpotGuideRunner(
    private val scope: CoroutineScope,
    private val hub: LocationHub,
    private val repo: SpotRepository,
    private val policy: SpotGuidePolicy,
    private val sunsetMs: suspend () -> Long?,
    private val onGuide: suspend (SpotGuidePolicy.Decision) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    data class Nearby(val spot: Spot, val distanceM: Double)

    private val _nearest = MutableStateFlow<Nearby?>(null)
    /** 다음(가장 가까운) 스팟 — 주행 화면 "다음 스팟 1.2km". */
    val nearest: StateFlow<Nearby?> = _nearest.asStateFlow()
    private val _spots = MutableStateFlow<List<Spot>>(emptyList())
    val spots: StateFlow<List<Spot>> = _spots.asStateFlow()

    private var state = SpotGuidePolicy.State()
    private var job: Job? = null
    @Volatile var lastGuided: Pair<Spot, Long>? = null
        private set

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var last: GeoPoint? = null
            while (isActive) {
                val here = hub.location.value
                if (here != null && here != last) {
                    last = here
                    val spots = repo.spotsNear(here)
                    _spots.value = spots
                    _nearest.value = spots.map { Nearby(it, Geo.distanceM(here, it.point)) }.minByOrNull { it.distanceM }
                    val (d, s) = policy.decide(state, here, spots, now(), hub.nextTurnM.value, sunsetMs())
                    state = s
                    if (d is SpotGuidePolicy.Decision.SpotGuide) lastGuided = d.spot to now()
                    if (d != SpotGuidePolicy.Decision.None) onGuide(d)
                }
                delay(1_000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** 새 여정(데모 다시 시작 등): 스팟당 1회·간격 기록을 지운다. */
    fun resetTrip() { state = SpotGuidePolicy.State() }

    /** 세션 스탬프용 장소: 3분 안에 가이드한 스팟, 아니면 1km 안 가장 가까운 스팟. */
    fun placeForSession(): String? {
        lastGuided?.let { (s, at) -> if (now() - at < 3 * 60_000) return s.name }
        return _nearest.value?.takeIf { it.distanceM <= 1_000 }?.spot?.name
    }
}
