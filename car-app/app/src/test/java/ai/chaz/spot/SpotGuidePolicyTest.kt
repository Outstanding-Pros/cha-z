package ai.chaz.spot

import ai.chaz.ambience.ContextProvider
import ai.chaz.ambience.SceneType
import ai.chaz.ambience.TimeBand
import ai.chaz.ambience.Weather
import ai.chaz.platform.navi.Geo
import ai.chaz.platform.navi.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotGuidePolicyTest {
    private val beach = Spot("b", "협재해수욕장", GeoPoint(33.3940, 126.2398), "beach", "demo")
    private val peak = Spot("p", "새별오름", GeoPoint(33.3660, 126.3577), "peak", "demo")
    private val shop = Spot("s", "가게", GeoPoint(33.3941, 126.2399), "shop", "demo")
    private val p = SpotGuidePolicy()

    /** beach 에서 북쪽으로 m 미터 떨어진 점. */
    private fun northOfBeach(m: Double) = GeoPoint(33.3940 + m / 111_000.0, 126.2398)

    @Test fun `거리 계산`() {
        assertEquals(1_000.0, Geo.distanceM(northOfBeach(0.0), northOfBeach(1_000.0)), 5.0)
    }

    @Test fun `400m 안으로 접근하면 가이드, 같은 스팟은 한 번만`() {
        var s = SpotGuidePolicy.State()
        var (d, ns) = p.decide(s, northOfBeach(900.0), listOf(beach), 0, null, null); s = ns
        assertEquals(SpotGuidePolicy.Decision.None, d)
        val r = p.decide(s, northOfBeach(350.0), listOf(beach), 10_000, null, null); s = r.second
        assertTrue(r.first is SpotGuidePolicy.Decision.SpotGuide)
        // 10분 뒤 다시 와도 같은 여정이면 안 함
        val again = p.decide(s, northOfBeach(200.0), listOf(beach), 700_000, null, null)
        assertEquals(SpotGuidePolicy.Decision.None, again.first)
    }

    @Test fun `멀어지는 중이면 안 함`() {
        val s0 = p.decide(SpotGuidePolicy.State(), northOfBeach(200.0), listOf(beach), 0, 500.0, null).second
            .copy(lastGuideAt = null, guided = emptySet()) // 처음 기록만 남김
        val away = p.decide(s0.copy(prevDist = mapOf("b" to 200.0)), northOfBeach(350.0), listOf(beach), 1_000, null, null)
        assertEquals(SpotGuidePolicy.Decision.None, away.first)
    }

    @Test fun `가이드 간 최소 5분, 회전 300m 안이면 미룸, 점수 2 미만 제외`() {
        val near = listOf(beach, peak, shop)
        val s1 = p.decide(SpotGuidePolicy.State(), northOfBeach(300.0), near, 0, 250.0, null)
        assertEquals("회전 안내가 가까우면 미룬다", SpotGuidePolicy.Decision.None, s1.first)
        val s2 = p.decide(SpotGuidePolicy.State(), northOfBeach(300.0), listOf(shop), 0, null, null)
        assertEquals("점수 0 스팟은 안내 안 함", SpotGuidePolicy.Decision.None, s2.first)
        val g = p.decide(SpotGuidePolicy.State(), northOfBeach(300.0), near, 0, null, null)
        assertTrue(g.first is SpotGuidePolicy.Decision.SpotGuide)
        val other = Spot("o", "다른 해변", GeoPoint(33.3945, 126.2398), "beach", "demo")
        val tooSoon = p.decide(g.second, northOfBeach(200.0), near + other, 60_000, null, null)
        assertEquals(SpotGuidePolicy.Decision.None, tooSoon.first)
        val later = p.decide(g.second, northOfBeach(200.0), near + other, 6 * 60_000L, null, null)
        assertTrue(later.first is SpotGuidePolicy.Decision.SpotGuide)
    }

    @Test fun `노을 가이드는 일몰 전후 30분에 한 번`() {
        val sunset = 10_000_000L
        val far = GeoPoint(37.5, 127.0)
        val (d, s) = p.decide(SpotGuidePolicy.State(), far, emptyList(), sunset - 20 * 60_000, null, sunset)
        assertEquals(SpotGuidePolicy.Decision.SunsetGuide, d)
        val again = p.decide(s.copy(lastGuideAt = 0), far, emptyList(), sunset + 20 * 60_000, null, sunset)
        assertEquals(SpotGuidePolicy.Decision.None, again.first)
    }

    @Test fun `이에요 예요`() {
        assertEquals("이에요", SpotGuidePolicy.copula("협재해수욕장"))
        assertEquals("예요", SpotGuidePolicy.copula("애월 해안도로 전망대"))
        assertEquals("예요", SpotGuidePolicy.copula("새별오름 앞 바다"))
    }

    @Test fun `점수와 병합`() {
        assertEquals(3, beach.score)
        assertEquals(2, peak.score)
        val dup = beach.copy(id = "osm:1", source = "overpass")
        assertEquals(1, SpotRepository.merge(listOf(beach, dup)).size)
    }

    @Test fun `맥락 - 시간대·날씨·풍경`() {
        val sunset = 100 * 60_000L
        assertEquals(TimeBand.GOLDEN_HOUR, ContextProvider.timeBand(sunset - 10 * 60_000, 0, sunset))
        assertEquals(TimeBand.NIGHT, ContextProvider.timeBand(sunset + 40 * 60_000, 0, sunset))
        assertEquals(TimeBand.DAY, ContextProvider.timeBand(sunset - 90 * 60_000, 0, sunset))
        assertEquals(Weather.CLEAR, ContextProvider.weatherOf(0))
        assertEquals(Weather.RAIN, ContextProvider.weatherOf(61))
        assertEquals(Weather.SNOW, ContextProvider.weatherOf(73))
        assertEquals(Weather.CLOUDY, ContextProvider.weatherOf(3))
        assertEquals(SceneType.COAST, ContextProvider.sceneOf(northOfBeach(500.0), listOf(beach, peak)))
        assertEquals(SceneType.UNKNOWN, ContextProvider.sceneOf(GeoPoint(37.5, 127.0), listOf(beach)))
    }
}
