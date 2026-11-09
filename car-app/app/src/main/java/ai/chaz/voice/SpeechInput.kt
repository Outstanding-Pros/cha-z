package ai.chaz.voice

import ai.chaz.platform.CapabilityProbe
import ai.chaz.platform.Feature
import ai.chaz.platform.ProbeChains
import ai.chaz.platform.SdkAvailability
import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * 명령 인식 어댑터. 한 발화를 듣고 최종 문장을 돌려준다.
 * noSpeechMs 안에 말이 시작되지 않으면 null. 음성 원본은 저장하지 않는다.
 */
interface SpeechInput {
    val name: String
    suspend fun listen(maxMs: Long = 5_000, noSpeechMs: Long = 4_000, onPartial: (String) -> Unit = {}): String?
    fun cancel()
}

/** 테스트·디버그용. 미리 넣어 둔 문장을 차례로 돌려준다. */
class FakeSpeechInput(vararg utterances: String?) : SpeechInput {
    override val name = "fake"
    private val queue = ArrayDeque(utterances.toList())
    override suspend fun listen(maxMs: Long, noSpeechMs: Long, onPartial: (String) -> Unit): String? =
        queue.removeFirstOrNull()
    override fun cancel() = Unit
}

/** SdkAvailability 가 고른 SpeechInput 으로 듣고, 실패하면 다음 후보(비즈라우터 전사)로. */
class SpeechInputRouter(
    private val availability: SdkAvailability,
    private val probe: CapabilityProbe,
    private val impls: Map<String, SpeechInput>,
    private val fallback: SpeechInput?,
) : SpeechInput {
    override val name get() = current()?.name ?: "none"

    fun current(): SpeechInput? = availability.selectedId(Feature.SPEECH_INPUT)?.let { impls[it] } ?: fallback

    override suspend fun listen(maxMs: Long, noSpeechMs: Long, onPartial: (String) -> Unit): String? {
        val primary = current() ?: return null
        return try {
            primary.listen(maxMs, noSpeechMs, onPartial).also { probe.reportSuccess(Feature.SPEECH_INPUT) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ChazVoice", "${primary.name} 인식 실패: ${e.message}")
            probe.reportFailure(Feature.SPEECH_INPUT, e.message ?: e.javaClass.simpleName)
            val backup = fallback?.takeIf { it !== primary } ?: throw e
            backup.listen(maxMs, noSpeechMs, onPartial)
        }
    }

    override fun cancel() {
        impls.values.forEach { it.cancel() }
        fallback?.cancel()
    }

    companion object {
        fun ids() = listOf(ProbeChains.Ids.STT_PLEOS, ProbeChains.Ids.STT_BIZROUTER)
    }
}
