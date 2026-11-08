package ai.chaz.platform.driving

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrivingGateHubTest {

    @Test fun `선택된 게이트의 주행 상태를 따른다`() = runTest(UnconfinedTestDispatcher()) {
        val hub = DrivingGateHub(backgroundScope)
        val gate = FakeDrivingGate()
        hub.attach(gate)
        assertFalse(hub.restricted.value)
        gate.set(true)
        assertTrue(hub.restricted.value)
        assertEquals("fake", hub.status.value.source)
        gate.set(false)
        assertFalse(hub.restricted.value)
    }

    @Test fun `오버라이드가 게이트 값보다 우선한다`() = runTest(UnconfinedTestDispatcher()) {
        val hub = DrivingGateHub(backgroundScope)
        val gate = FakeDrivingGate(initial = false)
        hub.attach(gate)
        hub.setOverride(DrivingGateHub.Override.FORCE_DRIVING)
        assertTrue(hub.restricted.value)
        gate.set(true)
        hub.setOverride(DrivingGateHub.Override.FORCE_PARKED)
        assertFalse(hub.restricted.value)
        hub.setOverride(DrivingGateHub.Override.AUTO)
        assertTrue(hub.restricted.value)
    }

    @Test fun `게이트를 바꾸면 이전 게이트는 멈추고 새 게이트를 따른다`() = runTest(UnconfinedTestDispatcher()) {
        val hub = DrivingGateHub(backgroundScope)
        var stopped = false
        val first = object : FakeDrivingGate("first", initial = true) { override fun stop() { stopped = true } }
        val second = FakeDrivingGate("second", initial = false)
        hub.attach(first)
        assertTrue(hub.restricted.value)
        hub.attach(second)
        assertTrue(stopped)
        assertFalse(hub.restricted.value)
        assertEquals("second", hub.status.value.source)
    }
}
