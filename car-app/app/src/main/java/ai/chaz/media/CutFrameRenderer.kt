package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L2 컷형 프레임 (SPEC F5) — 인생네컷식 세로 2칸 또는 4칸.
 * 칸 배경 = 외부 풍경(홀수 칸 전방, 짝수 칸 후방), 인물 = 그 컷 실내 사진의 누끼,
 * 양옆 세로 텍스트(장소명·날짜 / 'CHA~Z'·컷 번호), 하단 스탬프. 프레임 디자인은 자체 제작.
 */
class CutFrameRenderer(private val segmenter: PersonSegmenter) : LayoutRenderer {
    override val layout = Layout.CUT_FRAME
    override val maxCuts = 4

    override suspend fun render(cuts: List<CutImages>, opts: RenderOptions, scale: Float): Bitmap {
        require(cuts.isNotEmpty()) { "컷을 하나 이상 골라 주세요" }
        val masks = cuts.map { c -> c.interior?.let { segmenter.segment(it) } }
        return withContext(Dispatchers.Default) {
            val s = scale
            val width = (1200 * s).toInt()
            val side = 120 * s
            val top = 70 * s
            val gap = 22 * s
            val bottom = (if (opts.showStamp) 190 else 70) * s
            val cellW = width - side * 2
            val cellH = cellW * 9f / 16f
            val height = (top + cuts.size * cellH + (cuts.size - 1) * gap + bottom).toInt()
            val c = opts.frameColor

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            cv.drawColor(c.bg)
            val radius = 14 * s

            cuts.forEachIndexed { i, cut ->
                val cell = RectF(side, top + i * (cellH + gap), side + cellW, top + i * (cellH + gap) + cellH)
                cv.save()
                cv.clipPath(Path().apply { addRoundRect(cell, radius, radius, Path.Direction.CW) })
                // 배경: 외부 풍경
                val bg = if (i % 2 == 0) (cut.front ?: cut.rear) else (cut.rear ?: cut.front)
                if (bg != null) Draw.drawCover(cv, bg, cell, 0.45f) else cv.drawColor(c.sub)
                // 인물
                val interior = cut.interior
                val mask = masks[i]
                if (interior != null && mask != null) drawCutout(cv, interior, mask, cell, leftSide = i % 2 == 1)
                else if (interior != null) drawInset(cv, interior, cell, c.bg, s)
                cv.restore()
                // 컷 번호
                cv.drawText("#${cut.index}", cell.left + 14 * s, cell.top + 34 * s, Draw.text(0xFFFFFFFF.toInt(), 24 * s, bold = true).apply {
                    setShadowLayer(4 * s, 0f, 0f, 0x99000000.toInt())
                })
            }

            // 양옆 세로 텍스트
            val midY = top + (cuts.size * cellH + (cuts.size - 1) * gap) / 2
            val leftText = listOfNotNull(opts.stamp.place, opts.stamp.dateText).joinToString("   ")
            Draw.rotatedText(cv, leftText, side / 2, midY, -90f, Draw.text(c.fg, 30 * s, bold = true))
            Draw.rotatedText(cv, "CHA~Z   " + cuts.joinToString(" ") { "#${it.index}" }, width - side / 2, midY, 90f, Draw.text(c.fg, 34 * s, bold = true))

            if (opts.showStamp) {
                val y0 = height - bottom
                val t = Draw.text(c.fg, 46 * s, bold = true)
                val title = "CHA~Z"
                cv.drawText(title, (width - t.measureText(title)) / 2, y0 + 78 * s, t)
                val info = Draw.text(c.sub, 22 * s)
                val line = Draw.stampLine(opts.stamp)
                cv.drawText(line, (width - info.measureText(line)) / 2, y0 + 120 * s, info)
                opts.simulationNote?.let { n ->
                    val p = Draw.text(c.sub, 18 * s)
                    cv.drawText(n, (width - p.measureText(n)) / 2, y0 + 152 * s, p)
                }
            }
            out
        }
    }

    /** 인물 누끼를 칸 높이의 92%로 키워 칸 아래쪽에 세운다. 홀짝 칸마다 좌우를 바꿔 단조롭지 않게. */
    private fun drawCutout(cv: Canvas, interior: Bitmap, mask: PersonMask, cell: RectF, leftSide: Boolean) {
        val pad = ((mask.bottom - mask.top) * 0.04f).toInt()
        val box = Rect(
            (mask.left - pad).coerceAtLeast(0), (mask.top - pad).coerceAtLeast(0),
            (mask.right + pad).coerceAtMost(interior.width), mask.bottom.coerceAtMost(interior.height),
        )
        val cut = Bitmap.createBitmap(interior, box.left, box.top, box.width(), box.height()).copy(Bitmap.Config.ARGB_8888, true)
            .apply { setHasAlpha(true) } // JPEG 원본은 hasAlpha=false → 누끼 바깥이 검게 나오지 않게
        val alpha = Bitmap.createBitmap(mask.alpha, box.left, box.top, box.width(), box.height())
        Canvas(cut).drawBitmap(alpha, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })

        var h = cell.height() * 0.92f
        var w = h * box.width() / box.height()
        if (w > cell.width() * 0.75f) { w = cell.width() * 0.75f; h = w * box.height() / box.width() }
        val cx = cell.centerX() + (if (leftSide) -1 else 1) * cell.width() * 0.16f
        val dst = RectF(cx - w / 2, cell.bottom - h, cx + w / 2, cell.bottom)
        cv.drawBitmap(cut, null, dst, Draw.filterPaint())
        cut.recycle()
    }

    /** 인물 분리를 못 쓰면 실내 사진을 오른쪽 아래 작은 창으로. */
    private fun drawInset(cv: Canvas, interior: Bitmap, cell: RectF, borderColor: Int, s: Float) {
        val w = cell.width() * 0.42f
        val h = w * 9f / 16f
        val dst = RectF(cell.right - w - 14 * s, cell.bottom - h - 14 * s, cell.right - 14 * s, cell.bottom - 14 * s)
        cv.drawRoundRect(RectF(dst.left - 4 * s, dst.top - 4 * s, dst.right + 4 * s, dst.bottom + 4 * s), 10 * s, 10 * s, Paint().apply { color = borderColor })
        Draw.drawCover(cv, interior, dst)
    }
}
