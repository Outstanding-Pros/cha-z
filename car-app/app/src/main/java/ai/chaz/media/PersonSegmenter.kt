package ai.chaz.media

import ai.chaz.platform.ProbeResult
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * 인물 마스크. alpha 가 인물일 정도(0~255)인 비트맵(색은 무시), 원본과 같은 크기.
 * bbox 는 가장 큰 인물 덩어리의 경계 — L2 누끼 배치에 쓴다 (좌석 같은 잡음 덩어리는 뺀다).
 */
class PersonMask(val alpha: Bitmap, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty get() = right <= left || bottom <= top
}

interface PersonSegmenter {
    val name: String
    /** 인물이 없거나 실패하면 null. */
    suspend fun segment(image: Bitmap): PersonMask?
}

/** 테스트·대체용: 인물 분리 없음 (L1 은 인물 레이어 없이, L2 는 실내 사진을 작은 창으로). */
class FakePersonSegmenter : PersonSegmenter {
    override val name = "none"
    override suspend fun segment(image: Bitmap): PersonMask? = null
}

/** 순수 계산: 확률 → 알파 곡선, 가장 큰 덩어리 경계. 단위 테스트 대상. */
object MaskMath {
    /** 0.5 아래는 0, 0.85 위는 1, 사이는 부드럽게 (좌석·창의 애매한 값 제거). */
    fun alpha(p: Float): Int {
        val t = ((p - LOW) / (HIGH - LOW)).coerceIn(0f, 1f)
        return (t * t * (3 - 2 * t) * 255).toInt()
    }

    /**
     * person[y*w+x] (0~1)을 step 간격으로 훑어 threshold 이상 칸의 4방향 연결 덩어리 중 가장 큰 것의 경계.
     * 반환: [left, top, right, bottom] (원본 좌표) 또는 null.
     */
    fun largestBlob(person: FloatArray, w: Int, h: Int, step: Int = 4, threshold: Float = 0.8f): IntArray? {
        val gw = (w + step - 1) / step
        val gh = (h + step - 1) / step
        val on = BooleanArray(gw * gh) { i -> person[minOf((i / gw) * step, h - 1) * w + minOf((i % gw) * step, w - 1)] >= threshold }
        val seen = BooleanArray(on.size)
        var best: IntArray? = null
        var bestSize = 0
        val stack = IntArray(on.size)
        for (start in on.indices) {
            if (!on[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var size = 0; var l = gw; var t = gh; var r = -1; var b = -1
            while (sp > 0) {
                val i = stack[--sp]
                size++
                val x = i % gw; val y = i / gw
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                if (x > 0 && on[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < gw - 1 && on[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && on[i - gw] && !seen[i - gw]) { seen[i - gw] = true; stack[sp++] = i - gw }
                if (y < gh - 1 && on[i + gw] && !seen[i + gw]) { seen[i + gw] = true; stack[sp++] = i + gw }
            }
            if (size > bestSize) { bestSize = size; best = intArrayOf(l * step, t * step, minOf((r + 1) * step, w), minOf((b + 1) * step, h)) }
        }
        return best
    }

    fun alphaF(p: Float): Float {
        val t = ((p - LOW) / (HIGH - LOW)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /**
     * 누끼 다듬기 (모델 해상도에서): 알파 곡선 → 가장 큰 덩어리만 남김(떠 있는 핸들 조각·좌석 잡음 제거)
     * → 1칸 침식(창문 빛이 번진 테두리 제거) → 박스 블러 2회(가장자리 페더). 반환은 0~1 알파.
     */
    fun refine(person: FloatArray, w: Int, h: Int): FloatArray {
        val a = FloatArray(w * h) { alphaF(person[it]) }
        keepLargest(a, w, h, 0.1f)
        return boxBlur(boxBlur(erode(a, w, h), w, h), w, h)
    }

    /** threshold 이상인 4방향 연결 덩어리 중 가장 큰 것만 남기고 나머지는 0으로. */
    fun keepLargest(a: FloatArray, w: Int, h: Int, threshold: Float) {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        var next = 0; var best = 0; var bestSize = 0
        for (s in a.indices) {
            if (a[s] < threshold || label[s] != 0) continue
            next++
            var sp = 0; stack[sp++] = s; label[s] = next
            var size = 0
            while (sp > 0) {
                val i = stack[--sp]; size++
                val x = i % w
                fun push(j: Int) { if (a[j] >= threshold && label[j] == 0) { label[j] = next; stack[sp++] = j } }
                if (x > 0) push(i - 1)
                if (x < w - 1) push(i + 1)
                if (i >= w) push(i - w)
                if (i < w * (h - 1)) push(i + w)
            }
            if (size > bestSize) { bestSize = size; best = next }
        }
        for (i in a.indices) if (label[i] != best) a[i] = 0f
    }

    fun erode(a: FloatArray, w: Int, h: Int): FloatArray = FloatArray(w * h) { i ->
        val x = i % w; val y = i / w
        var m = a[i]
        for (dy in -1..1) for (dx in -1..1) {
            val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
            m = minOf(m, a[yy * w + xx])
        }
        m
    }

    /** 반경 1 분리형 박스 블러. */
    fun boxBlur(a: FloatArray, w: Int, h: Int): FloatArray {
        val t = FloatArray(w * h) { i ->
            val x = i % w
            (a[i - x + (x - 1).coerceAtLeast(0)] + a[i] + a[i - x + (x + 1).coerceAtMost(w - 1)]) / 3f
        }
        return FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            (t[(y - 1).coerceAtLeast(0) * w + x] + t[i] + t[(y + 1).coerceAtMost(h - 1) * w + x]) / 3f
        }
    }

    private const val LOW = 0.5f
    private const val HIGH = 0.85f
}

/**
 * 1순위: MediaPipe Image Segmenter, selfie_multiclass_256x256 (온디바이스).
 * 클래스 0 = 배경 → 인물 확률 = 1 - 배경. (selfie_segmenter 는 넓은 실내에서 좌석까지 인물로 잡아 교체함 — assets/models/LICENSES.md)
 */
class MediaPipePersonSegmenter(private val context: Context) : PersonSegmenter {
    override val name = "mediapipe-selfie-multiclass"
    private val lock = Mutex()
    private var segmenter: ImageSegmenter? = null

    private fun ensure(): ImageSegmenter = segmenter ?: create(context).also { segmenter = it }

    override suspend fun segment(image: Bitmap): PersonMask? = lock.withLock {
        withContext(Dispatchers.Default) {
            try {
                val src = if (image.config == Bitmap.Config.ARGB_8888) image else image.copy(Bitmap.Config.ARGB_8888, false)
                val result = ensure().segment(BitmapImageBuilder(src).build())
                val bg = result.confidenceMasks().orElse(null)?.firstOrNull() ?: return@withContext null
                val w = bg.width
                val h = bg.height
                val fb = ByteBufferExtractor.extract(bg).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val person = FloatArray(w * h) { 1f - fb.get(it) }
                // ALPHA_8 + DST_IN 조합이 기기마다 다르게 그려져서, 알파만 쓴 ARGB 마스크로 만든다
                val soft = MaskMath.refine(person, w, h)
                val pixels = IntArray(w * h) { (soft[it] * 255).toInt().coerceIn(0, 255) shl 24 }
                val box = MaskMath.largestBlob(person, w, h) ?: return@withContext null
                val alpha = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                val scaled = if (w == image.width && h == image.height) alpha else Bitmap.createScaledBitmap(alpha, image.width, image.height, true)
                val sx = image.width.toFloat() / w
                val sy = image.height.toFloat() / h
                PersonMask(scaled, (box[0] * sx).toInt(), (box[1] * sy).toInt(), (box[2] * sx).toInt(), (box[3] * sy).toInt())
                    .takeUnless { it.isEmpty }
            } catch (e: Exception) {
                Log.w("ChazSegment", "인물 분리 실패: ${e.message}")
                null
            }
        }
    }

    companion object {
        const val MODEL = "models/selfie_multiclass_256x256.tflite"

        private fun create(context: Context): ImageSegmenter = ImageSegmenter.createFromOptions(
            context.applicationContext,
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build(),
        )

        fun probe(context: Context): ProbeResult = try {
            create(context).close()
            ProbeResult.Ok("selfie_multiclass 로딩 성공 (온디바이스)")
        } catch (e: Throwable) {
            ProbeResult.Fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
