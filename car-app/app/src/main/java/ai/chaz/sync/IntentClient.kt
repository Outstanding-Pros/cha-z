package ai.chaz.sync

import ai.chaz.voice.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 규칙 파서 실패 시 LLM 분류 (서버 /intent/parse — Edge Function intent-parse 대체). 2초 넘으면 Unknown. */
class IntentClient(private val baseUrl: String, private val timeoutMs: Long = 2_000) {

    suspend fun classify(text: String): Intent = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("$baseUrl/intent/parse").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = timeoutMs.toInt()
                    readTimeout = timeoutMs.toInt()
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode != 200) return@runCatching Intent.Unknown
                    toIntent(JSONObject(conn.inputStream.bufferedReader().readText()))
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(Intent.Unknown)
        }
    } ?: Intent.Unknown

    companion object {
        fun toIntent(j: JSONObject): Intent {
            fun int(k: String) = if (j.isNull(k)) null else j.optInt(k).takeIf { it > 0 }
            fun str(k: String) = if (j.isNull(k)) null else j.optString(k).ifBlank { null }
            return when (j.optString("intent")) {
                "CAPTURE_DEFAULT" -> Intent.CaptureDefault
                "CAPTURE_CUSTOM" -> Intent.CaptureCustom(int("durationSec"), int("count"))
                "RETAKE" -> Intent.Retake
                "STOP" -> Intent.Stop
                "BGM_PLAY" -> Intent.BgmPlay(str("mood"))
                "BGM_STOP" -> Intent.BgmStop
                "HANDOFF_PHONE" -> Intent.HandoffPhone
                "SEND_PHONE" -> Intent.SendPhone
                "LIGHT_MOOD" -> Intent.LightMood(str("preset"))
                "HELP" -> Intent.Help
                else -> Intent.Unknown
            }
        }
    }
}
