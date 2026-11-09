package ai.chaz.media

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * 색 필터. SPEC 은 LUT 를 GPU 셰이더로 적용하라고 하지만, 1차에서는 ColorMatrix 로 근사한다
 * (미리보기·결과물이 같은 행렬을 써서 본 그대로 찍힌다). 행렬은 순수 FloatArray 라 단위 테스트한다.
 */
enum class ColorPreset(val label: String, val matrix: FloatArray?) {
    ORIGINAL("원본", null),
    FILM("필름", floatArrayOf(
        // 대비 살짝 낮추고 그림자를 들어 올린 뒤 따뜻하게
        0.90f, 0.05f, 0.02f, 0f, 22f,
        0.03f, 0.86f, 0.03f, 0f, 16f,
        0.02f, 0.04f, 0.78f, 0f, 14f,
        0f, 0f, 0f, 1f, 0f,
    )),
    WARM("노을", floatArrayOf(
        1.10f, 0f, 0f, 0f, 10f,
        0f, 1.00f, 0f, 0f, 0f,
        0f, 0f, 0.85f, 0f, -8f,
        0f, 0f, 0f, 1f, 0f,
    )),
    COOL("오션", floatArrayOf(
        0.90f, 0f, 0f, 0f, -6f,
        0f, 1.02f, 0f, 0f, 4f,
        0f, 0f, 1.12f, 0f, 12f,
        0f, 0f, 0f, 1f, 0f,
    )),
    MONO("흑백", floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )),
    ;

    fun colorFilter(): ColorMatrixColorFilter? = matrix?.let { ColorMatrixColorFilter(ColorMatrix(it)) }

    /** 한 픽셀(0~255 RGB)에 적용 — 테스트용. */
    fun apply(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val m = matrix ?: return Triple(r, g, b)
        fun row(i: Int) = (m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4]).toInt().coerceIn(0, 255)
        return Triple(row(0), row(1), row(2))
    }
}
