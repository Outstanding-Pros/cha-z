package ai.chaz.ambience

import ai.chaz.platform.awaitCallback
import ai.chaz.platform.vehicle.PleosVehicleHub
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 앰비언트 라이트 어댑터. Set 전에 Get 으로 원래 상태를 저장하고(snapshot), 세션이 끝나면 복원한다.
 * shown = 화면에 비추는 현재 색(실제 조명을 못 쓰면 이게 '시뮬레이션' 조명, 쓰면 미러 표시).
 */
interface AmbientLightController {
    val name: String
    val simulated: Boolean
    val shown: StateFlow<LightPreset?>
    suspend fun bounds(): Pair<Int, Int>
    suspend fun snapshot(): LightLevel?
    suspend fun apply(plan: LightPlan)
    suspend fun restore(level: LightLevel?)
}

/** 최후 수단 + 테스트: 화면 테두리 색 시뮬레이션 ("앰비언트 라이트(시뮬레이션)" 표기). */
class SimulatedAmbientLight : AmbientLightController {
    override val name = "screen-border"
    override val simulated = true
    private val _shown = MutableStateFlow<LightPreset?>(null)
    override val shown: StateFlow<LightPreset?> = _shown.asStateFlow()
    val applied = mutableListOf<LightPlan>()
    override suspend fun bounds() = 0 to 100
    override suspend fun snapshot(): LightLevel? = null
    override suspend fun apply(plan: LightPlan) { applied += plan; _shown.value = plan.preset }
    override suspend fun restore(level: LightLevel?) { _shown.value = null }
}

/**
 * 1순위: Vehicle SDK Light — check(Capabilities) → Get → Set(페이드) → 복원 (CLAUDE.md 규칙 3·4).
 * 스파이크: checkAmbientLightBrightnessColorCapabilities 쓰기 가능, 좌석 영역 1개.
 */
class PleosAmbientLight(private val hub: PleosVehicleHub) : AmbientLightController {
    override val name = "pleos-vehicle-light"
    override val simulated = false
    private val _shown = MutableStateFlow<LightPreset?>(null)
    override val shown: StateFlow<LightPreset?> = _shown.asStateFlow()
    private val lock = Mutex()
    private var area: SeatArea? = null
    private var range: Pair<Int, Int>? = null
    private var switchBefore: LightSwitch? = null

    private val light get() = hub.vehicle.getLight()

    private suspend fun area(): SeatArea = area ?: awaitCallback<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
        light.checkAmbientLightBrightnessColorCapabilities({ c, a -> ok(c to a) }, fail)
    }.second.first().also { area = it }

    override suspend fun bounds(): Pair<Int, Int> = range ?: runCatching {
        val a = area()
        val m = awaitCallback<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ c, b -> ok(c to b) }, fail)
        }.second
        val b = m[a] ?: m.values.first()
        b.minValue to b.maxValue
    }.getOrDefault(0 to 10).also { range = it }

    override suspend fun snapshot(): LightLevel? = lock.withLock {
        runCatching {
            val a = area()
            val color = awaitCallback<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(a, ok, fail) } ?: return@runCatching null
            val b = awaitCallback<Int?> { ok, fail -> light.getAmbientLightBrightness(a, ok, fail) } ?: bounds().second
            switchBefore = runCatching { awaitCallback<LightSwitch?> { ok, fail -> light.getAmbientLightSwitch(a, ok, fail) } }.getOrNull()
            LightLevel(color, b)
        }.onFailure { Log.w(TAG, "조명 Get 실패: ${it.message}") }.getOrNull()
    }

    override suspend fun apply(plan: LightPlan) = lock.withLock {
        val a = area()
        val from = runCatching {
            LightLevel(
                awaitCallback<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(a, ok, fail) } ?: plan.color,
                awaitCallback<Int?> { ok, fail -> light.getAmbientLightBrightness(a, ok, fail) } ?: plan.brightness,
            )
        }.getOrDefault(LightLevel(plan.color, plan.brightness))
        if (switchBefore == LightSwitch.OFF) runCatching { set { ok, fail -> light.setAmbientLightSwitch(a, LightSwitch.ON, { _, _ -> ok(Unit) }, fail) } }
        _shown.value = plan.preset
        val steps = (plan.fadeMs / STEP_MS).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val l = LightRuleEngine.step(from, plan, i / steps.toFloat())
            runCatching {
                set { ok, fail -> light.setAmbientLightBrightnessColor(a, l.color, { _, _ -> ok(Unit) }, fail) }
                set { ok, fail -> light.setAmbientLightBrightness(a, l.brightness, { _, _ -> ok(Unit) }, fail) }
            }.onFailure { Log.w(TAG, "조명 Set 실패: ${it.message}") }
            delay(STEP_MS)
        }
    }

    override suspend fun restore(level: LightLevel?) = lock.withLock {
        _shown.value = null
        if (level == null) return@withLock
        val a = area()
        runCatching {
            set { ok, fail -> light.setAmbientLightBrightnessColor(a, level.color, { _, _ -> ok(Unit) }, fail) }
            set { ok, fail -> light.setAmbientLightBrightness(a, level.brightness, { _, _ -> ok(Unit) }, fail) }
            switchBefore?.let { sw -> set { ok, fail -> light.setAmbientLightSwitch(a, sw, { _, _ -> ok(Unit) }, fail) } }
        }.onFailure { Log.w(TAG, "조명 복원 실패: ${it.message}") }
    }

    private suspend fun set(call: (ok: (Unit) -> Unit, fail: (Exception) -> Unit) -> Unit) {
        withTimeoutOrNull(1_500) { awaitCallback(call) }
    }

    companion object {
        private const val TAG = "ChazLight"
        private const val STEP_MS = 250L
    }
}
