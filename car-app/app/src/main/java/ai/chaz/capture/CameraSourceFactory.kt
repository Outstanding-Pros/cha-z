package ai.chaz.capture

import ai.chaz.platform.Feature
import ai.chaz.platform.ProbeChains
import ai.chaz.platform.SdkAvailability
import android.content.Context

/** 어떤 소스를 만들지 정한 결과. 순수 데이터라 단위 테스트한다. */
sealed interface SourcePlan {
    val role: CameraRole
    data class Camera(override val role: CameraRole, val cameraId: String, val kind: SourceKind) : SourcePlan
    data class Simulated(override val role: CameraRole, val assetPath: String) : SourcePlan
    /** 폰 컴패니언 카메라 (P5, 서버 중계). */
    data class Phone(override val role: CameraRole) : SourcePlan
}

/**
 * SdkAvailability 의 선택 결과로만 CameraSource 를 고른다 (기능 코드에 가용성 분기 금지).
 * 실내 1개 + 전방 1개 + 후방 1개를 목표로 하고, 없는 역할은 빠진다.
 */
object CameraPlanner {

    const val MOCK_DIR = "exterior-mock"

    fun plan(
        exteriorSelected: String?,
        interiorSelected: String?,
        report: CameraReport,
        mockAssets: List<String>,
    ): List<SourcePlan> {
        val plans = mutableListOf<SourcePlan>()

        when (interiorSelected) {
            ProbeChains.Ids.INTERIOR_VEHICLE -> report.automotive(CameraRole.INTERIOR).firstOrNull()
                ?.let { plans += SourcePlan.Camera(CameraRole.INTERIOR, it.id, SourceKind.VEHICLE_CAMERA) }
            ProbeChains.Ids.INTERIOR_WEBCAM -> report.cameras.firstOrNull { !it.isAutomotive }
                ?.let { plans += SourcePlan.Camera(CameraRole.INTERIOR, it.id, SourceKind.WEBCAM) }
            ProbeChains.Ids.INTERIOR_PHONE -> plans += SourcePlan.Phone(CameraRole.INTERIOR)
            // bizrouter.virtual-interior(T16) 은 아직 없음
        }

        when (exteriorSelected) {
            ProbeChains.Ids.EXTERIOR_VEHICLE -> listOf(CameraRole.FRONT, CameraRole.REAR).forEach { role ->
                report.automotive(role).firstOrNull()?.let { plans += SourcePlan.Camera(role, it.id, SourceKind.VEHICLE_CAMERA) }
            }
            ProbeChains.Ids.EXTERIOR_RECORDED -> {
                val images = mockAssets.filter { it.lowercase().matches(Regex("""(front|rear)_.*\.(jpg|jpeg|png)""")) }.sorted()
                val front = images.firstOrNull { it.startsWith("front") }
                val rear = images.firstOrNull { it.startsWith("rear") } ?: front // 후방 이미지가 없으면 전방으로 대신
                front?.let { plans += SourcePlan.Simulated(CameraRole.FRONT, "$MOCK_DIR/$it") }
                rear?.let { plans += SourcePlan.Simulated(CameraRole.REAR, "$MOCK_DIR/$it") }
            }
        }
        return plans
    }

    private val CameraInfo.isAutomotive get() = roleSource.startsWith("AUTOMOTIVE")
    private fun CameraReport.automotive(role: CameraRole) = cameras.filter { it.role == role && it.isAutomotive }
}

class CameraSourceFactory(
    private val context: Context,
    private val availability: SdkAvailability,
    private val serverUrl: String,
    private val carId: String,
) {

    fun create(): List<CameraSource> {
        val plans = CameraPlanner.plan(
            exteriorSelected = availability.selectedId(Feature.CAMERA_EXTERIOR),
            interiorSelected = availability.selectedId(Feature.CAMERA_INTERIOR),
            report = CameraInventory.scan(context),
            mockAssets = runCatching { context.assets.list(CameraPlanner.MOCK_DIR)?.toList() }.getOrNull().orEmpty(),
        )
        return plans.map { p ->
            when (p) {
                is SourcePlan.Camera -> CameraXSource(
                    context.applicationContext, p.cameraId, p.role, p.kind,
                    label = when (p.kind) {
                        SourceKind.WEBCAM -> "웹캠 #${p.cameraId}"
                        else -> "${roleLabel(p.role)} 카메라"
                    },
                )
                is SourcePlan.Simulated -> SimulatedExteriorSource(
                    context.applicationContext, p.assetPath, p.role, label = "${roleLabel(p.role)}(시뮬레이션)",
                )
                is SourcePlan.Phone -> PhoneCameraSource(serverUrl, carId)
            }
        }
    }

    companion object {
        fun roleLabel(role: CameraRole) = when (role) {
            CameraRole.INTERIOR -> "실내"
            CameraRole.FRONT -> "전방"
            CameraRole.REAR -> "후방"
            CameraRole.LEFT -> "좌측"
            CameraRole.RIGHT -> "우측"
        }
    }
}
