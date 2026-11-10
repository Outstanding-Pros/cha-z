package ai.chaz.voice

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEvents
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos TextToSpeech SDK (1순위). 스파이크 결과(docs/spike.md):
 * - SERVER 모드는 앱 자격 증명이 없어 실패 → ON_DEVICE 사용 (한국어 seoyoon)
 * - addEventListener 는 initialize 성공 뒤에, speak(text, isFinal = true) 여야 onDone 이 온다
 */
class PleosSpeechOutput(context: Context, private val focus: AudioFocus) : SpeechOutput {
    override val name = "pleos-tts-ondevice"
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var tts: PleosTts? = null
    @Volatile private var pending: CompletableDeferred<Unit>? = null

    private val events = object : TtsEvents {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() { pending?.complete(Unit) }
        override fun onStop() { pending?.complete(Unit) }
        override fun onError(error: TtsError) { pending?.completeExceptionally(IllegalStateException("Pleos TTS $error")) }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private suspend fun ensure(): PleosTts = withContext(Dispatchers.Main) {
        tts?.let { return@withContext it }
        val t = PleosTts(appContext, TtsMode.ON_DEVICE)
        val init = CompletableDeferred<TtsError?>()
        t.initialize(object : TtsInit {
            override fun onSuccess() { init.complete(null) }
            override fun onFailure(error: TtsError) { init.complete(error) }
        })
        // init 은 성공 시 null 로 끝나므로 감싸서 시간 초과와 구분한다
        val err = (withTimeoutOrNull(6_000) { listOf(init.await()) } ?: run { runCatching { t.release() }; error("Pleos TTS initialize 응답 없음") })[0]
        if (err != null) { runCatching { t.release() }; error("Pleos TTS initialize 실패: $err") }
        t.addEventListener(events)
        tts = t
        t
    }

    /** 첫 호출의 모델 로딩(~1초)을 촬영 전에 끝내 둔다. */
    override suspend fun prepare(phrases: List<String>) {
        runCatching { lock.withLock { ensure() } }
    }

    override suspend fun speak(text: String) = lock.withLock {
        val t = ensure()
        val done = CompletableDeferred<Unit>()
        pending = done
        focus.acquire()
        try {
            withContext(Dispatchers.Main) { t.speak(text, true) }
            // 한국어 한 글자 ~0.15초 + 합성 여유. 시간 초과는 일반 예외로 — withTimeout 의 취소 예외는 촬영 세션까지 죽인다
            withTimeoutOrNull(2_500L + text.length * 180L) { done.await() } ?: error("Pleos TTS onDone 없음")
        } finally {
            pending = null
            focus.release()
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
        pending?.complete(Unit)
    }

    fun release() {
        runCatching { tts?.removeEventListener(events) }
        runCatching { tts?.release() }
        tts = null
    }
}
