package ai.chaz.voice

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener as SttResult

/**
 * Pleos SpeechToText SDK, ON_DEVICE (스파이크: SERVER 는 자격 증명이 없어 실패, 온디바이스 ko_KR 은 동작).
 * request() 하면 CAAS 가 직접 마이크를 열고, 끝점(EPD)에서 최종 결과를 준다.
 */
class PleosSpeechInput(context: Context) : SpeechInput {
    override val name = "pleos-stt-ondevice"
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var stt: PleosStt? = null

    private class Turn(val onPartial: (String) -> Unit) {
        val result = CompletableDeferred<String?>()
        @Volatile var last: String? = null
        val firstSpeech = CompletableDeferred<Unit>()
    }
    @Volatile private var turn: Turn? = null

    private val listener = object : SttResult {
        override fun onUpdated(text: String, isFinal: Boolean) {
            val t = turn ?: return
            if (text.isNotBlank()) {
                t.last = text
                t.firstSpeech.complete(Unit)
                t.onPartial(text)
            }
            if (isFinal) t.result.complete(text.ifBlank { t.last })
        }
        override fun onUpdatedEpdData(p0: Long, p1: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() { turn?.let { it.result.complete(it.last) } }
        override fun onError(error: SttError) {
            val t = turn ?: return
            t.result.completeExceptionally(IllegalStateException("Pleos STT $error"))
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private suspend fun ensure(): PleosStt = withContext(Dispatchers.Main) {
        stt?.let { return@withContext it }
        val s = PleosStt(appContext, SttMode.ON_DEVICE)
        s.addListener(listener)
        val init = CompletableDeferred<SttError?>()
        s.initialize(object : SttInit {
            override fun onSuccess() { init.complete(null) }
            override fun onFailure(error: SttError) { init.complete(error) }
        })
        // init 은 성공 시 null 로 끝나므로 감싸서 시간 초과와 구분한다
        val err = (withTimeoutOrNull(6_000) { listOf(init.await()) } ?: run { runCatching { s.release() }; error("Pleos STT initialize 응답 없음") })[0]
        if (err != null) { runCatching { s.release() }; error("Pleos STT initialize 실패: $err") }
        stt = s
        s
    }

    override suspend fun listen(maxMs: Long, noSpeechMs: Long, onPartial: (String) -> Unit): String? = lock.withLock {
        val s = ensure()
        val t = Turn(onPartial)
        turn = t
        try {
            withContext(Dispatchers.Main) { s.request() }
            coroutineScope {
                // 말이 시작되지 않으면 일찍 끝낸다
                val guard = launch {
                    if (withTimeoutOrNull(noSpeechMs) { t.firstSpeech.await() } == null) t.result.complete(null)
                }
                val out = withTimeoutOrNull(maxMs) { t.result.await() } ?: t.last
                guard.cancel()
                out
            }
        } finally {
            turn = null
            withContext(Dispatchers.Main) { runCatching { s.stop() } }
            delay(50) // stop 처리 여유 (다음 request 와 겹치지 않게)
        }
    }

    override fun cancel() {
        turn?.result?.complete(turn?.last)
    }

    fun release() {
        runCatching { stt?.removeListener(listener) }
        runCatching { stt?.release() }
        stt = null
    }
}
