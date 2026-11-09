package ai.chaz

import ai.chaz.capture.CameraSource
import ai.chaz.capture.CameraSourceFactory
import ai.chaz.platform.CapabilityProbe
import ai.chaz.platform.Feature
import ai.chaz.platform.ProbeChains
import ai.chaz.platform.SdkAvailability
import ai.chaz.platform.driving.CarUxDrivingGate
import ai.chaz.platform.driving.DebugDrivingGate
import ai.chaz.platform.driving.DrivingGate
import ai.chaz.platform.driving.DrivingGateHub
import ai.chaz.platform.driving.VehicleGearDrivingGate
import ai.chaz.platform.vehicle.PleosVehicleHub
import ai.chaz.sync.ChazServer
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 수동 DI. 어댑터 구현체는 SdkAvailability 의 선택 결과로만 만든다. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val server = ChazServer(BuildConfig.CHAZ_SERVER_URL)
    val vehicleHub = PleosVehicleHub(appContext)
    val availability = SdkAvailability()
    val probe = CapabilityProbe(availability, ProbeChains.build(appContext, vehicleHub, server))
    val driving = DrivingGateHub(scope)
    val debugGate = DebugDrivingGate()

    private val cameraFactory = CameraSourceFactory(appContext, availability)
    private val _cameras = MutableStateFlow<List<CameraSource>>(emptyList())
    /** 현재 선택된 카메라 소스 (실내·전방·후방). 판정이 끝날 때마다 다시 만든다. */
    val cameras: StateFlow<List<CameraSource>> = _cameras.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    fun start() {
        reprobe()
        scope.launch {
            driving.status.collect { android.util.Log.i("ChazDriving", "restricted=${it.restricted} source=${it.source} detail=${it.detail} override=${it.override}") }
        }
    }

    fun reprobe() {
        if (_probing.value) return
        _probing.value = true
        scope.launch {
            try {
                // 주행 규제는 화면 잠금에 바로 쓰이므로 먼저 판정해 붙인다
                withContext(Dispatchers.Default) { probe.probe(Feature.DRIVING_GATE) }
                attachDrivingGate()
                withContext(Dispatchers.Default) { probe.probeAll() }
                attachDrivingGate()
                rebuildCameras()
            } finally {
                _probing.value = false
            }
        }
    }

    private fun rebuildCameras() {
        val old = _cameras.value
        val fresh = runCatching { cameraFactory.create() }.getOrElse {
            android.util.Log.w("ChazCamera", "카메라 소스 생성 실패: ${it.message}"); emptyList()
        }
        // 같은 구성이면 그대로 둔다 (미리보기가 깜빡이지 않게)
        if (old.map { it.role to it.label } == fresh.map { it.role to it.label }) return
        old.forEach { it.stop() }
        _cameras.value = fresh
        android.util.Log.i("ChazCamera", "소스: " + fresh.joinToString { "${it.role}=${it.label}" })
    }

    private fun attachDrivingGate() {
        val gate: DrivingGate = when (availability.selectedId(Feature.DRIVING_GATE)) {
            ProbeChains.Ids.DRIVING_CAR_UX -> (driving.current as? CarUxDrivingGate) ?: CarUxDrivingGate(appContext)
            ProbeChains.Ids.DRIVING_PLEOS_GEAR -> (driving.current as? VehicleGearDrivingGate) ?: VehicleGearDrivingGate(vehicleHub)
            else -> debugGate
        }
        driving.attach(gate)
    }
}
