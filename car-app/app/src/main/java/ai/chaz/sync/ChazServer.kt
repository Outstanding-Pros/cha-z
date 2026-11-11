package ai.chaz.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 로컬 개발 서버(server/, FastAPI) 클라이언트. 사양서의 Edge Function(health, tts-synthesize,
 * stt-transcribe, image-generate)을 T10 Supabase 이전까지 대신한다. 비즈라우터 키는 서버에만 있다.
 */
class ChazServer(val baseUrl: String) {

    data class Health(val reachable: Boolean, val keyConfigured: Boolean, val detail: String)

    private val mutex = Mutex()
    private var cached: Pair<Long, Health>? = null

    /** CapabilityProbe 가 여러 기능에서 동시에 부르므로 10초 캐시. */
    suspend fun health(): Health = mutex.withLock {
        cached?.let { (at, h) -> if (System.currentTimeMillis() - at < 10_000) return@withLock h }
        val h = fetchHealth()
        cached = System.currentTimeMillis() to h
        h
    }

    private suspend fun fetchHealth(): Health = withContext(Dispatchers.IO) {
        try {
            val conn = ServerHttp.open("$baseUrl/health").apply {
                connectTimeout = 2_500
                readTimeout = 2_500
            }
            try {
                if (conn.responseCode != 200) return@withContext Health(true, false, "HTTP ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val key = json.optBoolean("keyConfigured", false)
                Health(true, key, if (key) "서버 연결, 비즈라우터 키 설정됨" else "서버 연결, 키 미설정")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Health(false, false, "서버 연결 실패 (${e.javaClass.simpleName})")
        }
    }
}
