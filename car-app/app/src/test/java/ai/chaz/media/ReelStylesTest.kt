package ai.chaz.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReelStylesTest {
    @Test fun `BGM - 길이 맞고 클리핑 없고 소리가 있다`() {
        for (m in BgmMood.entries) {
            val pcm = BgmSynth.render(m, 2_000)
            assertEquals(BgmSynth.RATE * 2, pcm.size)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$m peak=$peak", peak in 10_000..32_767)
            assertEquals(0, pcm.first().toInt()) // 페이드 인
        }
    }

    @Test fun `BGM - 같은 입력이면 같은 소리 (자체 음원 재현성)`() {
        assertTrue(BgmSynth.render(BgmMood.OCEAN, 1_000).contentEquals(BgmSynth.render(BgmMood.OCEAN, 1_000)))
    }

    @Test fun `오션 브릿지 - 밝기가 확 오르는 프레임을 찾는다`() {
        assertEquals(3, OceanBridgeTimeline.findOpening(listOf(30f, 32f, 35f, 140f, 150f)))
        assertEquals(-1, OceanBridgeTimeline.findOpening(listOf(120f, 125f, 130f))) // 처음부터 밝으면 없음
    }

    @Test fun `오션 브릿지 - 열리는 프레임이 2초 지점에 온다`() {
        assertEquals(6, OceanBridgeTimeline.frameAt(2_000, 24, 6))
        assertEquals(2, OceanBridgeTimeline.frameAt(0, 24, 6))
        assertEquals(0, OceanBridgeTimeline.frameAt(0, 24, -1))
        assertEquals(23, OceanBridgeTimeline.frameAt(14_999, 24, 20)) // 끝은 마지막 프레임에서 멈춤
    }

    @Test fun `오션 브릿지 - 어둠은 2초 뒤 걷히고 컷은 4초부터 2초마다`() {
        assertTrue(OceanBridgeTimeline.darkness(1_000, false) > 0.5f)
        assertEquals(0f, OceanBridgeTimeline.darkness(4_000, false), 0.001f)
        assertEquals(0, OceanBridgeTimeline.cardsShown(3_999, 4))
        assertEquals(1, OceanBridgeTimeline.cardsShown(4_000, 4))
        assertEquals(4, OceanBridgeTimeline.cardsShown(11_000, 4))
        assertEquals(2, OceanBridgeTimeline.cardsShown(11_000, 2))
    }

    @Test fun `스타일 노출 조건`() {
        assertFalse(ReelStyle.available(ReelStyle.OCEAN, videos = 0, sessionsToday = 5, photos = 10))
        assertTrue(ReelStyle.available(ReelStyle.MOVIE, videos = 1, sessionsToday = 0, photos = 0))
        assertFalse(ReelStyle.available(ReelStyle.TRIP, videos = 3, sessionsToday = 1, photos = 4))
        assertTrue(ReelStyle.available(ReelStyle.TRIP, videos = 0, sessionsToday = 2, photos = 2))
        assertFalse(ReelStyle.available(ReelStyle.NIGHT, 0, 0, photos = 1)) // 노을 + 별밤 = 2장 이상
    }

    @Test fun `얼굴 사진을 보내는 AI 단계만 동의가 필요`() {
        assertEquals(setOf(ReelStyle.NIGHT, ReelStyle.PET), ReelStyle.entries.filter { it.faceAi }.toSet())
    }

    @Test fun `크롭줌 - 1장 6초, 4장 12초`() {
        assertEquals(6_000, ReelTimeline.durationMs(ReelMotion.CROP_ZOOM, 1))
        assertEquals(12_000, ReelTimeline.durationMs(ReelMotion.CROP_ZOOM, 4))
        assertEquals(1, ReelTimeline.at(ReelMotion.CROP_ZOOM, 4, 3_000).single)
    }

    @Test fun `템플릿 카피`() {
        assertEquals("11월 15일의 드라이브", ReelStyle.tripTemplateTitle(11, 15))
        assertEquals(3, ReelStyle.movieTemplate("애월").second.size)
    }
}
