package ai.chaz.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskMathTest {
    @Test fun `알파 곡선 - 애매한 값은 깎고 확실한 인물만 남긴다`() {
        assertEquals(0, MaskMath.alpha(0.3f))
        assertEquals(0, MaskMath.alpha(0.5f))
        assertEquals(255, MaskMath.alpha(0.9f))
        assertTrue(MaskMath.alpha(0.675f) in 100..155)
    }

    @Test fun `가장 큰 덩어리의 경계만 고른다 (좌석 잡음 제외)`() {
        val w = 40; val h = 20
        val p = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0 until y1) for (x in x0 until x1) p[y * w + x] = 1f }
        fill(24, 4, 36, 20) // 인물 (큰 덩어리)
        fill(2, 12, 8, 16)  // 좌석 잡음 (작은 덩어리)
        assertArrayEquals(intArrayOf(24, 4, 36, 20), MaskMath.largestBlob(p, w, h, step = 4))
    }

    @Test fun `다듬기 - 떠 있는 조각은 지우고 가장자리는 부드럽게`() {
        val w = 40; val h = 20
        val p = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0 until y1) for (x in x0 until x1) p[y * w + x] = 1f }
        fill(20, 2, 36, 20) // 인물
        fill(2, 14, 6, 18)  // 떠 있는 핸들 조각
        val a = MaskMath.refine(p, w, h)
        assertEquals("떨어진 조각은 0", 0f, a[16 * w + 4], 0.001f)
        assertEquals("인물 안쪽은 불투명", 1f, a[10 * w + 28], 0.001f)
        val edge = a[10 * w + 21]
        assertTrue("가장자리는 중간 값 ($edge)", edge > 0.05f && edge < 0.95f)
        assertEquals("바깥은 0", 0f, a[10 * w + 15], 0.001f)
    }

    @Test fun `인물이 없으면 null`() {
        assertNull(MaskMath.largestBlob(FloatArray(100) { 0.2f }, 10, 10))
    }
}
