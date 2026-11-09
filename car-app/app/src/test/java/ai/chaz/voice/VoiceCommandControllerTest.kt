package ai.chaz.voice

import ai.chaz.data.CaptureConfig
import ai.chaz.data.TriggerSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandControllerTest {

    private class Sink : CommandSink {
        override var capturing = false
        override var lastConfig = CaptureConfig(30, 6)
        override var driving = false
        val started = mutableListOf<Pair<TriggerSource, CaptureConfig>>()
        var stops = 0
        override var phonePaired = false
        var sendOutcome = SendOutcome.SENT
        var sends = 0
        override suspend fun sendToPhone(): SendOutcome { sends++; return sendOutcome }
        override fun startCapture(trigger: TriggerSource, config: CaptureConfig) { started += trigger to config }
        override fun stopCapture() { stops++ }
    }

    private class Env(val ctl: VoiceCommandController, val sink: Sink, val speech: FakeSpeechOutput, val cues: FakeCuePlayer, val llmCalls: MutableList<String>)

    private fun TestScope.env(
        follow: SpeechInput = FakeSpeechInput(),
        llm: Intent = Intent.Unknown,
        now: Long = 10_000,
    ): Env {
        val sink = Sink()
        val speech = FakeSpeechOutput()
        val cues = FakeCuePlayer()
        val calls = mutableListOf<String>()
        val ctl = VoiceCommandController(
            scope = this, sink = sink, speech = speech, input = follow, wakeInput = { null },
            cues = cues, phrases = PlainTtsPhrases, llm = { calls += it; llm }, now = { now },
        )
        return Env(ctl, sink, speech, cues, calls)
    }

    @Test fun `차즈 + 명령 한 문장이면 바로 실행 (1분 동안 10번)`() = runTest {
        val e = env()
        e.ctl.onHeard("차즈 1분 동안 10번 찍어줘")
        assertEquals(listOf(TriggerSource.WAKE_WORD to CaptureConfig(60, 10)), e.sink.started)
    }

    @Test fun `차즈만 들리고 1_5초 안에 말이 없으면 효과음 후 기본 촬영`() = runTest {
        val e = env(follow = FakeSpeechInput(null))
        e.ctl.onHeard("차즈")
        assertEquals(listOf("listening"), e.cues.events)
        assertEquals(listOf(TriggerSource.WAKE_WORD to CaptureConfig()), e.sink.started)
    }

    @Test fun `차즈 뒤에 따로 말한 명령을 쓴다`() = runTest {
        val e = env(follow = FakeSpeechInput("30초 동안 다섯 장"))
        e.ctl.onHeard("차즈야")
        assertEquals(CaptureConfig(30, 5), e.sink.started.single().second)
    }

    @Test fun `호출어가 없으면 무시하고 치즈도 무시`() = runTest {
        val e = env()
        e.ctl.onHeard("오늘 날씨 좋다")
        e.ctl.onHeard("치즈")
        assertTrue(e.sink.started.isEmpty())
        assertTrue(e.speech.spoken.isEmpty())
    }

    @Test fun `세션 종료 직후 3초는 호출어 무시`() = runTest {
        val e = env(now = 10_000)
        e.ctl.ignoreWakeUntil = 12_000
        e.ctl.onHeard("차즈 1분 동안 10번")
        assertTrue(e.sink.started.isEmpty())
    }

    @Test fun `촬영 중에는 그만만 받는다`() = runTest {
        val e = env()
        e.sink.capturing = true
        e.ctl.onHeard("차즈 다시 찍어줘")
        e.ctl.onHeard("차즈 노래 틀어줘")
        assertTrue(e.sink.started.isEmpty() && e.speech.spoken.isEmpty())
        e.ctl.onHeard("차즈 그만")
        assertEquals(1, e.sink.stops)
    }

    @Test fun `규칙 파서가 모르면 LLM 폴백, 그래도 모르면 다시 말씀해 주세요`() = runTest {
        val e = env(llm = Intent.CaptureCustom(30, null))
        e.ctl.onHeard("차즈 사진 좀 여러 장 박아줘") // 숫자가 없어 규칙 파서는 Unknown
        assertEquals(1, e.llmCalls.size)
        assertEquals(CaptureConfig(30, 10), e.sink.started.single().second) // 장수가 없으면 3초 간격으로 채움

        val e2 = env(llm = Intent.Unknown)
        e2.ctl.onHeard("차즈 오늘 날씨 어때")
        assertEquals(listOf("unknown"), e2.speech.spoken)
    }

    @Test fun `다시 찍기는 직전 설정, 기타 명령은 안내`() = runTest {
        val e = env()
        e.ctl.onHeard("차즈 다시 찍어줘")
        assertEquals(CaptureConfig(30, 6), e.sink.started.single().second)
        e.ctl.onHeard("차즈 폰으로 보내줘")
        e.ctl.onHeard("차즈 뭐 할 수 있어")
        e.sink.driving = true
        e.ctl.onHeard("차즈 분위기 바꿔줘")
        assertEquals(listOf("phone-not-paired", "help", "light-driving"), e.speech.spoken)
    }

    @Test fun `폰으로 보내기 - 연결 안 됨, 보냄, 사진 없음`() = runTest {
        val e = env()
        e.ctl.onHeard("차즈 폰으로 보내줘")
        e.sink.phonePaired = true
        e.ctl.onHeard("차즈 폰으로 보내줘")
        e.sink.sendOutcome = SendOutcome.NO_RESULT
        e.ctl.onHeard("차즈 폰으로 보내줘")
        assertEquals(listOf("phone-not-paired", "sent", "no-result"), e.speech.spoken)
        assertEquals(2, e.sink.sends)
    }

    @Test fun `음성 버튼 경로는 호출어 없이도 실행`() = runTest {
        val e = env()
        e.ctl.handleText("1분 동안 10번 찍어줘", requireWake = false)
        assertEquals(TriggerSource.VOICE_BUTTON to CaptureConfig(60, 10), e.sink.started.single())
    }
}
