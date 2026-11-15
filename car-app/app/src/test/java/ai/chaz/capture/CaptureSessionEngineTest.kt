package ai.chaz.capture

import ai.chaz.data.CaptureConfig
import ai.chaz.data.Clip
import ai.chaz.data.Cut
import ai.chaz.data.Frame
import ai.chaz.data.Session
import ai.chaz.data.TriggerSource
import ai.chaz.voice.FakeCuePlayer
import ai.chaz.voice.FakeSpeechOutput
import ai.chaz.voice.PlainTtsPhrases
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionEngineTest {

    /** 비트맵 없이 호출만 기록하는 저장소. */
    private class RecordingStore(private val now: () -> Long) : SessionStore {
        val cutTimes = mutableListOf<Long>()
        val clipFrames = mutableMapOf<CameraRole, Int>()
        var finished: Triple<List<Cut>, List<Clip>, Boolean>? = null
        override fun begin(session: Session) = Unit
        override suspend fun saveCut(session: Session, index: Int, tMs: Long, sources: List<CameraSource>): Cut {
            cutTimes += tMs
            return Cut(session.id, index, tMs, sources.map { Frame(it.role, "cut$index-${it.role}", it.kind) })
        }
        override suspend fun saveClipFrame(session: Session, source: CameraSource, seq: Int): Boolean {
            clipFrames[source.role] = (clipFrames[source.role] ?: 0) + 1
            return true
        }
        override fun clipPath(session: Session, role: CameraRole) = "clips/$role"
        override suspend fun finish(session: Session, cuts: List<Cut>, clips: List<Clip>, stopped: Boolean) {
            finished = Triple(cuts, clips, stopped)
        }
    }

    private val sources = listOf(
        FakeCameraSource(CameraRole.INTERIOR),
        FakeCameraSource(CameraRole.FRONT, SourceKind.RECORDED_VIDEO),
        FakeCameraSource(CameraRole.REAR, SourceKind.RECORDED_VIDEO),
    )

    private fun TestScope.setup(introMs: Long = 2_000): Triple<CaptureSessionEngine, RecordingStore, Pair<FakeSpeechOutput, FakeCuePlayer>> {
        val now = { testScheduler.currentTime }
        val store = RecordingStore(now)
        val cueLog = mutableListOf<String>()
        val cues = object : ai.chaz.voice.CuePlayer {
            override fun countdown(n: Int) { cueLog += "${now()}:cd$n" }
            override fun shutter() { cueLog += "${now()}:shutter" }
        }
        val speech = object : ai.chaz.voice.SpeechOutput {
            override val name = "fake"
            val spoken = mutableListOf<String>()
            override suspend fun speak(text: String) { spoken += text; delay(introMs) }
            override fun stop() = Unit
        }
        val engine = CaptureSessionEngine(speech, cues, store, PlainTtsPhrases, now)
        cuesLog = cueLog
        speechLog = speech.spoken
        return Triple(engine, store, FakeSpeechOutput() to FakeCuePlayer())
    }

    private var cuesLog: List<String> = emptyList()
    private var speechLog: List<String> = emptyList()

    private fun session(config: CaptureConfig = CaptureConfig()) = Session("s1", 0, config, TriggerSource.SHUTTER_BUTTON)

    @Test fun `원격 소스에는 컷마다 셔터 시각을 2초 이상 미리 알린다 (폰 예약 촬영)`() = runTest {
        val (engine, store) = setup(introMs = 2_000)
        val scheduled = mutableListOf<Triple<Int, Long, Long>>() // index, 알린 시각, 예정 셔터 시각
        val phone = object : CameraSource by FakeCameraSource(CameraRole.INTERIOR, SourceKind.PHONE_CAMERA) {
            override fun scheduleShot(index: Int, inMs: Long) {
                val t = testScheduler.currentTime
                scheduled += Triple(index, t, t + inMs)
            }
        }
        engine.run(session(), listOf(phone) + sources.drop(1), adjusted = false, isDriving = { false })

        assertEquals(listOf(1, 2, 3, 4), scheduled.map { it.first })
        // 예정 셔터 시각 = 실제 컷 시각 (t0 = 안내 2초 뒤)
        assertEquals(store.cutTimes.map { it + 2_000L }, scheduled.map { it.third })
        assertTrue("리드 타임 ${scheduled.map { it.third - it.second }}", scheduled.all { it.third - it.second >= 2_000 })
    }

    @Test fun `12초 4컷 - 3초 간격으로 컷마다 세 소스, 하나(2초 전) 둘(1초 전) 후 셔터`() = runTest {
        val (engine, store) = setup(introMs = 2_000)
        val result = engine.run(session(), sources, adjusted = false, isDriving = { false })

        assertFalse(result.stopped)
        assertEquals(4, result.cuts.size)
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 12_000L), store.cutTimes)
        assertTrue(result.cuts.all { c -> c.frames.map { it.role } == listOf(CameraRole.INTERIOR, CameraRole.FRONT, CameraRole.REAR) })

        // 시작 안내(2초) 뒤 t0 = 2000. 첫 컷 5000 의 2초 전 "하나", 1초 전 "둘" (스타일 기획서)
        assertEquals(listOf("3000:cd1", "4000:cd2", "5000:shutter"), cuesLog.take(3))
        assertEquals(12, cuesLog.size)

        // 클립: 0.5초 간격, 12.5초 → 소스당 약 25장
        sources.forEach { assertTrue("${it.role} ${store.clipFrames[it.role]}", (store.clipFrames[it.role] ?: 0) in 24..27) }
        assertEquals(listOf("start 12s 4", "done-parked"), speechLog)
        assertEquals(3, store.finished!!.second.size)
    }

    @Test fun `단발 - 세 소스 한 장씩 1컷, 영상 없음`() = runTest {
        val (engine, store) = setup(introMs = 2_000)
        val result = engine.run(session(ai.chaz.data.CaptureMode.SINGLE.config), sources, adjusted = false, isDriving = { false })

        assertEquals(1, result.cuts.size)
        assertEquals(3, result.cuts[0].frames.size)
        assertEquals(listOf(4_000L), store.cutTimes)
        assertTrue(store.clipFrames.isEmpty())
        assertTrue(result.clips.isEmpty())
        assertEquals(listOf("4000:cd1", "5000:cd2", "6000:shutter"), cuesLog)
        assertEquals("한 장 찍을게요", speechLog.first())
    }

    @Test fun `동영상 - 12초 영상과 3초마다 4컷`() = runTest {
        val (engine, store) = setup(introMs = 2_000)
        val result = engine.run(session(ai.chaz.data.CaptureMode.VIDEO.config), sources, adjusted = false, isDriving = { false })
        assertEquals(4, result.cuts.size)
        assertEquals(3, result.clips.size)
        assertTrue(result.clips.all { it.frameCount in 24..27 })
    }

    @Test fun `주행 중에 시작하면 포즈 유도 없는 안내`() = runTest {
        val (engine) = setup()
        engine.run(session(), sources, adjusted = false, isDriving = { true })
        assertEquals("start-driving 12s 4", speechLog.first())
    }

    @Test fun `주행 중에 끝나면 주행용 종료 안내`() = runTest {
        val (engine) = setup()
        engine.run(session(), sources, adjusted = false, isDriving = { true })
        assertEquals("done-driving", speechLog.last())
    }

    @Test fun `간격 2초도 하나 둘, 그보다 짧으면 하나만`() = runTest {
        val (engine) = setup(introMs = 0)
        engine.run(session(CaptureConfig(durationSec = 8, count = 4)), sources, adjusted = false, isDriving = { false })
        assertEquals(listOf("0:cd1", "1000:cd2", "2000:shutter"), cuesLog.take(3))
        assertFalse(cuesLog.any { it.endsWith("cd3") })
        assertEquals(1, ai.chaz.data.CaptureRules.countdownWords(1_500))
    }

    @Test fun `중지하면 찍은 컷까지 저장하고 멈춤 안내`() = runTest {
        val (engine, store) = setup(introMs = 0)
        var result: CaptureSessionEngine.Result? = null
        val job = launch { result = engine.run(session(), sources, adjusted = false, isDriving = { false }) }
        delay(7_000)
        engine.stop()
        job.join()
        assertTrue(result!!.stopped)
        assertEquals(2, result!!.cuts.size)
        assertTrue(store.finished!!.third)
        assertEquals("stopped", speechLog.last())
    }

    @Test fun `설정을 맞췄으면 조정 안내로 시작`() = runTest {
        val (engine) = setup(introMs = 0)
        engine.run(session(CaptureConfig(40, 20)), sources, adjusted = true, isDriving = { false })
        assertEquals("adjusted 40s 20", speechLog.first())
    }

    @Test fun `안내 음성이 실패해도 촬영은 계속`() = runTest {
        val now = { testScheduler.currentTime }
        val store = RecordingStore(now)
        val broken = object : ai.chaz.voice.SpeechOutput {
            override val name = "broken"
            override suspend fun speak(text: String) = error("TTS 엔진 없음")
            override fun stop() = Unit
        }
        val result = CaptureSessionEngine(broken, FakeCuePlayer(), store, PlainTtsPhrases, now)
            .run(session(), sources, adjusted = false, isDriving = { false })
        assertEquals(4, result.cuts.size)
    }
}
