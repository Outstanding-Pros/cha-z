package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * L1 차창 합성형 (SPEC F5):
 * 1. 바탕 = 실내 컷 원본  2. 창문 영역 = 정적 마스크(assets/masks/{cameraId}_windows.json)
 * 3. 외부 프레임을 창문 사각형에 원근 변환, 가장자리 8px 페더, 실내 밝기에 맞춰 노출 보정
 * 4. 인물 마스크로 원본 인물을 맨 위에 다시 올려 창문 앞 머리카락·어깨를 살린다
 * 5. 얇은 필름 테두리 + 하단 차량 데이터 스탬프
 */
class WindowSwapRenderer(private val segmenter: PersonSegmenter) : LayoutRenderer {
    override val layout = Layout.WINDOW_SWAP
    override val maxCuts = 1

    override suspend fun render(cuts: List<CutImages>, opts: RenderOptions, scale: Float): Bitmap {
        val cut = cuts.first()
        val interior = cut.interior ?: error("실내 컷이 없어요")
        val mask = opts.windowMask ?: error("이 실내 카메라의 창문 마스크가 없어요")
        val person = segmenter.segment(interior)
        return withContext(Dispatchers.Default) {
            val w = interior.width
            val h = interior.height
            val photo = interior.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(photo)
            val sx = w / mask.imageWidth.toFloat()
            val sy = h / mask.imageHeight.toFloat()

            for (win in mask.windows) {
                val ext = cut.exterior(win.source) ?: continue
                val dst = win.points.flatMap { (x, y) -> listOf(x * sx, y * sy) }.toFloatArray()
                val path = Path().apply {
                    moveTo(dst[0], dst[1]); for (i in 1 until 4) lineTo(dst[i * 2], dst[i * 2 + 1]); close()
                }
                val bounds = RectF().also { path.computeBounds(it, true) }
                // 창 비율에 맞춰 외부 프레임 가운데(지평선 약간 위)를 잘라 사각형에 원근 변환
                val src = Draw.crop(ext.width, ext.height, bounds.width() / bounds.height().coerceAtLeast(1f), 0.42f)
                val srcPts = floatArrayOf(
                    src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.top.toFloat(),
                    src.right.toFloat(), src.bottom.toFloat(), src.left.toFloat(), src.bottom.toFloat(),
                )
                val m = Matrix().apply { setPolyToPoly(srcPts, 0, dst, 0, 4) }

                // 노출 보정: 실내 창 밝기 쪽으로 절반만 맞춘다 (창은 원래 밝게 날아가 있음)
                val li = Draw.luminance(interior, Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
                val le = Draw.luminance(ext, src)
                val f = sqrt(li / le.coerceAtLeast(1f)).coerceIn(0.85f, 1.3f)

                val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val lc = Canvas(layer)
                lc.drawBitmap(ext, m, Draw.filterPaint().apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(f, f, f, 1f) })
                })
                // 가장자리 페더: 흐린 창 모양 마스크(전체 크기)로 알파를 깎는다.
                // 경로만 DST_IN 으로 그리면 경로 밖에 넘친 변환 이미지가 남으므로 전체 크기 마스크를 쓴다.
                val windowMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(windowMask).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF000000.toInt()
                    maskFilter = BlurMaskFilter(FEATHER_PX * (w / 1376f), BlurMaskFilter.Blur.NORMAL)
                })
                lc.drawBitmap(windowMask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                windowMask.recycle()
                canvas.drawBitmap(layer, 0f, 0f, null)
                layer.recycle()
            }

            // 인물 레이어를 맨 위에
            if (person != null) {
                // JPEG 에서 온 비트맵은 hasAlpha=false 라 투명 부분이 검게 그려진다 → 켜 준다
                val p = interior.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(true) }
                Canvas(p).drawBitmap(person.alpha, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                canvas.drawBitmap(p, 0f, 0f, null)
                p.recycle()
            }

            val framed = frame(photo, opts)
            photo.recycle()
            if (scale < 1f) Bitmap.createScaledBitmap(framed, (framed.width * scale).toInt(), (framed.height * scale).toInt(), true) else framed
        }
    }

    /** 얇은 필름 테두리 + 하단 스탬프 띠. */
    private fun frame(photo: Bitmap, opts: RenderOptions): Bitmap {
        val c = opts.frameColor
        val border = (photo.width * 0.022f).toInt()
        val band = if (opts.showStamp) (photo.width * 0.085f).toInt() else border
        val out = Bitmap.createBitmap(photo.width + border * 2, photo.height + border + band, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(c.bg)
        cv.drawBitmap(photo, border.toFloat(), border.toFloat(), null)
        if (opts.showStamp) {
            val y0 = photo.height + border
            val title = Draw.text(c.fg, band * 0.36f, bold = true)
            cv.drawText("CHA~Z", border.toFloat(), y0 + band * 0.58f, title)
            val info = Draw.text(c.fg, band * 0.24f)
            val line = Draw.stampLine(opts.stamp)
            cv.drawText(line, out.width - border - info.measureText(line), y0 + band * 0.50f, info)
            opts.simulationNote?.let { note ->
                val sub = Draw.text(c.sub, band * 0.18f)
                cv.drawText(note, out.width - border - sub.measureText(note), y0 + band * 0.80f, sub)
            }
        }
        return out
    }

    companion object { const val FEATHER_PX = 8f }
}
