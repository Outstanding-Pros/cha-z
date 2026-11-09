package ai.chaz.ambience

/** 앰비언트 조명 프리셋 (SPEC F3 표). brightness 는 최대 밝기 대비 비율. */
enum class LightPreset(val label: String, val color: Int, val brightness: Float) {
    COZY("코지", 0xFFFFD6A0.toInt(), 0.35f),           // 비·눈·흐림: 웜 화이트, 낮은 밝기
    GOLDEN("골든아워", 0xFFFF8A1F.toInt(), 0.7f),       // 일몰 전후 30분: 앰버·오렌지
    CITY_NIGHT("시티나이트", 0xFFB23CFF.toInt(), 0.6f), // 밤 + 도시: 퍼플·마젠타
    OCEAN("오션", 0xFF22C4E8.toInt(), 0.7f),            // 낮 + 해안: 아쿠아 블루
    FOREST("포레스트", 0xFF3DDC84.toInt(), 0.7f),       // 낮 + 숲·산: 그린
    BGM_EXCITING("신나는 곡", 0xFFFF2E9A.toInt(), 0.7f), // 그 외: BGM 분위기 색
    BGM_CALM("잔잔한 곡", 0xFF6EC6FF.toInt(), 0.6f),
    PHOTO_LIGHT("촬영 조명", 0xFFFFF1DC.toInt(), 1.0f), // 정차 중 야간 얼굴용 밝은 웜 화이트
    ;

    companion object {
        fun byLabel(text: String): LightPreset? = entries.firstOrNull { text.replace(" ", "").contains(it.label.replace(" ", "")) }
    }
}

/** 현재 조명 상태 (복원용 스냅샷). brightness 는 차량 단위 그대로. */
data class LightLevel(val color: Int, val brightness: Int)

/** 실제로 적용할 계획. */
data class LightPlan(val preset: LightPreset, val color: Int, val brightness: Int, val fadeMs: Long)

/**
 * LightRuleEngine (SPEC F3) — 순수 함수, 단위 테스트 필수.
 * 우선순위: 날씨 > 시간대 > 풍경 > BGM.
 * 주행 중: 세션 시작 때 한 번, 3초 이상 페이드, 밝기는 현재 값 이하, 깜빡임 금지(=한 번의 단조 페이드).
 * 정차 중: 모든 프리셋 + 촬영 조명.
 */
object LightRuleEngine {
    const val DRIVING_MIN_FADE_MS = 3_000L
    const val PARKED_FADE_MS = 1_500L

    enum class BgmMood { EXCITING, CALM }

    fun choose(ctx: CaptureContext, bgm: BgmMood? = null, parked: Boolean = false, wantPhotoLight: Boolean = false): LightPreset? {
        if (parked && wantPhotoLight && ctx.timeBand == TimeBand.NIGHT) return LightPreset.PHOTO_LIGHT
        if (ctx.weather != Weather.CLEAR) return LightPreset.COZY
        if (ctx.timeBand == TimeBand.GOLDEN_HOUR) return LightPreset.GOLDEN
        if (ctx.timeBand == TimeBand.NIGHT && ctx.scene == SceneType.CITY) return LightPreset.CITY_NIGHT
        if (ctx.timeBand == TimeBand.DAY) {
            when (ctx.scene) {
                SceneType.COAST -> return LightPreset.OCEAN
                SceneType.FOREST, SceneType.MOUNTAIN -> return LightPreset.FOREST
                else -> Unit
            }
        }
        return when (bgm) {
            BgmMood.EXCITING -> LightPreset.BGM_EXCITING
            BgmMood.CALM -> LightPreset.BGM_CALM
            null -> null
        }
    }

    /**
     * @param current 지금 조명 (없으면 밝기 상한을 max 로 본다)
     * @param minB maxB 차량 밝기 범위 (checkAmbientLightBrightnessCapabilities)
     */
    fun plan(preset: LightPreset, driving: Boolean, current: LightLevel?, minB: Int, maxB: Int): LightPlan? {
        if (driving && preset == LightPreset.PHOTO_LIGHT) return null // 촬영 조명은 정차 중만
        var b = (minB + (maxB - minB) * preset.brightness).toInt().coerceIn(minB, maxB)
        if (driving && current != null) b = minOf(b, current.brightness) // 주행 중: 현재 밝기 이하
        val fade = if (driving) DRIVING_MIN_FADE_MS else PARKED_FADE_MS
        return LightPlan(preset, preset.color, b, fade)
    }

    /** 페이드 한 단계 (t = 0~1). 색은 채널별 선형, 밝기도 선형 — 한 방향으로만 움직여 깜빡이지 않는다. */
    fun step(from: LightLevel, to: LightPlan, t: Float): LightLevel {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        val color = (0xFF shl 24) or
            (mix(ch(from.color, 16), ch(to.color, 16)) shl 16) or
            (mix(ch(from.color, 8), ch(to.color, 8)) shl 8) or
            mix(ch(from.color, 0), ch(to.color, 0))
        return LightLevel(color, (from.brightness + (to.brightness - from.brightness) * t).toInt())
    }
}
