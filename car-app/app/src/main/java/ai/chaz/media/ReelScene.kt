package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/** 릴스 한 편의 그림: 1080×1920 좌표로 시각 t 의 프레임과 커버를 그린다. 스타일마다 하나. */
interface ReelScene {
    val durationMs: Long
    /** 배경 음악 분위기 (자체 합성 BGM). */
    val mood: BgmMood
    fun draw(c: Canvas, tMs: Long)
    fun drawCover(c: Canvas)
}

/** 릴스 스타일들이 같이 쓰는 그리기 조각 (안전 영역·표기 규칙을 한곳에서 지킨다). */
internal object ReelDraw {
    const val W = ReelSpec.W.toFloat()
    const val H = ReelSpec.H.toFloat()
    const val SIDE = ReelSpec.SAFE_SIDE.toFloat()
    const val TITLE_BASE = ReelSpec.SAFE_TOP + 70f
    const val BOTTOM_SAFE = H - ReelSpec.SAFE_BOTTOM
    /** 사진을 놓는 핵심 영역 (제목 아래 ~ '차~즈로 찍음' 위). */
    val core = ReelBox(SIDE, TITLE_BASE + 50f, W - SIDE, BOTTOM_SAFE - 70f)

    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** 아주 작게 줄였다가 두 배씩 키워 부드럽게 흐린다. */
    fun blur(b: Bitmap): Bitmap {
        val w0 = 40; val h0 = (w0 * b.height / b.width).coerceAtLeast(2)
        var cur = Bitmap.createScaledBitmap(b, w0, h0, true)
        repeat(4) {
            val next = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true)
            if (next !== cur) cur.recycle()
            cur = next
        }
        return cur
    }

    fun background(c: Canvas, blur: Bitmap, dim: Int = 110) {
        c.drawColor(Color.BLACK)
        Draw.drawCover(c, blur, RectF(0f, 0f, W, H))
        c.drawColor(Color.argb(dim, 0, 0, 0))
    }

    fun title(c: Canvas, text: String, dateText: String?, size: Float = 78f, alpha: Int = 255) {
        val p = Draw.hand(Color.WHITE, size).apply { this.alpha = alpha; setShadowLayer(12f, 0f, 3f, Color.argb(160 * alpha / 255, 0, 0, 0)) }
        c.drawText(fit(text, p, W - SIDE * 2 - 160f), SIDE, TITLE_BASE, p)
        if (dateText != null && alpha > 200) Draw.dateStamp(c, Draw.filmDate(dateText), W - SIDE, TITLE_BASE, 34f)
    }

    /** 가운데 정렬 글자 (자막). */
    fun centered(c: Canvas, text: String, y: Float, size: Float, color: Int = Color.WHITE, bold: Boolean = true, alpha: Int = 255) {
        val p = Draw.text(color, size, bold).apply { this.alpha = alpha; setShadowLayer(10f, 0f, 2f, Color.argb(170 * alpha / 255, 0, 0, 0)) }
        val s = fit(text, p, W - SIDE * 2)
        c.drawText(s, (W - p.measureText(s)) / 2, y, p)
    }

    fun aiBadge(c: Canvas, label: String = "AI") {
        val p = Draw.text(Color.WHITE, 30f, bold = true)
        val w = p.measureText(label)
        val r = RectF(W - SIDE - w - 36f, ReelSpec.SAFE_TOP + 100f, W - SIDE, ReelSpec.SAFE_TOP + 148f)
        c.drawRoundRect(r, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) })
        c.drawText(label, r.left + 18f, r.centerY() + 11f, p)
    }

    /** '차~즈로 찍음' — 왼쪽 아래(오른쪽 아래는 릴스 버튼 자리). */
    fun mark(c: Canvas) {
        val p = Draw.text(Color.argb(220, 255, 255, 255), 30f, bold = true).apply { setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0)) }
        c.drawText("차~즈로 찍음 · CHA~Z", SIDE, BOTTOM_SAFE - 14f, p)
    }

    /** 흰 테두리 사진 카드. rot = 기울기(도), scale 은 카드 가운데 기준. */
    fun card(c: Canvas, bmp: Bitmap, r: RectF, rot: Float = 0f, alpha: Int = 255, border: Float = 12f) {
        c.save()
        c.rotate(rot, r.centerX(), r.centerY())
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.alpha = alpha; setShadowLayer(18f, 0f, 6f, Color.argb(120 * alpha / 255, 0, 0, 0)) }
        c.drawRoundRect(r, 12f, 12f, shadow)
        val inner = RectF(r.left + border, r.top + border, r.right - border, r.bottom - border)
        c.drawBitmap(bmp, Draw.crop(bmp.width, bmp.height, inner.width() / inner.height()), inner, Paint(paint).apply { this.alpha = alpha })
        c.restore()
    }

    /** 사진을 가로 꽉 차게(전체 보이게) 가운데에, zoom 배. */
    fun fitWidth(c: Canvas, bmp: Bitmap, cy: Float, zoom: Float, maxH: Float = core.h * 1.1f, alpha: Int = 255) {
        var fw = W * zoom
        var fh = fw * bmp.height / bmp.width
        if (fh > maxH) { val k = maxH / fh; fw *= k; fh *= k }
        c.drawBitmap(bmp, null, RectF((W - fw) / 2, cy - fh / 2, (W + fw) / 2, cy + fh / 2), Paint(paint).apply { this.alpha = alpha })
    }

    /** 사진을 화면 전체(9:16)로 잘라 채운다. panX 0~1 = 가로로 어디를 보여 줄지, zoom ≥ 1. */
    fun fill(c: Canvas, bmp: Bitmap, panX: Float = 0.5f, zoom: Float = 1f, alpha: Int = 255) {
        val k = H / bmp.height * zoom
        val sw = bmp.width * k
        val left = -(sw - W).coerceAtLeast(0f) * panX.coerceIn(0f, 1f)
        val sh = bmp.height * k
        val m = Matrix().apply { setScale(k, k); postTranslate(left, (H - sh) / 2) }
        c.drawBitmap(bmp, m, Paint(paint).apply { this.alpha = alpha })
    }

    fun box(b: ReelBox) = RectF(b.l, b.t, b.r, b.b)

    fun fit(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    fun ease(x: Float): Float { val y = x.coerceIn(0f, 1f); return 1f - (1f - y) * (1f - y) }
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
}
