package ai.chaz.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProbeTest {

    private fun probeOf(vararg c: Candidate, timeoutMs: Long = 1_000): Pair<CapabilityProbe, SdkAvailability> {
        val a = SdkAvailability()
        return CapabilityProbe(a, mapOf(Feature.SPEECH_OUTPUT to c.toList()), timeoutMs, log = {}) to a
    }

    @Test fun `첫 번째로 사용 가능한 후보를 고르고 나머지는 건너뛴다`() = runTest {
        val (p, a) = probeOf(
            Candidate("pleos", "Pleos", Tier.PLEOS_SDK) { ProbeResult.Fail("init 실패") },
            Candidate("android", "Android", Tier.PLATFORM) { ProbeResult.Ok("ok") },
            Candidate("biz", "Biz", Tier.BIZROUTER) { error("호출되면 안 됨") },
        )
        p.probeAll()
        val s = a.selection(Feature.SPEECH_OUTPUT)!!
        assertEquals("android", s.selectedId)
        assertEquals(listOf(AttemptStatus.FAILED, AttemptStatus.OK, AttemptStatus.SKIPPED), s.attempts.map { it.status })
        assertEquals("init 실패", s.attempts[0].reason)
    }

    @Test fun `예외와 시간 초과는 사용 불가로 기록한다`() = runTest {
        val (p, a) = probeOf(
            Candidate("throws", "T", Tier.PLEOS_SDK) { throw NoClassDefFoundError("ai/pleos/X") },
            Candidate("slow", "S", Tier.PLATFORM) { delay(5_000); ProbeResult.Ok("늦음") },
            Candidate("last", "L", Tier.FALLBACK) { ProbeResult.Ok("ok") },
            timeoutMs = 100,
        )
        p.probeAll()
        val s = a.selection(Feature.SPEECH_OUTPUT)!!
        assertEquals("last", s.selectedId)
        assertTrue(s.attempts[0].reason.startsWith("NoClassDefFoundError"))
        assertTrue(s.attempts[1].reason.contains("초과"))
    }

    @Test fun `Pending 후보는 건너뛰고 선택하지 않는다`() = runTest {
        val (p, a) = probeOf(
            Candidate("vosk", "Vosk", Tier.FALLBACK) { ProbeResult.Pending("T07") },
        )
        p.probeAll()
        val s = a.selection(Feature.SPEECH_OUTPUT)!!
        assertNull(s.selected)
        assertEquals(AttemptStatus.PENDING, s.attempts.single().status)
    }

    @Test fun `3회 연속 실패하면 다음 후보로 내려간다`() = runTest {
        val (p, a) = probeOf(
            Candidate("pleos", "Pleos", Tier.PLEOS_SDK) { ProbeResult.Ok("ok") },
            Candidate("biz", "Biz", Tier.BIZROUTER) { ProbeResult.Ok("ok") },
        )
        p.probeAll()
        assertEquals("pleos", a.selectedId(Feature.SPEECH_OUTPUT))

        assertFalse(p.reportFailure(Feature.SPEECH_OUTPUT, "onError"))
        assertFalse(p.reportFailure(Feature.SPEECH_OUTPUT, "onError"))
        assertTrue(p.reportFailure(Feature.SPEECH_OUTPUT, "onError"))

        val s = a.selection(Feature.SPEECH_OUTPUT)!!
        assertEquals("biz", s.selectedId)
        assertTrue("pleos" in s.demoted)
        assertTrue(s.note!!.contains("3회"))
    }

    @Test fun `중간에 성공하면 실패 횟수를 초기화한다`() = runTest {
        val (p, a) = probeOf(
            Candidate("pleos", "Pleos", Tier.PLEOS_SDK) { ProbeResult.Ok("ok") },
            Candidate("biz", "Biz", Tier.BIZROUTER) { ProbeResult.Ok("ok") },
        )
        p.probeAll()
        p.reportFailure(Feature.SPEECH_OUTPUT, "x")
        p.reportFailure(Feature.SPEECH_OUTPUT, "x")
        p.reportSuccess(Feature.SPEECH_OUTPUT)
        assertFalse(p.reportFailure(Feature.SPEECH_OUTPUT, "x"))
        assertEquals("pleos", a.selectedId(Feature.SPEECH_OUTPUT))
    }
}
