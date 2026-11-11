package ai.chaz.capture

import ai.chaz.sync.ServerHttp
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 폰 컴패니언 카메라 (SPEC P5 · T11). 폰 PWA 가 '실내 카메라 모드'에서 켜 둔 영상을 서버를 거쳐 쓴다.
 * - 미리보기·클립: 폰이 0.5초마다 올리는 저해상 프레임을 가져온다(/sync/camera/latest).
 * - 컷(예약 촬영): 엔진이 컷 2초 이상 전에 scheduleShot 을 부르면 '서버 시각 atMs 에 찍어'를 보낸다(/arm).
 *   폰은 서버와 맞춘 시계로 그 순간에 찍어 올리고, 셔터 시각의 capture() 는 그 결과를 받아 온다(/result).
 *   Cloudflare 경유 지연(0.3~1.8초)이 있어도 셔터 순간에 찍히게 하려는 것.
 * - 예약이 없거나 실패하면 즉시 촬영(/shot), 그것도 실패하면 2초 안의 최근 미리보기로 대신한다 (컷이 비지 않게).
 */
class PhoneCameraSource(
    private val baseUrl: String,
    private val carId: String,
) : CameraSource {
    override val role = CameraRole.INTERIOR
    override val kind = SourceKind.PHONE_CAMERA
    override val label = "폰 카메라"

    private var scope: CoroutineScope? = null
    private val latest = MutableStateFlow<Bitmap?>(null)
    @Volatile private var latestAt = 0L
    private var seq = 0L

    /** 서버 시각 - 내 시각 (ms). null 이면 아직 못 맞춤 → 예약 대신 즉시 촬영. */
    @Volatile private var offsetMs: Long? = null

    private class Armed(val atLocalMs: Long, val shotId: Deferred<String?>)
    private val armed = ConcurrentLinkedQueue<Armed>()

    override fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s ->
            s.launch {
                while (isActive) {
                    runCatching { pollLatest() }
                    delay(POLL_MS)
                }
            }
            s.launch {
                while (isActive) {
                    syncClock()
                    delay(CLOCK_SYNC_MS)
                }
            }
        }
    }

    /** 서버 시계 맞추기: 8번 재서 왕복이 가장 짧았던 값을 쓴다 (오차 ≈ 왕복/2). */
    private fun syncClock() {
        var best: Pair<Long, Long>? = null // rtt, offset
        repeat(8) {
            runCatching {
                val t0 = System.currentTimeMillis()
                val conn = open("/sync/time", readTimeoutMs = 2_000)
                try {
                    if (conn.responseCode != 200) return@runCatching
                    val t = JSONObject(conn.inputStream.bufferedReader().readText()).getDouble("t").toLong()
                    val t1 = System.currentTimeMillis()
                    if (best == null || t1 - t0 < best!!.first) best = (t1 - t0) to (t - (t0 + t1) / 2)
                } finally {
                    conn.disconnect()
                }
            }
        }
        best?.let { (rtt, off) ->
            offsetMs = off
            Log.i(TAG, "서버 시계 맞춤: 보정 ${off}ms, 왕복 ${rtt}ms")
        }
    }

    override fun scheduleShot(index: Int, inMs: Long) {
        val s = scope ?: return
        val off = offsetMs ?: return
        if (inMs < MIN_LEAD_MS) return // 너무 촉박하면 즉시 촬영으로
        val atLocal = System.currentTimeMillis() + inMs
        armed += Armed(atLocal, s.async { runCatching { arm(index, atLocal + off) }.onFailure { Log.w(TAG, "예약 실패: ${it.message}") }.getOrNull() })
    }

    private fun arm(index: Int, atServerMs: Long): String {
        val conn = open("/sync/camera/arm", readTimeoutMs = 4_000).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use {
                it.write(JSONObject().put("carDeviceId", carId).put("atMs", atServerMs).put("index", index).toString().toByteArray())
            }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().readText()).getString("shotId")
        } finally {
            conn.disconnect()
        }
    }

    /** 이번 셔터에 해당하는 예약. 지난 세션에서 남은 오래된 예약은 버린다. */
    private suspend fun takeArmed(): String? {
        val now = System.currentTimeMillis()
        while (true) {
            val a = armed.poll() ?: return null
            if (a.atLocalMs < now - 3_000) { a.shotId.cancel(); continue }
            return withTimeoutOrNull(1_500) { a.shotId.await() }
        }
    }

    private fun fetchResult(shotId: String): Bitmap {
        val conn = open("/sync/camera/result/$shotId?carDeviceId=$carId&waitMs=4000", readTimeoutMs = SHOT_TIMEOUT_MS)
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("JPEG 디코딩 실패")
        } finally {
            conn.disconnect()
        }
    }

    override fun stop() {
        armed.clear()
        scope?.cancel()
        scope = null
    }

    private fun pollLatest() {
        val conn = open("/sync/camera/latest?carDeviceId=$carId&after=$seq", readTimeoutMs = 2_000)
        try {
            if (conn.responseCode != 200) return
            val bytes = conn.inputStream.use { it.readBytes() }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            seq = conn.getHeaderField("X-Seq")?.toLongOrNull() ?: seq
            latest.value = bmp
            latestAt = System.currentTimeMillis()
        } finally {
            conn.disconnect()
        }
    }

    override fun createPreview(context: Context, owner: LifecycleOwner): View {
        val view = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF000000.toInt())
        }
        val job = CoroutineScope(Dispatchers.Main).launch {
            latest.filterNotNull().collect { view.setImageBitmap(it) }
        }
        view.tag = job
        start() // CameraXSource 와 같이: 미리보기가 붙으면 프레임 가져오기 시작
        return view
    }

    override fun releasePreview(view: View) {
        (view.tag as? Job)?.cancel()
        view.tag = null
    }

    override suspend fun capture(): Bitmap = withContext(Dispatchers.IO) {
        try {
            takeArmed()?.let { return@withContext fetchResult(it) }
            val conn = open("/sync/camera/shot", readTimeoutMs = SHOT_TIMEOUT_MS).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(JSONObject().put("carDeviceId", carId).toString().toByteArray()) }
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val bytes = conn.inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("JPEG 디코딩 실패")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            val fallback = latest.value?.takeIf { System.currentTimeMillis() - latestAt < 2_000 }
            Log.w(TAG, "폰 고해상 컷 실패(${e.message}) → " + if (fallback != null) "최근 미리보기로 대신" else "컷 없음")
            fallback ?: throw e
        }
    }

    override suspend fun snapshot(): Bitmap? = latest.value?.takeIf { System.currentTimeMillis() - latestAt < 2_000 }

    private fun open(path: String, readTimeoutMs: Int) =
        ServerHttp.open(baseUrl + path).apply {
            connectTimeout = 2_000
            readTimeout = readTimeoutMs
            useCaches = false
        }

    companion object {
        private const val TAG = "ChazPhoneCam"
        private const val POLL_MS = 400L
        private const val SHOT_TIMEOUT_MS = 5_000
        private const val CLOCK_SYNC_MS = 60_000L
        private const val MIN_LEAD_MS = 800L

        /** CapabilityProbe 용: 폰 카메라 모드가 켜져 있는지. */
        suspend fun online(baseUrl: String, carId: String): Boolean? = withContext(Dispatchers.IO) {
            runCatching {
                val conn = ServerHttp.open("$baseUrl/sync/camera/status?carDeviceId=$carId")
                    .apply { connectTimeout = 2_000; readTimeout = 2_000 }
                try {
                    if (conn.responseCode != 200) null
                    else JSONObject(conn.inputStream.bufferedReader().readText()).getBoolean("online")
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
    }
}
