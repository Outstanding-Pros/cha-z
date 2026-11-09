package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/** 렌더러 공통 그리기 도구. */
internal object Draw {
    val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    fun filterPaint() = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** 원본(w×h)에서 aspect(가로/세로)에 맞는 잘라낼 영역. vBias 0 = 위, 0.5 = 가운데. */
    fun crop(w: Int, h: Int, aspect: Float, vBias: Float = 0.5f): Rect {
        val src = w.toFloat() / h
        return if (src > aspect) {
            val cw = (h * aspect).toInt()
            val l = (w - cw) / 2
            Rect(l, 0, l + cw, h)
        } else {
            val ch = (w / aspect).toInt()
            val t = ((h - ch) * vBias).toInt()
            Rect(0, t, w, t + ch)
        }
    }

    fun drawCover(canvas: Canvas, bmp: Bitmap, dst: RectF, vBias: Float = 0.5f) {
        canvas.drawBitmap(bmp, crop(bmp.width, bmp.height, dst.width() / dst.height(), vBias), dst, filterPaint())
    }

    /** 영역의 평균 밝기(0~255). 8픽셀 간격 표본. */
    fun luminance(bmp: Bitmap, r: Rect): Float {
        var sum = 0.0
        var n = 0
        val x0 = r.left.coerceIn(0, bmp.width - 1); val x1 = r.right.coerceIn(1, bmp.width)
        val y0 = r.top.coerceIn(0, bmp.height - 1); val y1 = r.bottom.coerceIn(1, bmp.height)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = bmp.getPixel(x, y)
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                n++
                x += 8
            }
            y += 8
        }
        return if (n == 0) 128f else (sum / n).toFloat()
    }

    fun text(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) BOLD else Typeface.DEFAULT
    }

    /** (cx, cy) 를 중심으로 세로(degrees 회전) 텍스트. */
    fun rotatedText(canvas: Canvas, s: String, cx: Float, cy: Float, degrees: Float, paint: Paint) {
        canvas.save()
        canvas.rotate(degrees, cx, cy)
        val w = paint.measureText(s)
        canvas.drawText(s, cx - w / 2, cy + paint.textSize / 3, paint)
        canvas.restore()
    }

    fun stampLine(s: StampInfo): String = listOfNotNull(
        s.dateText,
        s.place,
        s.weather,
        s.driveMode,
        s.odometerKm?.takeIf { it > 0 }?.let { "%,.0f km".format(it) }, // 에뮬레이터는 0 → 생략
    ).joinToString("  ·  ")
}
