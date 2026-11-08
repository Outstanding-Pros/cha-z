package ai.chaz.platform.driving

import ai.chaz.platform.ProbeResult
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 1순위: AAOS CarUxRestrictions. requiresDistractionOptimization = 주행 중. */
class CarUxDrivingGate(private val context: Context) : DrivingGate {
    override val name = "car-ux-restrictions"
    private val _restricted = MutableStateFlow(false)
    private val _detail = MutableStateFlow("연결 전")
    override val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    override val detail: StateFlow<String> = _detail.asStateFlow()

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { apply(it) }

    override fun start() {
        if (car != null) return
        val c = Car.createCar(context) ?: run { _detail.value = "Car 연결 실패"; return }
        car = c
        val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        manager = m
        if (m == null) { _detail.value = "UX 제한 서비스 없음"; return }
        m.registerListener(listener)
        m.currentCarUxRestrictions?.let { apply(it) }
    }

    override fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }

    private fun apply(r: CarUxRestrictions) {
        _restricted.value = r.isRequiresDistractionOptimization
        _detail.value = if (r.isRequiresDistractionOptimization) {
            "UX 제한 0x" + Integer.toHexString(r.activeRestrictions)
        } else "UX 제한 없음"
    }

    companion object {
        /** 차량 기능 + Car 서비스 + UX 제한 매니저까지 얻어지면 사용 가능. */
        fun probe(context: Context): ProbeResult {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                return ProbeResult.Fail("자동차 기기가 아님")
            }
            val car = Car.createCar(context) ?: return ProbeResult.Fail("Car.createCar 실패")
            return try {
                val m = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                    ?: return ProbeResult.Fail("CAR_UX_RESTRICTION_SERVICE 없음")
                val r = m.currentCarUxRestrictions
                    ?: return ProbeResult.Fail("현재 UX 제한 조회 실패")
                ProbeResult.Ok("현재 DO 요구=${r.isRequiresDistractionOptimization}")
            } finally {
                car.disconnect()
            }
        }
    }
}
