package ai.chaz.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreviewTest {
    @Test fun `빠르면 인물 분리 유지`() {
        val g = FpsGovernor(minFps = 6f, window = 4, warmup = 0)
        repeat(10) { g.record(70) }
        assertTrue(g.segmentation)
        assertEquals(14.3f, g.fps(), 0.2f)
    }

    @Test fun `느려지면 인물 분리를 끄고 다시 켜지 않는다`() {
        val g = FpsGovernor(minFps = 6f, window = 4, warmup = 0)
        repeat(4) { g.record(400) } // 2.5fps
        assertFalse(g.segmentation)
        repeat(10) { g.record(50) }
        assertFalse(g.segmentation)
    }

    @Test fun `워밍업 프레임은 판단에서 뺀다`() {
        val g = FpsGovernor(minFps = 6f, window = 4, warmup = 3)
        repeat(3) { g.record(900) } // 모델 로딩
        repeat(4) { g.record(80) }
        assertTrue(g.segmentation)
    }

    @Test fun `색 필터`() {
        assertEquals(Triple(10, 20, 30), ColorPreset.ORIGINAL.apply(10, 20, 30))
        val (r, g, b) = ColorPreset.MONO.apply(200, 100, 50)
        assertTrue(r == g && g == b)
        val warm = ColorPreset.WARM.apply(128, 128, 128)
        assertTrue(warm.first > warm.third) // 노을 = 붉게
        val cool = ColorPreset.COOL.apply(128, 128, 128)
        assertTrue(cool.third > cool.first) // 오션 = 푸르게
    }
}
