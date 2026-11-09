package ai.chaz.voice

import ai.chaz.platform.CapabilityProbe
import ai.chaz.platform.Feature
import ai.chaz.platform.ProbeChains
import ai.chaz.platform.SdkAvailability
import android.util.Log

/**
 * SdkAvailability 가 고른 SpeechOutput 으로 말한다. 실패하면 이번 문장은 다음 후보(비즈라우터)로
 * 다시 말하고, CapabilityProbe 에 실패를 알려 3회 연속이면 후보가 내려가게 한다.
 */
class SpeechOutputRouter(
    private val availability: SdkAvailability,
    private val probe: CapabilityProbe,
    private val impls: Map<String, SpeechOutput>,
    private val fallback: SpeechOutput?,
) : SpeechOutput {
    override val name get() = current()?.name ?: "none"

    private fun current(): SpeechOutput? = availability.selectedId(Feature.SPEECH_OUTPUT)?.let { impls[it] } ?: fallback

    override suspend fun prepare(phrases: List<String>) {
        current()?.prepare(phrases)
    }

    override suspend fun speak(text: String) {
        val primary = current() ?: run { Log.w(TAG, "음성 안내 구현 없음"); return }
        try {
            primary.speak(text)
            probe.reportSuccess(Feature.SPEECH_OUTPUT)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "${primary.name} 실패: ${e.message}")
            probe.reportFailure(Feature.SPEECH_OUTPUT, e.message ?: e.javaClass.simpleName)
            val backup = fallback?.takeIf { it !== primary } ?: throw e
            backup.speak(text)
        }
    }

    override fun stop() {
        impls.values.forEach { it.stop() }
        fallback?.stop()
    }

    companion object {
        private const val TAG = "ChazSpeech"
        /** 후보 id → 구현. android.tts 는 에뮬레이터에 엔진이 없어 아직 만들지 않는다. */
        fun ids() = listOf(ProbeChains.Ids.TTS_PLEOS, ProbeChains.Ids.TTS_BIZROUTER)
    }
}
