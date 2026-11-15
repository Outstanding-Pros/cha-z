package ai.chaz.state

import ai.chaz.data.CaptureConfig
import ai.chaz.data.CaptureRules
import ai.chaz.data.Session
import ai.chaz.data.TriggerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateMachineTest {
    private var n = 0
    private val m = AppStateMachine { t, c -> Session("s${++n}", 0, c, t) }
    private fun AppModel.on(vararg e: AppEvent) = e.fold(this) { acc, ev -> m.reduce(acc, ev) }
    private val trigger = AppEvent.Trigger(TriggerSource.WAKE_WORD, CaptureConfig())

    @Test fun `차즈 촬영이 끝나 앨범에 저장되면 대기로 (주행 여부 상관없이)`() {
        assertEquals(AppState.Standby, AppModel().on(trigger, AppEvent.ShotTaken(4), AppEvent.Archived).state)
        assertEquals(AppState.Standby, AppModel(restricted = true).on(trigger, AppEvent.ShotTaken(1), AppEvent.Archived).state)
        // 촬영 중이 아니면 무시
        assertEquals(AppState.Editing("x"), AppModel().on(AppEvent.Imported("x"), AppEvent.Archived).state)
    }

    @Test fun `정차 중 촬영이 끝나면 편집`() {
        val s = AppModel().on(trigger, AppEvent.ShotTaken(4), AppEvent.SessionFinished).state
        assertEquals(AppState.Editing("s1"), s)
    }

    @Test fun `주행 중 촬영이 끝나면 저장·편집 잠금, 정차하면 편집으로`() {
        val saved = AppModel(restricted = true).on(trigger, AppEvent.SessionFinished)
        assertEquals(AppState.Saved("s1"), saved.state)
        assertEquals(AppState.Editing("s1"), saved.on(AppEvent.DrivingChanged(false)).state)
    }

    @Test fun `폰 직접 셔터 사진은 정차 중 편집, 주행 중 저장, 촬영 중에는 무시`() {
        assertEquals(AppState.Editing("p1"), AppModel().on(AppEvent.Imported("p1")).state)
        assertEquals(AppState.Saved("p1"), AppModel(restricted = true).on(AppEvent.Imported("p1")).state)
        val capturing = AppModel().on(trigger)
        assertEquals(capturing, capturing.on(AppEvent.Imported("p1")))
        // 다른 결과를 편집하던 중이면 새 사진으로 바뀐다
        assertEquals(AppState.Editing("p2"), AppModel().on(trigger, AppEvent.SessionFinished, AppEvent.Imported("p2")).state)
    }

    @Test fun `편집 중 주행이 시작되면 Saved 로`() {
        val s = AppModel().on(trigger, AppEvent.SessionFinished, AppEvent.DrivingChanged(true)).state
        assertEquals(AppState.Saved("s1"), s)
    }

    @Test fun `촬영 중에는 주행 전환이 상태를 바꾸지 않고 중복 트리거도 무시`() {
        val cap = AppModel().on(trigger, AppEvent.ShotTaken(1))
        val after = cap.on(AppEvent.DrivingChanged(true), trigger)
        assertTrue(after.state is AppState.Capturing)
        assertEquals("s1", (after.state as AppState.Capturing).session.id)
        assertEquals(1, (after.state as AppState.Capturing).shotsTaken)
        assertTrue(after.restricted)
    }

    @Test fun `한 컷도 못 찍고 멈추면 대기, 찍었으면 결과를 남긴다`() {
        assertEquals(AppState.Standby, AppModel().on(trigger, AppEvent.Stop).state)
        assertEquals(AppState.Editing("s2"), AppModel().on(trigger, AppEvent.ShotTaken(2), AppEvent.Stop).state) // 두 번째 세션
    }

    @Test fun `핸드오프와 전송`() {
        val saved = AppModel(restricted = true).on(trigger, AppEvent.SessionFinished)
        assertEquals(AppState.Handoff("s1"), saved.on(AppEvent.HandoffRequested).state)
        assertEquals(AppState.Delivered("s1"), saved.on(AppEvent.SendToPhone).state)
        // 결과 화면에서 다시 찍기
        val again = saved.on(AppEvent.SendToPhone, trigger).state
        assertTrue(again is AppState.Capturing)
    }

    @Test fun `촬영 설정 제약`() {
        assertEquals(CaptureRules.Normalized(CaptureConfig(12, 4), false), CaptureRules.normalize(CaptureConfig(12, 4)))
        assertEquals(CaptureConfig(60, 10), CaptureRules.normalize(CaptureConfig(60, 10)).config) // "1분 동안 10번"
        assertEquals(CaptureConfig(120, 20), CaptureRules.normalize(CaptureConfig(300, 50)).config)
        assertEquals(CaptureConfig(4, 1), CaptureRules.normalize(CaptureConfig(1, 0)).config)
        // 간격 2초 미만 → 시간을 늘린다
        val r = CaptureRules.normalize(CaptureConfig(10, 20))
        assertEquals(CaptureConfig(40, 20), r.config)
        assertTrue(r.adjusted)
        assertFalse(CaptureRules.normalize(CaptureConfig(30, 6)).adjusted)
        assertEquals(2, CaptureRules.countdownWords(2_000))
        assertEquals(2, CaptureRules.countdownWords(3_000)) // "하나, 둘" (스타일 기획서)
        assertEquals(1, CaptureRules.countdownWords(1_000))
        assertEquals(listOf("12초", "1분", "1분 30초", "2분"), listOf(12, 60, 90, 120).map(CaptureRules::formatDuration))
    }
}
