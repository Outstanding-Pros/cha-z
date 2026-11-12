package ai.chaz.sync

import ai.chaz.media.Result
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 이 차량 기기의 고정 id (devices 테이블). 첫 실행 때 만들어 저장한다. */
object DeviceIdentity {
    fun carId(context: Context): String {
        val prefs = context.getSharedPreferences("chaz", Context.MODE_PRIVATE)
        return prefs.getString("carDeviceId", null) ?: ("car-" + UUID.randomUUID()).also {
            prefs.edit().putString("carDeviceId", it).apply()
        }
    }
}

/**
 * 폰 연결 (SPEC F6). 서버(T10 전까지 로컬, Supabase 대체)를 거쳐서만 폰과 주고받는다.
 * - pairing-create: QR(페어링 토큰이 담긴 PWA URL, 10분 만료). 주행 중에는 QR 을 보여 주지 않는다(화면 잠금).
 * - deliver: 완성본 업로드 → 서버가 폰에 result.delivered 이벤트.
 * TODO(T10+): WorkManager 업로드 큐, 오프라인 재시도.
 */
class PhoneLink(private val baseUrl: String, val carId: String, private val scope: CoroutineScope) {

    data class Ticket(val token: String, val url: String, val expiresAtMs: Long)
    data class State(val paired: Boolean = false, val phones: Int = 0, val reachable: Boolean = true, val ticket: Ticket? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var poll: Job? = null

    private var events: Job? = null

    /**
     * 서버 이벤트(SSE /sync/events?deviceId=차량) 구독. 폰 음성 리모컨(voice.command)·폰 직접 셔터(phone.photo)·페어링 수락을 받는다.
     * 끊기면 3초 뒤 다시 붙는다. 문장 내용은 로그에 남기지 않는다.
     */
    /** onPhoto(photoId, role): role 은 폰이 고른 자리 — "INTERIOR" | "FRONT" | "REAR". */
    fun listen(onVoice: suspend (String) -> Unit, onPhoto: suspend (String, String) -> Unit = { _, _ -> }) {
        if (events?.isActive == true) return
        events = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val conn = ServerHttp.open("$baseUrl/sync/events?deviceId=$carId").apply {
                        connectTimeout = 5_000; readTimeout = 40_000 // 서버가 15초마다 ping
                        setRequestProperty("Accept", "text/event-stream")
                    }
                    try {
                        conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                            var event: String? = null
                            val data = StringBuilder()
                            while (isActive) {
                                val line = r.readLine() ?: break
                                when {
                                    line.startsWith("event:") -> event = line.substringAfter(":").trim()
                                    line.startsWith("data:") -> data.append(line.substringAfter(":").trim())
                                    line.isEmpty() -> {
                                        val ev = event; val d = data.toString()
                                        event = null; data.setLength(0)
                                        when (ev) {
                                            "voice.command" -> runCatching { JSONObject(d).getString("text") }.getOrNull()
                                                ?.takeIf { it.isNotBlank() }?.let { t ->
                                                    Log.i("ChazPhone", "폰 음성 명령 ${t.length}자")
                                                    scope.launch { onVoice(t) }
                                                }
                                            "phone.photo" -> runCatching { JSONObject(d) }.getOrNull()
                                                ?.let { j -> j.optString("photoId").takeIf { it.isNotBlank() }?.let { id -> id to j.optString("role", "INTERIOR") } }
                                                ?.let { (id, role) -> scope.launch { onPhoto(id, role) } }
                                            "pairing.claimed" -> scope.launch { refresh() }
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }.onFailure { Log.w("ChazPhone", "이벤트 연결 끊김: ${it.javaClass.simpleName}") }
                delay(3_000)
            }
        }
    }

    /** 폰이 직접 찍어 보낸 사진(JPEG). 서버는 한 번 가져가면 지운다. 없거나 실패하면 null. */
    suspend fun fetchPushed(photoId: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = ServerHttp.open("$baseUrl/sync/camera/pushed/$photoId?carDeviceId=$carId").apply {
                connectTimeout = 3_000; readTimeout = 10_000
            }
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "폰 사진 받기 실패: ${it.message}") }.getOrNull()
    }

    /** 연결 상태 한 번 확인. */
    suspend fun refresh(): State {
        val s = runCatching { http("GET", "/sync/pairing-status?carDeviceId=$carId") }.fold(
            onSuccess = { j -> _state.value.copy(paired = j.getBoolean("paired"), phones = j.getInt("phones"), reachable = true) },
            onFailure = { _state.value.copy(reachable = false) },
        )
        _state.value = s
        return s
    }

    /**
     * QR 띄우기. 'QR 닫기'(stopPairing) 전까지 계속 띄워 둔다 — 폰이 연결돼도 닫지 않는다.
     * 토큰은 한 폰만 쓸 수 있어서, 폰이 연결되거나 10분 만료가 다가오면 새 토큰(새 QR)으로 바꿔 다음 폰도 찍을 수 있게 한다.
     */
    fun startPairing() {
        poll?.cancel()
        poll = scope.launch {
            suspend fun newTicket(): Boolean = try {
                val j = http("POST", "/sync/pairing-create", JSONObject().put("carDeviceId", carId))
                _state.value = _state.value.copy(
                    reachable = true,
                    ticket = Ticket(j.getString("token"), j.getString("url"), (j.getDouble("expiresAt") * 1000).toLong()),
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "페어링 토큰 실패: ${e.message}")
                _state.value = _state.value.copy(reachable = false)
                false
            }
            if (!newTicket()) return@launch
            var phones = _state.value.phones
            while (isActive) {
                delay(2_000)
                val s = refresh()
                val t = s.ticket
                val claimed = s.phones > phones
                phones = maxOf(phones, s.phones)
                // 연결됐거나 곧 만료(30초 전) → 새 QR. 실패하면 다음 확인 때 다시 시도
                if (claimed || t == null || System.currentTimeMillis() > t.expiresAtMs - 30_000) newTicket()
            }
        }
    }

    fun stopPairing() {
        poll?.cancel()
        _state.value = _state.value.copy(ticket = null)
    }

    /** 완성본 전송. 연결된 폰 수를 돌려준다. */
    suspend fun deliver(result: Result): Int = withContext(Dispatchers.IO) {
        val meta = JSONObject()
            .put("id", result.id).put("sessionIds", JSONArray(result.sessionIds)).put("layout", result.layout.name)
            .put("kind", "PHOTO").put("filters", JSONArray(result.filters))
            .put("sourceKinds", JSONArray(result.sourceKinds.map { it.name })).put("createdOn", result.createdOn)
        val boundary = "----chaz" + System.nanoTime()
        val conn = ServerHttp.open("$baseUrl/sync/results").apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 3_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { o ->
                fun field(name: String, value: String) =
                    o.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
                field("carDeviceId", carId)
                field("meta", meta.toString())
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"result.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                File(result.path).inputStream().use { it.copyTo(o) }
                o.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (conn.responseCode != 200) error("전송 실패 HTTP ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().readText()).getInt("deliveredTo")
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun http(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val conn = ServerHttp.open(baseUrl + path).apply {
            requestMethod = method; connectTimeout = 3_000; readTimeout = 5_000
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        try {
            body?.let { b -> conn.outputStream.use { it.write(b.toString().toByteArray(Charsets.UTF_8)) } }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    companion object { private const val TAG = "ChazPhone" }
}
