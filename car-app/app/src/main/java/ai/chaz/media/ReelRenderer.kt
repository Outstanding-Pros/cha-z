package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** 릴스 한 편에 들어가는 사진 한 장. */
class ReelPhoto(val bitmap: Bitmap, val ai: Boolean, val caption: String?)

/**
 * 릴스 한 프레임을 1080×1920 좌표로 그린다 (인코더가 실제 크기에 맞춰 canvas 를 줄인다).
 * - 이어붙이기 장면: 흐린 같은 사진을 배경으로 꽉 채우고, 가로 사진 전체를 가운데에 (사람이 잘리지 않게) 천천히 줌인.
 * - 그리드 장면: 핵심 영역(약 950×980) 안에 1~4칸, 흰 테두리.
 * - 공통: 위 안전 영역 아래 제목, 아래 안전 영역 위 '차~즈로 찍음', AI 사진이 보이면 오른쪽 위 'AI' 배지.
 */
class ReelRenderer(
    private val photos: List<ReelPhoto>,
    private val motion: ReelMotion,
    private val title: String,
    private val dateText: String,
) {
    private val n = photos.size
    private val blurred: List<Bitmap> = photos.map { blur(it.bitmap) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val W = ReelSpec.W.toFloat()
    private val H = ReelSpec.H.toFloat()
    private val side = ReelSpec.SAFE_SIDE.toFloat()
    private val titleBase = ReelSpec.SAFE_TOP + 70f
    private val bottomSafe = H - ReelSpec.SAFE_BOTTOM
    private val gridArea = ReelBox(side, titleBase + 50f, W - side, bottomSafe - 70f)

    val durationMs: Long get() = ReelTimeline.durationMs(motion, n)

    fun draw(c: Canvas, tMs: Long) {
        val m = ReelTimeline.at(motion, n, tMs)
        val single = m.single
        if (single != null) drawSingle(c, single, m.progress) else drawGrid(c, m)
        // 공통 표기
        drawTitle(c, if (single != null) photos[single].caption ?: title else title)
        if (single != null && n > 1) drawDots(c, single)
        val aiShown = if (single != null) photos[single].ai else photos.any { it.ai }
        if (aiShown) drawAiBadge(c)
        drawMark(c)
    }

    /** 커버: 그리드가 다 보인 장면. */
    fun drawCover(c: Canvas) {
        drawGrid(c, ReelMoment(null, 0f, 1f))
        drawTitle(c, title)
        if (photos.any { it.ai }) drawAiBadge(c)
        drawMark(c)
    }

    private fun drawSingle(c: Canvas, i: Int, p: Float) {
        val bmp = photos[i].bitmap
        background(c, blurred[i])
        val zoom = 1f + 0.08f * p
        val fw = W * zoom
        val fh = fw * bmp.height / bmp.width
        val cy = (gridArea.t + gridArea.b) / 2
        val dst = RectF((W - fw) / 2, cy - fh / 2, (W + fw) / 2, cy + fh / 2)
        // 세로 사진이면 핵심 영역 높이를 넘지 않게 맞춘다
        if (dst.height() > gridArea.h * 1.1f) {
            val k = gridArea.h * 1.1f / dst.height()
            val w2 = dst.width() * k; val h2 = dst.height() * k
            dst.set((W - w2) / 2, cy - h2 / 2, (W + w2) / 2, cy + h2 / 2)
        }
        c.drawBitmap(bmp, null, dst, paint)
    }

    private fun drawGrid(c: Canvas, m: ReelMoment) {
        background(c, blurred.first())
        val zoom = 1f + 0.05f * (if (motion == ReelMotion.GRID) m.progress else 0f)
        val pop = m.reveal
        val s = zoom * (0.85f + 0.15f * ease(pop))
        val cx = W / 2; val cy = (gridArea.t + gridArea.b) / 2
        c.save()
        c.scale(s, s, cx, cy)
        val cells = ReelTimeline.gridCells(n, gridArea, 22f)
        val alpha = (255 * ease(pop)).toInt()
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.alpha = alpha }
        val photoPaint = Paint(paint).apply { this.alpha = alpha }
        cells.forEachIndexed { i, b ->
            val outer = RectF(b.l, b.t, b.r, b.b)
            c.drawRoundRect(outer, 14f, 14f, frame)
            val inner = RectF(outer.left + 10f, outer.top + 10f, outer.right - 10f, outer.bottom - 10f)
            val bmp = photos[i].bitmap
            c.drawBitmap(bmp, Draw.crop(bmp.width, bmp.height, inner.width() / inner.height()), inner, photoPaint)
        }
        c.restore()
    }

    private fun background(c: Canvas, blur: Bitmap) {
        c.drawColor(Color.BLACK)
        Draw.drawCover(c, blur, RectF(0f, 0f, W, H))
        c.drawColor(Color.argb(110, 0, 0, 0))
    }

    private fun drawTitle(c: Canvas, text: String) {
        val p = Draw.hand(Color.WHITE, 78f).apply { setShadowLayer(12f, 0f, 3f, Color.argb(160, 0, 0, 0)) }
        val s = fit(text, p, W - side * 2 - 140f)
        c.drawText(s, side, titleBase, p)
        Draw.dateStamp(c, Draw.filmDate(dateText), W - side, titleBase, 34f)
    }

    private fun drawDots(c: Canvas, cur: Int) {
        val r = 9f; val gap = 30f
        val y = gridArea.b + 34f
        val x0 = W / 2 - gap * (n - 1) / 2
        val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }
        for (i in 0 until n) c.drawCircle(x0 + gap * i, y, r, if (i <= cur) on else off)
    }

    private fun drawAiBadge(c: Canvas) {
        val p = Draw.text(Color.WHITE, 30f, bold = true)
        val label = "AI"
        val w = p.measureText(label)
        val r = RectF(W - side - w - 36f, ReelSpec.SAFE_TOP + 100f, W - side, ReelSpec.SAFE_TOP + 148f)
        c.drawRoundRect(r, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) })
        c.drawText(label, r.left + 18f, r.centerY() + 11f, p)
    }

    /** '차~즈로 찍음' — 왼쪽 아래(오른쪽 아래는 릴스 버튼 자리). */
    private fun drawMark(c: Canvas) {
        val p = Draw.text(Color.argb(220, 255, 255, 255), 30f, bold = true).apply { setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0)) }
        c.drawText("차~즈로 찍음 · CHA~Z", side, bottomSafe - 14f, p)
    }

    private fun fit(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    private fun ease(x: Float) = 1f - (1f - x) * (1f - x)

    /** 아주 작게 줄였다가 두 배씩 여러 번 키워(매 단계 선형 보간) 깍두기 없이 부드럽게 흐린다. */
    private fun blur(b: Bitmap): Bitmap {
        val w0 = 40; val h0 = (w0 * b.height / b.width).coerceAtLeast(2)
        var cur = Bitmap.createScaledBitmap(b, w0, h0, true)
        repeat(4) {
            val next = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true)
            if (next !== cur) cur.recycle()
            cur = next
        }
        return cur
    }
}
