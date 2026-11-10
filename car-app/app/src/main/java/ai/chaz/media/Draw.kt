package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
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

    /** 평균 색 [r,g,b] (0~255). 알파가 있으면 알파 가중 (누끼는 인물 부분만). 표본 간격 step. */
    fun meanColor(bmp: Bitmap, step: Int = 6): FloatArray {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0.0
        val row = IntArray(bmp.width)
        var y = 0
        while (y < bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            var x = 0
            while (x < bmp.width) {
                val c = row[x]
                val a = if (bmp.hasAlpha()) Color.alpha(c) / 255.0 else 1.0
                r += Color.red(c) * a; g += Color.green(c) * a; b += Color.blue(c) * a; n += a
                x += step
            }
            y += step
        }
        return if (n < 1) floatArrayOf(128f, 128f, 128f) else floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    /**
     * 인물을 배경 톤에 맞추는 색 행렬: 채널별 이득을 배경/인물 평균 비율 쪽으로 strength 만큼 옮긴다.
     * 밝기는 반만 따라가고(얼굴이 너무 어두워지지 않게) 채널 이득은 0.8~1.25 로 제한.
     */
    fun toneMatch(person: FloatArray, bg: FloatArray, strength: Float = 0.45f): ColorMatrix {
        fun lum(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
        val lp = lum(person).coerceAtLeast(1f); val lb = lum(bg).coerceAtLeast(1f)
        val bright = 1f + (lb / lp - 1f) * strength * 0.5f
        val gains = FloatArray(3) { i ->
            val chroma = (bg[i] / lb) / (person[i] / lp).coerceAtLeast(0.01f) // 색 비율만
            ((1f + (chroma - 1f) * strength) * bright).coerceIn(0.8f, 1.25f)
        }
        return ColorMatrix(floatArrayOf(
            gains[0], 0f, 0f, 0f, 0f,
            0f, gains[1], 0f, 0f, 0f,
            0f, 0f, gains[2], 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /**
     * 라이트 랩: 누끼 안쪽 가장자리에 배경 색을 SCREEN 으로 살짝 비춰 붙여 넣은 티를 줄인다.
     * cut 은 알파 있는 누끼, dst 는 그릴 위치. radius 는 cut 픽셀 기준.
     */
    fun lightWrap(canvas: Canvas, cut: Bitmap, dst: RectF, color: FloatArray, radius: Float, alpha: Int = 90) {
        val rgb = Color.rgb(color[0].toInt(), color[1].toInt(), color[2].toInt())
        // 가장자리 띠 = A × (1 − blur(A)): 누끼 알파를 배경색으로 칠하고, 흐린 알파만큼 안쪽을 지운다
        val band = Bitmap.createBitmap(cut.width, cut.height, Bitmap.Config.ARGB_8888)
        val bc = Canvas(band)
        bc.drawBitmap(cut, 0f, 0f, Paint().apply { colorFilter = PorterDuffColorFilter(rgb, PorterDuff.Mode.SRC_IN) })
        val offset = IntArray(2)
        val blurred = cut.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(radius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL) }, offset)
        bc.drawBitmap(blurred, offset[0].toFloat(), offset[1].toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        blurred.recycle()
        canvas.drawBitmap(band, null, dst, filterPaint().apply {
            this.alpha = alpha
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        })
        band.recycle()
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
