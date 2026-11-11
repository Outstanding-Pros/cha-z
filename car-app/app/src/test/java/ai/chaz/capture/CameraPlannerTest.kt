package ai.chaz.capture

import ai.chaz.platform.ProbeChains.Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPlannerTest {

    private val emulator = CameraReport(
        cameras = listOf(CameraInfo("0", CameraRole.INTERIOR, "추정(LENS_FACING=FRONT)", "FRONT", "1376x768")),
        concurrent = emptyList(),
    )
    private val car = CameraReport(
        cameras = listOf(
            CameraInfo("10", CameraRole.FRONT, "AUTOMOTIVE_LOCATION=2", "BACK", "1920x1080"),
            CameraInfo("11", CameraRole.REAR, "AUTOMOTIVE_LOCATION=3", "BACK", "1920x1080"),
            CameraInfo("12", CameraRole.INTERIOR, "AUTOMOTIVE_LOCATION=0", "FRONT", "1280x720"),
        ),
        concurrent = emptyList(),
    )

    @Test fun `에뮬레이터 - 웹캠 실내 + 목 이미지 전후방`() {
        val plans = CameraPlanner.plan(Ids.EXTERIOR_RECORDED, Ids.INTERIOR_WEBCAM, emulator, listOf("README.md", "rear_01.jpg", "front_01.jpg"))
        assertEquals(
            listOf(
                SourcePlan.Camera(CameraRole.INTERIOR, "0", SourceKind.WEBCAM),
                SourcePlan.Simulated(CameraRole.FRONT, "exterior-mock/front_01.jpg"),
                SourcePlan.Simulated(CameraRole.REAR, "exterior-mock/rear_01.jpg"),
            ),
            plans,
        )
    }

    @Test fun `후방 목 이미지가 없으면 전방 이미지를 함께 쓴다`() {
        val plans = CameraPlanner.plan(Ids.EXTERIOR_RECORDED, null, emulator, listOf("front_01.jpg"))
        assertEquals(listOf(CameraRole.FRONT, CameraRole.REAR), plans.map { it.role })
        assertTrue(plans.all { it is SourcePlan.Simulated && it.assetPath.endsWith("front_01.jpg") })
    }

    @Test fun `실차 - AUTOMOTIVE_LOCATION 카메라를 역할별로 쓴다`() {
        val plans = CameraPlanner.plan(Ids.EXTERIOR_VEHICLE, Ids.INTERIOR_VEHICLE, car, emptyList())
        assertEquals(
            listOf(
                SourcePlan.Camera(CameraRole.INTERIOR, "12", SourceKind.VEHICLE_CAMERA),
                SourcePlan.Camera(CameraRole.FRONT, "10", SourceKind.VEHICLE_CAMERA),
                SourcePlan.Camera(CameraRole.REAR, "11", SourceKind.VEHICLE_CAMERA),
            ),
            plans,
        )
    }

    @Test fun `폰 카메라 모드가 선택되면 실내는 폰(웹캠보다 우선)`() {
        val plans = CameraPlanner.plan(Ids.EXTERIOR_RECORDED, Ids.INTERIOR_PHONE, emulator, listOf("front_01.jpg", "rear_01.jpg"))
        assertEquals(SourcePlan.Phone(CameraRole.INTERIOR), plans.first())
        assertEquals(listOf(CameraRole.INTERIOR, CameraRole.FRONT, CameraRole.REAR), plans.map { it.role })
    }

    @Test fun `선택된 후보가 없으면 소스도 없다`() {
        assertTrue(CameraPlanner.plan(null, null, emulator, listOf("front_01.jpg")).isEmpty())
    }

    @Test fun `KenBurns 창은 항상 이미지 안에 있고 시간에 따라 좁아진다`() {
        val a = KenBurns.window(1280, 720, 0)
        val b = KenBurns.window(1280, 720, KenBurns.PERIOD_MS / 2)
        listOf(a, b).forEach {
            assertTrue(it.left >= 0 && it.top >= 0 && it.right <= 1280 && it.bottom <= 720)
        }
        assertEquals(1280, a.width)
        assertTrue(b.width < a.width)
        assertEquals(a, KenBurns.window(1280, 720, KenBurns.PERIOD_MS)) // 주기 반복
    }
}
