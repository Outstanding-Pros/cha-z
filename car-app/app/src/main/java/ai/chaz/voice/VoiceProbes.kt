package ai.chaz.voice

import ai.chaz.platform.ProbeResult
import android.content.Context
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import android.speech.tts.TextToSpeech as AndroidTts

/** SpeechOutput·SpeechInput·WakeWordDetector 후보 판정. 실제 구현은 T07. */
object VoiceProbes {

    /** Pleos TextToSpeech SDK: initialize() 성공 여부. 인스턴스는 판정 후 release. */
    suspend fun probePleosTts(context: Context, mode: TtsMode = TtsMode.ON_DEVICE): ProbeResult =
        withContext(Dispatchers.Main) {
            val tts = PleosTts(context.applicationContext, mode)
            val done = CompletableDeferred<ProbeResult>()
            try {
                tts.initialize(object : TtsInitListener {
                    override fun onSuccess() { done.complete(ProbeResult.Ok("initialize 성공 ($mode)")) }
                    override fun onFailure(error: TtsError) { done.complete(ProbeResult.Fail("initialize 실패: $error ($mode)")) }
                })
                withTimeoutOrNull(5_000) { done.await() } ?: ProbeResult.Fail("initialize 응답 없음 ($mode)")
            } finally {
                runCatching { tts.release() }
            }
        }

    /** Pleos SpeechToText SDK: initialize() 성공 여부. */
    suspend fun probePleosStt(context: Context, mode: SttMode = SttMode.HYBRID): ProbeResult =
        withContext(Dispatchers.Main) {
            val stt = PleosStt(context.applicationContext, mode)
            val done = CompletableDeferred<ProbeResult>()
            try {
                stt.initialize(object : SttInitListener {
                    override fun onSuccess() { done.complete(ProbeResult.Ok("initialize 성공 ($mode)")) }
                    override fun onFailure(error: SttError) { done.complete(ProbeResult.Fail("initialize 실패: $error ($mode)")) }
                })
                withTimeoutOrNull(5_000) { done.await() } ?: ProbeResult.Fail("initialize 응답 없음 ($mode)")
            } finally {
                runCatching { stt.release() }
            }
        }

    /** Android TextToSpeech: 엔진 초기화 + 한국어 지원. */
    suspend fun probeAndroidTts(context: Context): ProbeResult = withContext(Dispatchers.Main) {
        val status = CompletableDeferred<Int>()
        val tts = AndroidTts(context.applicationContext) { status.complete(it) }
        try {
            val s = withTimeoutOrNull(5_000) { status.await() } ?: return@withContext ProbeResult.Fail("엔진 응답 없음")
            if (s != AndroidTts.SUCCESS) return@withContext ProbeResult.Fail("엔진 초기화 실패 (status=$s, 엔진=${tts.defaultEngine})")
            val lang = tts.isLanguageAvailable(Locale.KOREAN)
            if (lang >= AndroidTts.LANG_AVAILABLE) ProbeResult.Ok("한국어 지원 (엔진=${tts.defaultEngine})")
            else ProbeResult.Fail("한국어 미지원 (코드 $lang)")
        } finally {
            tts.shutdown()
        }
    }

    fun probeAndroidRecognizer(context: Context): ProbeResult =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            ProbeResult.Ok("인식 서비스 있음 (실제 인식은 런타임 실패 시 다음 후보로)")
        } else ProbeResult.Fail("인식 서비스 없음")
}
