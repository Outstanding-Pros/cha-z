package ai.chaz.ui.spike

import ai.chaz.capture.CameraInventory
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener as SttResult
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEvents
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * T04 스파이크: 카메라·마이크·TTS·STT 실측. 결과는 화면과 logcat(ChazSpike)에 같이 남기고
 * docs/spike.md 에 옮겨 적는다. 개발용 화면이며 시연 대상이 아니다.
 */
class SpikeRunner(private val context: Context) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private fun log(s: String) {
        Log.i(TAG, s)
        _lines.update { it + s }
    }

    fun clear() { _lines.value = emptyList() }

    suspend fun runAll() {
        clear()
        cameras()
        mic()
        TtsMode.entries.forEach { pleosTts(it) }
        SttMode.entries.forEach { pleosStt(it) }
        log("== 완료")
    }

    fun cameras() {
        log("== 카메라")
        val r = CameraInventory.scan(context)
        log("카메라 ${r.cameras.size}대")
        r.cameras.forEach { log("#${it.id} role=${it.role} (${it.roleSource}) facing=${it.lensFacing} max=${it.maxSize}") }
        log("동시 오픈 조합: " + if (r.concurrent.isEmpty()) "없음/미지원" else r.concurrent.joinToString { it.joinToString("+") })
    }

    @SuppressLint("MissingPermission")
    suspend fun mic() = withContext(Dispatchers.IO) {
        log("== 마이크 (2초 녹음 RMS)")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("RECORD_AUDIO 권한 없음"); return@withContext
        }
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { log("AudioRecord 초기화 실패"); rec.release(); return@withContext }
        val buf = ShortArray(rate * 2)
        rec.startRecording()
        var read = 0
        while (read < buf.size) {
            val n = rec.read(buf, read, buf.size - read)
            if (n <= 0) break
            read += n
        }
        rec.stop(); rec.release()
        var sum = 0.0; var peak = 0
        for (i in 0 until read) { val v = buf[i].toInt(); sum += v * v; if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
        val rms = if (read > 0) sqrt(sum / read) else 0.0
        log("읽은 샘플 $read, RMS=${"%.1f".format(rms)}, peak=$peak → " + if (peak > 200) "입력 있음" else "무음(호스트 마이크 꺼짐?)")
    }

    suspend fun pleosTts(mode: TtsMode) = withContext(Dispatchers.Main) {
        log("== Pleos TTS ($mode)")
        val t0 = System.currentTimeMillis()
        val tts = PleosTts(context.applicationContext, mode)
        val init = CompletableDeferred<String>()
        val done = CompletableDeferred<String>()
        try {
            tts.initialize(object : TtsInit {
                override fun onSuccess() { init.complete("성공") }
                override fun onFailure(error: TtsError) { init.complete("실패 $error") }
            })
            val i = withTimeoutOrNull(6_000) { init.await() } ?: "응답 없음"
            log("initialize: $i")
            if (i != "성공") return@withContext
            tts.addEventListener(object : TtsEvents {
                override fun onStart() { log("TTS onStart") }
                override fun onReady() { log("TTS onReady") }
                override fun onDone() { done.complete("onDone") }
                override fun onError(error: TtsError) { done.complete("onError $error") }
                override fun onStop() { done.complete("onStop") }
                override fun onUpdatedRms(rms: Double) = Unit
            })
            tts.speak("차즈 음성 안내 테스트입니다.", true) // 2번째 인자 = 스트림 텍스트 마지막 여부 (false면 onDone 안 옴)
            log("speak 결과: " + (withTimeoutOrNull(40_000) { done.await() } ?: "40초 내 응답 없음") + " (${System.currentTimeMillis() - t0}ms)")
        } catch (t: Throwable) {
            log("예외 ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { tts.release() }
        }
    }

    suspend fun pleosStt(mode: SttMode) = withContext(Dispatchers.Main) {
        log("== Pleos STT ($mode) — 4초 동안 말해 보세요")
        val stt = PleosStt(context.applicationContext, mode)
        val init = CompletableDeferred<String>()
        val end = CompletableDeferred<String>()
        val listener = object : SttResult {
            override fun onUpdated(text: String, isFinal: Boolean) {
                log("STT onUpdated final=$isFinal \"$text\"")
                if (isFinal) end.complete("최종 결과")
            }
            override fun onUpdatedEpdData(p0: Long, p1: Long) = Unit
            override fun onStartedRecognition() { log("STT onStartedRecognition") }
            override fun onEndedRecognition() { end.complete("onEndedRecognition") }
            override fun onError(error: SttError) { end.complete("onError $error") }
            override fun onReady() { log("STT onReady") }
            override fun onUpdatedRms(rms: Double) = Unit
        }
        try {
            stt.addListener(listener)
            stt.initialize(object : SttInit {
                override fun onSuccess() { init.complete("성공") }
                override fun onFailure(error: SttError) { init.complete("실패 $error") }
            })
            val i = withTimeoutOrNull(6_000) { init.await() } ?: "응답 없음"
            log("initialize: $i")
            if (i != "성공") return@withContext
            stt.request()
            log("request 결과: " + (withTimeoutOrNull(12_000) { end.await() } ?: "12초 내 종료 없음"))
            runCatching { stt.stop() }
        } catch (t: Throwable) {
            log("예외 ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { stt.removeListener(listener) }
            runCatching { stt.release() }
        }
    }

    companion object { const val TAG = "ChazSpike" }
}
