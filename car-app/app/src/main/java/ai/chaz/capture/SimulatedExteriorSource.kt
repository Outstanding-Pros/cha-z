package ai.chaz.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * '사전 녹화 주행 영상' 후보의 이미지 판(assets/exterior-mock/front_*.jpg 등).
 * 정지 이미지를 천천히 줌·패닝해 앞으로 달리는 느낌을 낸다. 같은 함수(KenBurns.window)로
 * 미리보기와 촬영 프레임을 만들어서, 미리보기에서 본 장면과 찍힌 장면이 같다.
 */
class SimulatedExteriorSource(
    private val appContext: Context,
    private val assetPath: String,
    override val role: CameraRole,
    override val label: String,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) : CameraSource {
    override val kind = SourceKind.RECORDED_VIDEO

    private val image: Bitmap by lazy {
        appContext.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            ?: error("이미지를 읽을 수 없음: $assetPath")
    }

    override fun start() = Unit
    override fun stop() = Unit

    override fun createPreview(context: Context, owner: LifecycleOwner): View =
        PanView(context)

    override fun releasePreview(view: View) = Unit

    override suspend fun capture(): Bitmap = render(OUT_W, OUT_H)

    override suspend fun snapshot(): Bitmap = render(SNAP_W, SNAP_H)

    private suspend fun render(w: Int, h: Int): Bitmap = withContext(Dispatchers.Default) {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(image, KenBurns.window(image.width, image.height, clock()).toRect(), Rect(0, 0, w, h), PAINT)
        out
    }

    private inner class PanView(context: Context) : View(context) {
        private val dst = Rect()
        override fun onDraw(canvas: Canvas) {
            val src = KenBurns.window(image.width, image.height, clock()).toRect()
            // 화면 비율에 맞게 가운데 기준으로 채운다 (FILL_CENTER)
            val viewRatio = width.toFloat() / height.coerceAtLeast(1)
            val srcRatio = src.width().toFloat() / src.height()
            if (viewRatio > srcRatio) {
                val h = (src.width() / viewRatio).toInt()
                src.top += (src.height() - h) / 2; src.bottom = src.top + h
            } else {
                val w = (src.height() * viewRatio).toInt()
                src.left += (src.width() - w) / 2; src.right = src.left + w
            }
            dst.set(0, 0, width, height)
            canvas.drawBitmap(image, src, dst, PAINT)
            postInvalidateOnAnimation()
        }
    }

    companion object {
        const val OUT_W = 1280
        const val OUT_H = 720
        const val SNAP_W = 640
        const val SNAP_H = 360
        private val PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}

/** 원본 이미지 좌표의 사각형 (android.graphics.Rect 없이 단위 테스트하려고 따로 둔다). */
data class PanWindow(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun toRect() = Rect(left, top, right, bottom)
}

/** 순수 계산: 시각 t(ms)에서 원본 이미지 중 보여 줄 영역. 단위 테스트 대상. */
object KenBurns {
    const val PERIOD_MS = 8_000L
    private const val ZOOM_START = 1.0f
    private const val ZOOM_END = 1.18f

    fun window(imgW: Int, imgH: Int, tMs: Long): PanWindow {
        val p = (tMs % PERIOD_MS).toFloat() / PERIOD_MS // 0..1 반복: 소실점 쪽으로 서서히 다가간다
        val zoom = ZOOM_START + (ZOOM_END - ZOOM_START) * p
        val w = (imgW / zoom).toInt()
        val h = (imgH / zoom).toInt()
        val left = (imgW - w) / 2
        val top = ((imgH - h) * 0.45f).toInt() // 지평선이 약간 위에 있어서 위쪽으로 치우쳐 확대
        return PanWindow(left, top, left + w, top + h)
    }
}
