package ai.chaz.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/** 카메라 위치 역할. 도메인 모델 CameraRole 과 같은 값. */
enum class CameraRole { INTERIOR, FRONT, REAR, LEFT, RIGHT }

/** CameraManager 로 본 카메라 한 대. role 판정 근거(roleSource)를 같이 남긴다. */
data class CameraInfo(
    val id: String,
    val role: CameraRole?,
    val roleSource: String,
    val lensFacing: String,
    val maxSize: String,
)

data class CameraReport(val cameras: List<CameraInfo>, val concurrent: List<Set<String>>) {
    fun byRole(role: CameraRole) = cameras.filter { it.role == role }
}

/**
 * 카메라 찾기 (사양서 F1 카메라 소스):
 * AUTOMOTIVE_LOCATION(API 33+)으로 전방·후방·실내·측면을 구분하고,
 * 키가 없으면 LENS_FACING 으로 추정한다.
 */
object CameraInventory {

    fun scan(context: Context): CameraReport {
        val cm = context.getSystemService(CameraManager::class.java) ?: return CameraReport(emptyList(), emptyList())
        val cameras = cm.cameraIdList.map { id -> describe(id, cm.getCameraCharacteristics(id)) }
        val concurrent = runCatching { cm.concurrentCameraIds.toList() }.getOrDefault(emptyList())
        return CameraReport(cameras, concurrent)
    }

    private fun describe(id: String, ch: CameraCharacteristics): CameraInfo {
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> "FRONT"
            CameraMetadata.LENS_FACING_BACK -> "BACK"
            CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
        val (role, source) = roleOf(ch, facing)
        return CameraInfo(id, role, source, facing, size?.toString() ?: "-")
    }

    private fun roleOf(ch: CameraCharacteristics, facing: String): Pair<CameraRole?, String> {
        if (Build.VERSION.SDK_INT >= 33) {
            val loc = ch.get(CameraCharacteristics.AUTOMOTIVE_LOCATION)
            if (loc != null) {
                val role = when (loc) {
                    CameraMetadata.AUTOMOTIVE_LOCATION_INTERIOR -> CameraRole.INTERIOR
                    CameraMetadata.AUTOMOTIVE_LOCATION_EXTERIOR_FRONT -> CameraRole.FRONT
                    CameraMetadata.AUTOMOTIVE_LOCATION_EXTERIOR_REAR -> CameraRole.REAR
                    CameraMetadata.AUTOMOTIVE_LOCATION_EXTERIOR_LEFT -> CameraRole.LEFT
                    CameraMetadata.AUTOMOTIVE_LOCATION_EXTERIOR_RIGHT -> CameraRole.RIGHT
                    else -> null
                }
                return role to "AUTOMOTIVE_LOCATION=$loc"
            }
        }
        // 키 없음 → LENS_FACING 추정: 전면 렌즈(사람을 향함)는 실내, 후면 렌즈는 전방 외부로 본다
        return when (facing) {
            "FRONT", "EXTERNAL" -> CameraRole.INTERIOR to "추정(LENS_FACING=$facing)"
            "BACK" -> CameraRole.FRONT to "추정(LENS_FACING=$facing)"
            else -> null to "판정 불가"
        }
    }
}
