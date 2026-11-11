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
 * L1 오픈카(구 차창 합성, SPEC F5) — 마스크에 cabin 다각형이 있으면 openTop(), 없으면 아래 창문 교체:
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

            if (mask.cabin != null) openTop(canvas, interior, cut, mask, person, sx, sy, opts)
            else for (win in mask.windows) {
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
                val windowMask = maskFor(win, w, h, path)
                lc.drawBitmap(windowMask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                canvas.drawBitmap(layer, 0f, 0f, null)
                layer.recycle()
            }

            // 인물 레이어를 맨 위에 (오픈카는 openTop 안에서 이미 살림)
            if (person != null && mask.cabin == null) {
                // JPEG 에서 온 비트맵은 hasAlpha=false 라 투명 부분이 검게 그려진다 → 켜 준다
                val p = interior.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(true) }
                Canvas(p).drawBitmap(person.alpha, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                canvas.drawBitmap(p, 0f, 0f, null)
                p.recycle()
            }

            opts.filter.colorFilter()?.let { cf ->
                val filtered = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
                Canvas(filtered).drawBitmap(photo, 0f, 0f, Paint().apply { colorFilter = cf })
                Canvas(photo).drawBitmap(filtered, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
                filtered.recycle()
            }
            val framed = frame(photo, opts)
            photo.recycle()
            if (scale < 1f) Bitmap.createScaledBitmap(framed, (framed.width * scale).toInt(), (framed.height * scale).toInt(), true) else framed
        }
    }

    /**
     * 오픈카: 바탕 = 후방 풍경(수평선을 뒷유리 높이에), 그 위에 실내를 '남길 마스크'(좌석 다각형 ∪ 인물)로 올린다.
     * 지붕·기둥·창이 통째로 사라져 뚜껑 연 차에서 찍은 사진처럼 된다. 하늘 자리에 작은 캡션.
     */
    private fun openTop(canvas: Canvas, interior: Bitmap, cut: CutImages, mask: WindowMask, person: PersonMask?, sx: Float, sy: Float, opts: RenderOptions) {
        val w = interior.width; val h = interior.height
        val ext = cut.rear ?: cut.front
        if (ext != null) {
            // 수평선(외부 프레임 높이의 42%)을 horizonY 에 맞추고, 위쪽이 비지 않게 필요하면 더 키운다
            val hy = (mask.horizonY ?: mask.imageHeight * 0.4f) * sy
            val k = maxOf(w / ext.width.toFloat(), hy / (ext.height * 0.42f), h / ext.height.toFloat())
            val bw = ext.width * k; val bh = ext.height * k
            val top = (hy - bh * 0.42f).coerceAtMost(0f)
            canvas.drawColor(0xFF000000.toInt())
            canvas.drawBitmap(ext, null, RectF((w - bw) / 2, top, (w + bw) / 2, top + bh), Draw.filterPaint())
        }
        // 남길 마스크 = 흐린 좌석 다각형 ∪ 인물 알파
        val keep = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val kc = Canvas(keep)
        kc.drawBitmap(cabinMask(mask, w, h, sx, sy), 0f, 0f, null)
        person?.let { kc.drawBitmap(it.alpha, 0f, 0f, null) }
        val inner = interior.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(true) }
        val ic = Canvas(inner)
        ic.drawBitmap(keep, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        keep.recycle()
        // 실내를 바깥 햇빛 쪽 톤으로 (지붕이 열렸으니 더 밝고 풍경 색을 띤다)
        val paint = Draw.filterPaint()
        if (ext != null) {
            val tone = Draw.toneMatch(Draw.meanColor(interior, step = 16), Draw.meanColor(ext, step = 16), strength = 0.35f)
            tone.postConcat(ColorMatrix().apply { setScale(1.10f, 1.08f, 1.05f, 1f) })
            paint.colorFilter = ColorMatrixColorFilter(tone)
        }
        canvas.drawBitmap(inner, 0f, 0f, paint)
        inner.recycle()

        Draw.vignette(canvas, RectF(0f, 0f, w.toFloat(), h.toFloat()), 0.35f)
        Draw.grain(canvas, RectF(0f, 0f, w.toFloat(), h.toFloat()), w / 1376f, alpha = 30)
        // 하늘 자리 캡션
        val caption = opts.stamp.place ?: "오늘의 드라이브"
        val p = Draw.hand(0xF2FFFFFF.toInt(), h * 0.065f).apply { setShadowLayer(h * 0.01f, 0f, 0f, 0x66000000) }
        canvas.drawText(caption, (w - p.measureText(caption)) / 2, (mask.horizonY ?: mask.imageHeight * 0.4f) * sy * 0.42f, p)
        Draw.dateStamp(canvas, Draw.filmDate(opts.stamp.dateText), w - w * 0.03f, h - h * 0.05f, h * 0.045f)
    }

    private fun cabinMask(mask: WindowMask, w: Int, h: Int, sx: Float, sy: Float): Bitmap = synchronized(maskCache) {
        maskCache.getOrPut("cabin@${w}x$h") {
            val pts = mask.cabin!!
            val path = Path().apply {
                moveTo(pts[0].first * sx, pts[0].second * sy)
                for (i in 1 until pts.size) lineTo(pts[i].first * sx, pts[i].second * sy)
                close()
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF000000.toInt()
                    maskFilter = BlurMaskFilter(2.5f * (w / 1376f), BlurMaskFilter.Blur.NORMAL)
                })
            }
        }
    }

    /** 흐린 창 모양 마스크는 크기·창마다 고정이라 캐시한다 (라이브 미리보기에서 매 프레임 다시 만들지 않게). */
    private val maskCache = HashMap<String, Bitmap>()

    private fun maskFor(win: WindowQuad, w: Int, h: Int, path: Path): Bitmap = synchronized(maskCache) {
        maskCache.getOrPut("${win.name}@${w}x$h") {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF000000.toInt()
                    maskFilter = BlurMaskFilter(FEATHER_PX * (w / 1376f), BlurMaskFilter.Blur.NORMAL)
                })
            }
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
