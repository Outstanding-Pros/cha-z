package ai.chaz.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelTimelineTest {
    @Test fun `길이 - 그리드 6초, 이어붙이기 장수x2초, 쌓아서 공개 +2초`() {
        assertEquals(6_000, ReelTimeline.durationMs(ReelMotion.GRID, 4))
        assertEquals(8_000, ReelTimeline.durationMs(ReelMotion.SEQUENCE, 4))
        assertEquals(10_000, ReelTimeline.durationMs(ReelMotion.STACK, 4))
        assertEquals(300, ReelTimeline.frames(ReelMotion.STACK, 4))
    }

    @Test fun `이어붙이기는 2초마다 다음 사진`() {
        assertEquals(0, ReelTimeline.at(ReelMotion.SEQUENCE, 3, 0).single)
        assertEquals(0, ReelTimeline.at(ReelMotion.SEQUENCE, 3, 1_999).single)
        assertEquals(1, ReelTimeline.at(ReelMotion.SEQUENCE, 3, 2_000).single)
        assertEquals(2, ReelTimeline.at(ReelMotion.SEQUENCE, 3, 99_999).single) // 끝을 넘어가면 마지막 장
    }

    @Test fun `쌓아서 공개는 마지막 2초가 그리드`() {
        assertEquals(1, ReelTimeline.at(ReelMotion.STACK, 2, 3_000).single)
        val end = ReelTimeline.at(ReelMotion.STACK, 2, 4_000)
        assertNull(end.single)
        assertEquals(0f, end.reveal, 0.001f)
        assertEquals(1f, ReelTimeline.at(ReelMotion.STACK, 2, 4_500).reveal, 0.001f)
    }

    @Test fun `그리드 칸은 영역 안, 장수만큼`() {
        val area = ReelBox(65f, 390f, 1015f, 1180f)
        for (n in 1..4) {
            val cells = ReelTimeline.gridCells(n, area, 22f)
            assertEquals(n, cells.size)
            cells.forEach { c -> assertTrue(c.l >= area.l && c.r <= area.r && c.t >= area.t && c.b <= area.b && c.w > 0 && c.h > 0) }
        }
    }

    @Test fun `글자 영역은 릴스 안전 영역 안`() {
        assertTrue(ReelSpec.SAFE_TOP + ReelSpec.SAFE_BOTTOM < ReelSpec.H)
        assertEquals(1080f / 1920f, 9f / 16f, 0.0001f)
    }
}
