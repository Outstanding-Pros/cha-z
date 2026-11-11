package ai.chaz.voice

import ai.chaz.sync.ServerHttp
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 비즈라우터 전사 (서버 /voice/stt 경유). 16kHz 모노로 녹음하다가 말이 끝나면(0.7초 무음) 보낸다.
 * 말이 시작되지 않으면 서버에 보내지 않는다 (비용·프라이버시). 녹음본은 메모리에서만 쓰고 저장하지 않는다.
 */
class ServerSpeechInput(private val context: Context, private val baseUrl: String) : SpeechInput {
    override val name = "bizrouter-transcribe"
    private val lock = Mutex()
    @Volatile private var cancelled = false

    @SuppressLint("MissingPermission")
    override suspend fun listen(maxMs: Long, noSpeechMs: Long, onPartial: (String) -> Unit): String? = lock.withLock {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("RECORD_AUDIO 권한 없음")
        }
        cancelled = false
        val pcm = withContext(Dispatchers.IO) { record(maxMs, noSpeechMs) } ?: return@withLock null
        val text = withContext(Dispatchers.IO) { upload(wav(pcm)) }
        text.ifBlank { null }?.also(onPartial)
    }

    override fun cancel() { cancelled = true }

    @SuppressLint("MissingPermission")
    private suspend fun record(maxMs: Long, noSpeechMs: Long): ByteArray? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 실패" }
        val out = ByteArrayOutputStream()
        val frame = ShortArray(RATE / 10) // 100ms
        var elapsed = 0L
        var speechAt = -1L
        var silentMs = 0L
        rec.startRecording()
        try {
            while (elapsed < maxMs && !cancelled) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) break
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until n) { bb.putShort(frame[i]); sum += frame[i] * frame[i].toDouble() }
                out.write(bb.array())
                elapsed += 100
                val loud = sqrt(sum / n) > SPEECH_RMS
                if (loud) { if (speechAt < 0) speechAt = elapsed; silentMs = 0 } else silentMs += 100
                if (speechAt < 0 && elapsed >= noSpeechMs) return null
                if (speechAt >= 0 && silentMs >= END_SILENCE_MS) break
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return if (speechAt < 0) null else out.toByteArray()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    private fun upload(wav: ByteArray): String {
        val boundary = "----chaz" + System.nanoTime()
        val conn = ServerHttp.open("$baseUrl/voice/stt").apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { o ->
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                o.write(wav)
                o.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (conn.responseCode != 200) error("STT 서버 HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().readText()).optString("text")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val RATE = 16_000
        private const val SPEECH_RMS = 500.0
        private const val END_SILENCE_MS = 700L
    }
}
