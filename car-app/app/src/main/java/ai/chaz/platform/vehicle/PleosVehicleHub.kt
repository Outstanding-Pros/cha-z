package ai.chaz.platform.vehicle

import ai.chaz.platform.ProbeResult
import ai.chaz.platform.awaitCallback
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import android.content.Context

/**
 * 프로세스당 Vehicle 인스턴스 하나. initialize() 는 처음 쓸 때 한 번,
 * release() 는 앱이 끝날 때만 (콜백 진행 중에 release 하지 않는다 — CLAUDE.md 규칙 4).
 */
class PleosVehicleHub(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var initialized = false

    val vehicle: Vehicle by lazy {
        Vehicle(appContext).also {
            it.initialize()
            initialized = true
        }
    }

    fun release() {
        if (initialized) vehicle.release()
    }

    companion object {
        private fun CapabilityConfig.writable() = accessType == AccessType.WRITE || accessType == AccessType.READ_WRITE

        /** VehicleStateProvider 1순위: 주행거리 + 드라이브 모드 조회 가능 여부. */
        suspend fun probeVehicleState(hub: PleosVehicleHub): ProbeResult {
            // 도메인마다 지원 여부가 달라서 따로 확인한다 (하나라도 읽히면 사용)
            suspend fun check(call: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit): String =
                try { awaitCallback(call).accessType.name } catch (e: Exception) { "미지원(${e.message})" }
            val odo = check { ok, fail -> hub.vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(ok, fail) }
            val mode = check { ok, fail -> hub.vehicle.getDrivingMode().checkDrivingModeStateCapabilities(ok, fail) }
            val readable = setOf(AccessType.READ.name, AccessType.READ_WRITE.name)
            val summary = "주행거리 $odo, 드라이브 모드 $mode"
            return if (odo in readable || mode in readable) ProbeResult.Ok(summary) else ProbeResult.Fail(summary)
        }

        /** AmbientLightController 1순위: 앰비언트 라이트 색 Set 가능 여부. */
        suspend fun probeAmbientLight(hub: PleosVehicleHub): ProbeResult {
            val (cfg, areas) = awaitCallback<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
                hub.vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ c, a -> ok(c to a) }, fail)
            }
            return when {
                !cfg.writable() -> ProbeResult.Fail("색 Set 불가 (${cfg.accessType})")
                areas.isEmpty() -> ProbeResult.Fail("지원 좌석 영역 없음")
                else -> ProbeResult.Ok("색 Set 가능, 영역 ${areas.size}개")
            }
        }
    }
}
