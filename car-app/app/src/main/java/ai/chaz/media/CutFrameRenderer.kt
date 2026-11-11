package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L2 컷형 프레임 (SPEC F5) — 인생네컷식 세로 2~4칸. 프레임 디자인은 자체 제작.
 *
 * '합성 사진'이 아니라 '필름으로 찍은 사진'처럼 보이게 하는 것이 목표:
 *  - 배경(외부 풍경, 홀수 칸 전방·짝수 칸 후방)은 살짝 흐리게 → 얕은 심도, 인물이 떠 보이지 않는다.
 *  - 인물은 가슴 위만 크게, 칸 아래 가장자리에 붙인다 → 핸들·레버 조각과 잘린 아랫단이 칸 밖으로 빠진다.
 *  - 칸마다 톤 맞춤·라이트 랩 → 비네팅 → 필름 입자 → 주황 날짜 각인으로 칸 전체를 한 장의 사진으로 묶는다.
 *  - 프레임: FILM = 35mm 필름 스트립(구멍·가장자리 글씨), 나머지 = 종이 프레임 + 손글씨 캡션.
 */
class CutFrameRenderer(private val segmenter: PersonSegmenter) : LayoutRenderer {
    override val layout = Layout.CUT_FRAME
    override val maxCuts = 4

    override suspend fun render(cuts: List<CutImages>, opts: RenderOptions, scale: Float): Bitmap {
        require(cuts.isNotEmpty()) { "컷을 하나 이상 골라 주세요" }
        val masks = cuts.map { c -> c.interior?.let { segmenter.segment(it) } }
        return withContext(Dispatchers.Default) {
            val s = scale
            val c = opts.frameColor
            val film = c == FrameColor.FILM
            val width = (1200 * s).toInt()
            val side = (if (film) 118 else 64) * s
            val top = (if (film) 84 else 64) * s
            val gap = (if (film) 26 else 18) * s
            val bottom = when {
                !opts.showStamp -> if (film) 84 else 64
                film -> 178
                else -> 236
            } * s
            val cellW = width - side * 2
            val cellH = cellW * 9f / 16f
            val height = (top + cuts.size * cellH + (cuts.size - 1) * gap + bottom).toInt()

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            cv.drawColor(c.bg)
            if (!film) Draw.grain(cv, RectF(0f, 0f, width.toFloat(), height.toFloat()), s, alpha = 22) // 종이 질감

            val date = Draw.filmDate(opts.stamp.dateText)
            val radius = if (film) 3 * s else 8 * s
            cuts.forEachIndexed { i, cut ->
                val cell = RectF(side, top + i * (cellH + gap), side + cellW, top + i * (cellH + gap) + cellH)
                drawCell(cv, cut, masks[i], cell, i, opts, s, radius)
                Draw.dateStamp(cv, date, cell.right - 22 * s, cell.bottom - 20 * s, 30 * s)
            }

            if (film) drawFilmStrip(cv, width.toFloat(), height.toFloat(), side, top, cellH, gap, cuts, s)
            if (opts.showStamp) drawCaption(cv, opts, width.toFloat(), height - bottom, s, film)
            out
        }
    }

    private fun drawCell(cv: Canvas, cut: CutImages, mask: PersonMask?, cell: RectF, i: Int, opts: RenderOptions, s: Float, radius: Float) {
        // 칸 내용을 필터 레이어에 그린다 (프레임 색은 필터를 받지 않게)
        cv.saveLayer(cell, Paint().apply { colorFilter = opts.filter.colorFilter() })
        cv.clipPath(Path().apply { addRoundRect(cell, radius, radius, Path.Direction.CW) })
        val bg = if (i % 2 == 0) (cut.front ?: cut.rear) else (cut.rear ?: cut.front)
        val interior = cut.interior
        val person = interior != null && mask != null && mask.right > mask.left && mask.bottom > mask.top
        if (bg != null) {
            if (person) {
                val soft = Draw.soften(bg, 5)
                Draw.drawCover(cv, soft, cell, 0.45f)
                soft.recycle()
            } else Draw.drawCover(cv, bg, cell, 0.45f)
        } else cv.drawColor(opts.frameColor.sub)
        if (person) drawBust(cv, interior!!, mask!!, cell, leftSide = i % 2 == 1, bg = bg)
        else if (interior != null) drawInset(cv, interior, cell, opts.frameColor.bg, s)
        Draw.vignette(cv, cell, 0.5f)
        Draw.grain(cv, cell, s)
        cv.restore()
    }

    /**
     * 인물 누끼에서 가슴 위(마스크 위쪽 52%)만 잘라 칸 높이의 88%로 키우고, 칸 아래 가장자리에 붙인다.
     * 잘린 선은 칸 밖으로 나가서 보이지 않고, 핸들을 쥔 손·핸들 조각은 대부분 잘려 나간다.
     */
    private fun drawBust(cv: Canvas, interior: Bitmap, mask: PersonMask, cell: RectF, leftSide: Boolean, bg: Bitmap?) {
        val mh = mask.bottom - mask.top
        val mw = mask.right - mask.left
        val pad = (mh * 0.05f).toInt()
        val sidePad = (mw * 0.10f).toInt() // 흐린 가장자리가 상자에 잘려 직선이 생기지 않게
        val bustBottom = (mask.top + mh * 0.52f).toInt()
        val box = Rect(
            (mask.left - sidePad).coerceAtLeast(0), (mask.top - pad).coerceAtLeast(0),
            (mask.right + sidePad).coerceAtMost(interior.width), bustBottom.coerceIn(mask.top + 1, interior.height),
        )
        val cut = Bitmap.createBitmap(interior, box.left, box.top, box.width(), box.height()).copy(Bitmap.Config.ARGB_8888, true)
            .apply { setHasAlpha(true) } // JPEG 원본은 hasAlpha=false → 누끼 바깥이 검게 나오지 않게
        val alpha = erode(Bitmap.createBitmap(mask.alpha, box.left, box.top, box.width(), box.height()))
        val cc = Canvas(cut)
        cc.drawBitmap(alpha, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        // 아래 18%는 서서히 투명하게 — 칸 아래로 빠지기 전 잘린 단면(손·핸들 윗부분)이 딱딱하지 않게
        val fadeTop = box.height() * 0.82f
        cc.drawRect(0f, fadeTop, box.width().toFloat(), box.height().toFloat(), Paint().apply {
            shader = LinearGradient(0f, fadeTop, 0f, box.height().toFloat(), 0xFF000000.toInt(), 0x40000000, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })

        var h = cell.height() * 0.88f
        var w = h * box.width() / box.height()
        if (w > cell.width() * 0.62f) { w = cell.width() * 0.62f; h = w * box.height() / box.width() }
        val cx = cell.centerX() + (if (leftSide) -1 else 1) * cell.width() * 0.15f
        val overflow = h * 0.03f
        val dst = RectF(cx - w / 2, cell.bottom - h + overflow, cx + w / 2, cell.bottom + overflow)
        val bgMean = bg?.let { Draw.meanColor(it, step = 12) }
        val paint = Draw.filterPaint()
        if (bgMean != null) paint.colorFilter = ColorMatrixColorFilter(Draw.toneMatch(Draw.meanColor(cut, step = 8), bgMean, strength = 0.6f))
        cv.drawBitmap(cut, null, dst, paint)
        if (bgMean != null) Draw.lightWrap(cv, cut, dst, bgMean, radius = box.width() * 0.025f, alpha = 110)
        cut.recycle()
    }

    /** 마스크의 반투명 가장자리를 깎는다 (배경·창틀 색이 머리·볼 옆에 흰 테두리로 남지 않게). */
    private fun erode(alpha: Bitmap): Bitmap {
        // 마스크는 알파만 쓰는 ARGB (ALPHA_8 + DST_IN 은 기기마다 달라서 — PersonSegmenter 참고)
        val w = alpha.width; val h = alpha.height
        val px = IntArray(w * h)
        alpha.getPixels(px, 0, w, 0, 0, w, h)
        for (k in px.indices) {
            val a = ((((px[k] ushr 24) and 0xFF) - 55) * 255 / 165).coerceIn(0, 255)
            px[k] = (a shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 인물 분리를 못 쓰면 실내 사진을 오른쪽 아래 작은 창으로. */
    private fun drawInset(cv: Canvas, interior: Bitmap, cell: RectF, borderColor: Int, s: Float) {
        val w = cell.width() * 0.42f
        val h = w * 9f / 16f
        val dst = RectF(cell.right - w - 14 * s, cell.bottom - h - 64 * s, cell.right - 14 * s, cell.bottom - 64 * s)
        cv.drawRoundRect(RectF(dst.left - 4 * s, dst.top - 4 * s, dst.right + 4 * s, dst.bottom + 4 * s), 6 * s, 6 * s, Paint().apply { color = borderColor })
        Draw.drawCover(cv, interior, dst)
    }

    /** 35mm 필름 스트립: 양옆 구멍 + 호박색 가장자리 글씨(필름 이름·컷 번호). */
    private fun drawFilmStrip(cv: Canvas, width: Float, height: Float, side: Float, top: Float, cellH: Float, gap: Float, cuts: List<CutImages>, s: Float) {
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9E2D3.toInt() }
        val hw = 30 * s; val hh = 22 * s; val step = 52 * s
        val xs = listOf(side * 0.30f, width - side * 0.30f)
        var y = 18 * s
        while (y + hh < height - 10 * s) {
            xs.forEach { x -> cv.drawRoundRect(RectF(x - hw / 2, y, x + hw / 2, y + hh), 5 * s, 5 * s, hole) }
            y += step
        }
        val amber = Draw.text(FrameColor.FILM.sub, 20 * s, bold = true).apply { letterSpacing = 0.12f }
        val inner = listOf(side * 0.72f, width - side * 0.72f)
        cuts.forEachIndexed { i, cut ->
            val cy = top + i * (cellH + gap) + cellH / 2
            Draw.rotatedText(cv, "CHA~Z 400", inner[0], cy - cellH * 0.22f, -90f, amber)
            Draw.rotatedText(cv, "▶ ${cut.index}", inner[0], cy + cellH * 0.3f, -90f, amber)
            Draw.rotatedText(cv, "${cut.index}A", inner[1], cy, 90f, amber)
        }
    }

    /** 아래 캡션: 손글씨 장소명(없으면 '오늘의 드라이브') + 작은 정보 줄 + CHA~Z 로고. */
    private fun drawCaption(cv: Canvas, opts: RenderOptions, width: Float, y0: Float, s: Float, film: Boolean) {
        val c = opts.frameColor
        val caption = opts.stamp.place ?: "오늘의 드라이브"
        val hand = Draw.hand(c.fg, (if (film) 58 else 72) * s)
        val left = if (film) 118 * s else 64 * s
        val base = y0 + (if (film) 74 else 92) * s
        cv.drawText(caption, left, base, hand)
        val info = Draw.text(if (film) c.sub else c.sub, 21 * s).apply { letterSpacing = 0.04f }
        cv.drawText(Draw.stampLine(opts.stamp), left, base + 44 * s, info)
        opts.simulationNote?.let { n -> cv.drawText(n, left, base + 76 * s, Draw.text(c.sub, 17 * s)) }
        val logo = Draw.text(if (film) c.sub else c.fg, 40 * s, bold = true).apply { letterSpacing = 0.06f }
        val t = "CHA~Z"
        cv.drawText(t, width - left - logo.measureText(t), base, logo)
    }
}
