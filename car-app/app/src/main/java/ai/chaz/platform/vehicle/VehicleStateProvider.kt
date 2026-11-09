package ai.chaz.platform.vehicle

import ai.chaz.platform.awaitCallback
import kotlinx.coroutines.withTimeoutOrNull

/** 차량 상태 (스탬프용 주행거리·드라이브 모드). 못 얻으면 null → 스탬프에서 뺀다. */
interface VehicleStateProvider {
    suspend fun odometerKm(): Double?
    suspend fun driveMode(): String?
}

/** 표시 생략 (최후 수단) + 테스트용. */
class FakeVehicleStateProvider(private val odo: Double? = null, private val mode: String? = null) : VehicleStateProvider {
    override suspend fun odometerKm() = odo
    override suspend fun driveMode() = mode
}

/** Vehicle SDK Odometer / DrivingMode. 스파이크: 주행거리 READ 가능, 드라이브 모드는 에뮬레이터 미지원. */
class PleosVehicleStateProvider(private val hub: PleosVehicleHub) : VehicleStateProvider {
    override suspend fun odometerKm(): Double? = withTimeoutOrNull(2_000) {
        runCatching {
            awaitCallback<Float?> { ok, fail -> hub.vehicle.getOdometer().getCurrentDistanceDriven(ok, fail) }?.toDouble()
        }.getOrNull()
    }

    override suspend fun driveMode(): String? = withTimeoutOrNull(2_000) {
        runCatching {
            awaitCallback<ai.pleos.playground.vehicle.constant.control.DrivingModeType?> { ok, fail ->
                hub.vehicle.getDrivingMode().getCurrentDrivingModeState(ok, fail)
            }?.name
        }.getOrNull()
    }
}
