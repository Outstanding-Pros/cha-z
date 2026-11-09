package ai.chaz.media

import ai.chaz.capture.CaptureSessionEngine
import ai.chaz.capture.SourceKind
import ai.chaz.data.Cut
import ai.chaz.platform.vehicle.VehicleStateProvider
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SPEC 도메인 Result. createdOn = CAR (폰 편집은 T15). */
data class Result(
    val id: String,
    val sessionIds: List<String>,
    val layout: Layout,
    val path: String,
    val filters: List<String>,
    val sourceKinds: Set<SourceKind>,
    val createdOn: String = "CAR",
)

/**
 * C3 편집의 뒷단: 컷 이미지 로드(캐시) → 레이아웃 렌더 → 저장(앱 폴더 + 갤러리).
 * 렌더러 선택·창문 마스크·스탬프 값은 여기서 모은다.
 */
class ResultComposer(
    private val context: Context,
    private val renderers: Map<Layout, LayoutRenderer>,
    private val vehicle: () -> VehicleStateProvider,
    private val windowMask: () -> WindowMask?,
) {
    private val cache = LinkedHashMap<String, CutImages>()

    private suspend fun images(cut: Cut, sample: Int): CutImages = withContext(Dispatchers.IO) {
        val key = "${cut.sessionId}/${cut.index}@$sample"
        synchronized(cache) { cache[key] } ?: CutImages.load(cut, sample).also {
            synchronized(cache) {
                cache[key] = it
                while (cache.size > 12) cache.remove(cache.keys.first())
            }
        }
    }

    fun available(layout: Layout): Boolean = when (layout) {
        Layout.WINDOW_SWAP -> windowMask() != null && renderers.containsKey(layout)
        else -> renderers.containsKey(layout)
    }

    suspend fun options(result: CaptureSessionEngine.Result, frameColor: FrameColor, showStamp: Boolean): RenderOptions {
        val v = vehicle()
        val kinds = (result.cuts.flatMap { c -> c.frames.map { it.source } }).toSet()
        return RenderOptions(
            stamp = StampInfo(
                place = null, // TODO(T12): SpotRepository 스팟명 / NaviHelper 위치
                dateText = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(result.session.startedAt)),
                weather = null, // TODO(T13): ContextProvider
                driveMode = v.driveMode(),
                odometerKm = v.odometerKm(),
            ),
            frameColor = frameColor,
            showStamp = showStamp,
            windowMask = windowMask(),
            sourceKinds = kinds,
        )
    }

    /** preview = true 면 절반 해상도로 빠르게. */
    suspend fun render(layout: Layout, cuts: List<Cut>, opts: RenderOptions, preview: Boolean): Bitmap {
        val r = renderers[layout] ?: error("${layout.label} 레이아웃은 아직 준비 중이에요")
        val imgs = cuts.take(r.maxCuts).map { images(it, if (preview) 2 else 1) }
        return r.render(imgs, opts, if (preview) 0.5f else 1f)
    }

    /** 앱 폴더(sessions/{id}/results)와 갤러리(Pictures/Cha-z)에 저장. */
    suspend fun save(sessionId: String, layout: Layout, bitmap: Bitmap, opts: RenderOptions): Result = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val id = "$sessionId-${layout.id}-$stamp"
        val dir = File(context.filesDir, "sessions/$sessionId/results").apply { mkdirs() }
        val file = File(dir, "$id.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }

        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "chaz_$id.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cha-z")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { u -> context.contentResolver.openOutputStream(u)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        }

        val result = Result(id, listOf(sessionId), layout, file.absolutePath, emptyList(), opts.sourceKinds)
        File(dir, "$id.json").writeText(
            JSONObject()
                .put("id", id).put("sessionIds", JSONArray(result.sessionIds)).put("layout", layout.name)
                .put("kind", "PHOTO").put("path", file.name).put("filters", JSONArray())
                .put("sourceKinds", JSONArray(result.sourceKinds.map { it.name })).put("createdOn", result.createdOn)
                .toString(2),
        )
        result
    }
}
