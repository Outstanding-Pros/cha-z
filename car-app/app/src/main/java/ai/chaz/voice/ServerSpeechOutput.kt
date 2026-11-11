package ai.chaz.voice

import ai.chaz.sync.ServerHttp
import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 비즈라우터 TTS (서버 /voice/tts 경유, 키는 서버에만). 문장 해시로 앱에도 캐시해서
 * 같은 문장은 네트워크 없이 바로 재생한다.
 */
class ServerSpeechOutput(context: Context, private val baseUrl: String, private val focus: AudioFocus) : SpeechOutput {
    override val name = "bizrouter-tts"
    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val lock = Mutex()
    @Volatile private var player: MediaPlayer? = null

    override suspend fun prepare(phrases: List<String>) {
        phrases.forEach { runCatching { fetch(it) } }
    }

    override suspend fun speak(text: String) = lock.withLock {
        val file = fetch(text)
        focus.acquire()
        try {
            play(file)
        } finally {
            focus.release()
        }
    }

    override fun stop() {
        runCatching { player?.stop() }
    }

    private suspend fun fetch(text: String): File = withContext(Dispatchers.IO) {
        val f = File(cacheDir, sha1(text) + ".mp3")
        if (f.exists() && f.length() > 0) return@withContext f
        val conn = ServerHttp.open("$baseUrl/voice/tts").apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) error("TTS 서버 HTTP ${conn.responseCode}")
            val tmp = File(cacheDir, f.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(f)
            f
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun play(file: File) {
        val done = CompletableDeferred<Unit>()
        val mp = withContext(Dispatchers.Main) {
            MediaPlayer().apply {
                setAudioAttributes(AudioFocus.ATTRS)
                setDataSource(file.absolutePath)
                setOnCompletionListener { done.complete(Unit) }
                setOnErrorListener { _, what, extra -> done.completeExceptionally(IllegalStateException("MediaPlayer $what/$extra")); true }
                prepare()
                start()
            }
        }
        player = mp
        try {
            withTimeoutOrNull(mp.duration.toLong().coerceAtLeast(1_000) + 3_000) { done.await() } ?: error("재생 완료 콜백 없음")
        } finally {
            player = null
            withContext(Dispatchers.Main) { runCatching { mp.release() } }
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
