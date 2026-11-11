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
 * - 컷: /sync/camera/shot 한 번으로 폰에 capture.shot 을 보내고 고해상 JPEG 를 받는다.
 *   실패하면 2초 안의 최근 미리보기로 대신한다 (컷이 비지 않게).
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

    override fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s ->
            s.launch {
                while (isActive) {
                    runCatching { pollLatest() }
                    delay(POLL_MS)
                }
            }
        }
    }

    override fun stop() {
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
        private const val SHOT_TIMEOUT_MS = 4_500

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
