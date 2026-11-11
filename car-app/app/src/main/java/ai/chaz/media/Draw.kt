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
    private val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    /** 손글씨 캡션 글꼴 (assets/fonts/NanumPenScript, OFL). 앱 시작 시 AppGraph 가 채운다. 없으면 기본 글꼴. */
    @Volatile var handwriting: Typeface? = null

    fun hand(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = handwriting ?: Typeface.DEFAULT
    }

    // ---- 필름 감성 효과 (L2) ----

    /** 필름 입자 타일 (회색 128 ± 잡음). OVERLAY 로 겹쳐 그린다. 고정 시드라 미리보기와 결과물이 같다. */
    private val grainTile: Bitmap by lazy {
        val n = 256
        val rnd = java.util.Random(7)
        val px = IntArray(n * n) {
            val v = (128 + rnd.nextGaussian() * 38).toInt().coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    fun grain(canvas: Canvas, area: RectF, scale: Float, alpha: Int = 46) {
        val shader = android.graphics.BitmapShader(grainTile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        // 결과물 해상도에서도 입자가 너무 잘아지지 않게 키운다
        val k = 1.5f * scale.coerceAtLeast(0.5f)
        shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(k, k) })
        canvas.drawRect(area, Paint().apply {
            this.shader = shader
            this.alpha = alpha
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        })
    }

    /** 가장자리를 어둡게 (필름 카메라 렌즈 느낌). */
    fun vignette(canvas: Canvas, area: RectF, strength: Float = 0.55f) {
        val r = kotlin.math.hypot(area.width(), area.height()) / 2f
        canvas.drawRect(area, Paint().apply {
            shader = android.graphics.RadialGradient(
                area.centerX(), area.centerY(), r,
                intArrayOf(0x00000000, 0x00000000, Color.argb((255 * strength).toInt(), 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
        })
    }

    /** 배경을 살짝 흐리게 (얕은 심도 — 인물이 붙여 넣은 듯 떠 보이지 않게). factor = 줄였다 키우는 배수. */
    fun soften(bmp: Bitmap, factor: Int = 4): Bitmap {
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width / factor).coerceAtLeast(1), (bmp.height / factor).coerceAtLeast(1), true)
        val out = Bitmap.createScaledBitmap(small, (bmp.width / 2).coerceAtLeast(1), (bmp.height / 2).coerceAtLeast(1), true)
        if (small !== out) small.recycle()
        return out
    }

    private val DATE = Regex("(\\d{2})(\\d{2})\\D(\\d{1,2})\\D(\\d{1,2})")

    /** "2026.11.12" → "'26 11 12" (일회용 카메라 날짜 각인). */
    fun filmDate(dateText: String): String =
        DATE.find(dateText)?.destructured?.let { (_, yy, m, d) -> "'$yy ${m.padStart(2, '0')} ${d.padStart(2, '0')}" } ?: dateText

    /** 주황색 디지털 날짜 각인. (right, baseline) 기준 오른쪽 정렬. */
    fun dateStamp(canvas: Canvas, text: String, right: Float, baseline: Float, size: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF9A3C.toInt()
            textSize = size
            typeface = MONO_BOLD
            letterSpacing = 0.08f
            setShadowLayer(size * 0.35f, 0f, 0f, 0xCCFF5A1F.toInt())
        }
        canvas.drawText(text, right - p.measureText(text), baseline, p)
    }

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
