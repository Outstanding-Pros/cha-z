package ai.chaz.ambience

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightRuleEngineTest {
    private fun ctx(w: Weather = Weather.CLEAR, t: TimeBand = TimeBand.DAY, s: SceneType = SceneType.UNKNOWN) =
        CaptureContext(s, t, w, sunsetMs = null, source = "test")

    @Test fun `SPEC 표 - 조건별 프리셋`() {
        assertEquals(LightPreset.COZY, LightRuleEngine.choose(ctx(w = Weather.RAIN)))
        assertEquals(LightPreset.COZY, LightRuleEngine.choose(ctx(w = Weather.SNOW)))
        assertEquals(LightPreset.COZY, LightRuleEngine.choose(ctx(w = Weather.CLOUDY)))
        assertEquals(LightPreset.GOLDEN, LightRuleEngine.choose(ctx(t = TimeBand.GOLDEN_HOUR)))
        assertEquals(LightPreset.CITY_NIGHT, LightRuleEngine.choose(ctx(t = TimeBand.NIGHT, s = SceneType.CITY)))
        assertEquals(LightPreset.OCEAN, LightRuleEngine.choose(ctx(s = SceneType.COAST)))
        assertEquals(LightPreset.FOREST, LightRuleEngine.choose(ctx(s = SceneType.FOREST)))
        assertEquals(LightPreset.FOREST, LightRuleEngine.choose(ctx(s = SceneType.MOUNTAIN)))
    }

    @Test fun `우선순위 - 날씨 그다음 시간대 그다음 풍경 그다음 BGM`() {
        // 비 오는 노을 해안 → 날씨가 이긴다
        assertEquals(LightPreset.COZY, LightRuleEngine.choose(ctx(Weather.RAIN, TimeBand.GOLDEN_HOUR, SceneType.COAST)))
        // 맑은 노을 해안 → 시간대가 풍경보다 먼저
        assertEquals(LightPreset.GOLDEN, LightRuleEngine.choose(ctx(t = TimeBand.GOLDEN_HOUR, s = SceneType.COAST)))
        // 아무 조건도 없으면 BGM 분위기 색, BGM 도 없으면 바꾸지 않음
        assertEquals(LightPreset.BGM_EXCITING, LightRuleEngine.choose(ctx(), LightRuleEngine.BgmMood.EXCITING))
        assertEquals(LightPreset.BGM_CALM, LightRuleEngine.choose(ctx(), LightRuleEngine.BgmMood.CALM))
        assertNull(LightRuleEngine.choose(ctx()))
    }

    @Test fun `촬영 조명은 정차 중 밤에만`() {
        assertEquals(LightPreset.PHOTO_LIGHT, LightRuleEngine.choose(ctx(t = TimeBand.NIGHT), parked = true, wantPhotoLight = true))
        assertEquals(null, LightRuleEngine.choose(ctx(t = TimeBand.NIGHT), parked = false, wantPhotoLight = true))
        assertNull(LightRuleEngine.plan(LightPreset.PHOTO_LIGHT, driving = true, current = null, minB = 0, maxB = 10))
    }

    @Test fun `주행 중 - 3초 이상 페이드, 밝기는 현재 이하`() {
        val p = LightRuleEngine.plan(LightPreset.OCEAN, driving = true, current = LightLevel(0xFFFFFFFF.toInt(), 3), minB = 0, maxB = 10)!!
        assertTrue(p.fadeMs >= 3_000)
        assertEquals(3, p.brightness) // 프리셋은 7 이지만 현재 3 이라 3 으로
        val parked = LightRuleEngine.plan(LightPreset.OCEAN, driving = false, current = LightLevel(0, 3), minB = 0, maxB = 10)!!
        assertEquals(7, parked.brightness)
    }

    @Test fun `페이드는 한 방향으로만 (깜빡임 없음)`() {
        val from = LightLevel(0xFF000000.toInt(), 2)
        val plan = LightPlan(LightPreset.OCEAN, 0xFF22C4E8.toInt(), 8, 3_000)
        val reds = (0..10).map { LightRuleEngine.step(from, plan, it / 10f) }
        val bs = reds.map { it.brightness }
        val greens = reds.map { (it.color shr 8) and 0xFF }
        assertEquals(bs, bs.sorted())
        assertEquals(greens, greens.sorted())
        assertEquals(plan.color, reds.last().color)
    }

    @Test fun `디렉터 - 주행 중 자동 변경을 끄면 주행 중엔 바꾸지 않고 정차 중엔 바꾼다`() = runTest {
        val light = SimulatedAmbientLight()
        var driving = true
        val d = AmbienceDirector({ light }, { ctx(s = SceneType.COAST) }, driving = { driving }, autoWhileDriving = { false })
        assertNull(d.begin("spot"))
        assertEquals(0, light.applied.size)
        driving = false
        assertEquals(LightPreset.OCEAN, d.begin("session"))
    }

    @Test fun `디렉터 - 에피소드 시작에 한 번 적용, 끝나면 복원`() = runTest {
        val light = SimulatedAmbientLight()
        val d = AmbienceDirector({ light }, { ctx(s = SceneType.COAST) }, driving = { true })
        assertEquals(LightPreset.OCEAN, d.begin("spot"))
        d.begin("session") // 이어지는 세션: 다시 바꾸지 않음
        assertEquals(1, light.applied.size)
        d.end()
        assertNull(light.shown.value)
        assertNull(d.manual(LightPreset.GOLDEN)) // 주행 중 수동 변경 불가
    }
}
