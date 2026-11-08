@file:Suppress("DEPRECATION") // Gear API 는 2.3.2 에서 deprecated 지만 동작한다 (대체 = GEAR_SELECTION 속성)

package ai.chaz.platform.driving

import ai.chaz.platform.ProbeResult
import ai.chaz.platform.awaitCallback
import ai.chaz.platform.vehicle.PleosVehicleHub
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 2순위: Vehicle SDK Gear 도메인. P 가 아니면 주행 중으로 본다. */
class VehicleGearDrivingGate(private val hub: PleosVehicleHub) : DrivingGate {
    override val name = "pleos-vehicle-gear"
    private val _restricted = MutableStateFlow(false)
    private val _detail = MutableStateFlow("연결 전")
    override val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    override val detail: StateFlow<String> = _detail.asStateFlow()

    private var registered = false
    private val listener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = apply(gear)
        override fun onFailed(e: Exception) { _detail.value = "기어 구독 실패" }
    }

    override fun start() {
        if (registered) return
        val gear = hub.vehicle.getGear()
        gear.getCurrentGearSelectionState({ apply(it) }, { _detail.value = "기어 조회 실패" })
        gear.registerGearSelection(listener)
        registered = true
    }

    override fun stop() {
        if (!registered) return
        hub.vehicle.getGear().unregisterGearSelection(listener)
        registered = false
    }

    private fun apply(gear: VehicleGear?) {
        if (gear == null) return // 알 수 없는 값이면 직전 상태 유지
        _restricted.value = isDriving(gear)
        _detail.value = "기어 ${label(gear)}"
    }

    companion object {
        fun isDriving(gear: VehicleGear): Boolean = gear != VehicleGear.GEAR_PARK

        fun label(gear: VehicleGear): String = when (gear) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_DRIVE -> "D"
            VehicleGear.GEAR_UNKNOWN -> "?"
            else -> gear.name.removePrefix("GEAR_")
        }

        suspend fun probe(hub: PleosVehicleHub): ProbeResult {
            val gear = awaitCallback<VehicleGear?> { ok, fail ->
                hub.vehicle.getGear().getCurrentGearSelectionState(ok, fail)
            } ?: return ProbeResult.Fail("기어 값 없음")
            return ProbeResult.Ok("현재 기어 ${label(gear)}")
        }
    }
}
