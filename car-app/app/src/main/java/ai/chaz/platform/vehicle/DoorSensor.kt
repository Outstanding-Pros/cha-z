package ai.chaz.platform.vehicle

import ai.chaz.platform.ProbeResult
import ai.chaz.platform.awaitCallback
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 승객 도어 열림 감지 (여정 앨범 자동 전송 트리거). */
interface DoorSensor {
    val name: String
    /** 승객 도어 중 하나라도 열려 있으면 true. 모르면 null. */
    val anyOpen: StateFlow<Boolean?>
    suspend fun start()
    fun stop()
}

/** 감지 불가 (자동 전송 꺼짐) + 테스트·adb 시뮬레이션용. */
class FakeDoorSensor(override val name: String = "감지 안 함") : DoorSensor {
    private val _open = MutableStateFlow<Boolean?>(null)
    override val anyOpen: StateFlow<Boolean?> = _open.asStateFlow()
    fun set(open: Boolean?) { _open.value = open }
    override suspend fun start() {}
    override fun stop() {}
}

/**
 * Vehicle SDK Door: checkDoorPositionCapabilities → 승객 도어(ROW_*)마다 registerDoorPosition.
 * 위치 값이 최소값보다 크면 열림. 후드·트렁크는 제외 (짐 싣는 건 '도착'이 아님).
 */
class PleosDoorSensor(private val hub: PleosVehicleHub) : DoorSensor {
    override val name = "Vehicle SDK Door"
    private val _open = MutableStateFlow<Boolean?>(null)
    override val anyOpen: StateFlow<Boolean?> = _open.asStateFlow()
    private val state = java.util.concurrent.ConcurrentHashMap<DoorArea, Boolean>()
    private val listeners = mutableListOf<Pair<DoorArea, DoorPositionListener>>()

    override suspend fun start() {
        if (listeners.isNotEmpty()) return
        val bounds = passengerDoors(hub)
        bounds.forEach { (area, b) ->
            val min = b.minValue ?: 0
            fun update(pos: Int?) {
                if (pos == null) return
                state[area] = pos > min
                _open.value = state.values.any { it }
            }
            runCatching { update(awaitCallback<Int?> { ok, fail -> hub.vehicle.getDoor().getCurrentDoorPosition(area, ok, fail) }) }
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(position: Int?) = update(position)
                override fun onFailed(e: Exception) { android.util.Log.w("ChazDoor", "도어 콜백 실패: ${e.message}") }
            }
            hub.vehicle.getDoor().registerDoorPosition(area, l)
            listeners += area to l
        }
    }

    override fun stop() {
        listeners.forEach { (a, l) -> runCatching { hub.vehicle.getDoor().unregisterDoorPosition(a, l) } }
        listeners.clear()
    }

    companion object {
        private val PASSENGER = setOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT, DoorValue.ROW_3_LEFT, DoorValue.ROW_3_RIGHT)

        private suspend fun passengerDoors(hub: PleosVehicleHub): Map<DoorArea, AreaBounds<Int>> {
            val (cfg, map) = awaitCallback<Pair<CapabilityConfig, Map<DoorArea, AreaBounds<Int>>>> { ok, fail ->
                hub.vehicle.getDoor().checkDoorPositionCapabilities({ c, m -> ok(c to m) }, fail)
            }
            if (cfg.accessType != AccessType.READ && cfg.accessType != AccessType.READ_WRITE) error("도어 위치 읽기 불가 (${cfg.accessType})")
            return map.filterKeys { a -> a.values.any { it in PASSENGER } }
        }

        suspend fun probe(hub: PleosVehicleHub): ProbeResult = try {
            val doors = passengerDoors(hub)
            if (doors.isEmpty()) ProbeResult.Fail("승객 도어 영역 없음") else ProbeResult.Ok("도어 위치 읽기 가능, 승객 도어 ${doors.size}개")
        } catch (e: Exception) {
            ProbeResult.Fail(e.message ?: "도어 조회 실패")
        }
    }
}
