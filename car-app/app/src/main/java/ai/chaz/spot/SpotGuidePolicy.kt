package ai.chaz.spot

import ai.chaz.platform.navi.Geo
import ai.chaz.platform.navi.GeoPoint

/**
 * 선제 가이드 규칙 (SPEC F2) — 순수 함수, 단위 테스트.
 * - 스팟까지 400m 이내로 '접근 중'(거리가 줄어드는 중, 처음 본 위치면 그대로 인정)
 * - 가이드 간 최소 5분, 스팟당 여정 1회
 * - 회전 안내가 300m 안에 있으면 미룬다
 * - 일몰 전후 30분에 노을 가이드 여정당 1회
 */
class SpotGuidePolicy(
    private val triggerM: Double = 400.0,
    private val minGapMs: Long = 5 * 60_000L,
    private val tbtDeferM: Double = 300.0,
    private val sunsetWindowMs: Long = 30 * 60_000L,
) {
    data class State(
        val lastGuideAt: Long? = null,
        val guided: Set<String> = emptySet(),
        val prevDist: Map<String, Double> = emptyMap(),
        val sunsetGuided: Boolean = false,
    )

    sealed interface Decision {
        data object None : Decision
        data class SpotGuide(val spot: Spot, val distanceM: Double) : Decision
        data object SunsetGuide : Decision
    }

    fun decide(state: State, here: GeoPoint, spots: List<Spot>, nowMs: Long, nextTurnM: Double?, sunsetMs: Long?): Pair<Decision, State> {
        val dist = spots.associate { it.id to Geo.distanceM(here, it.point) }
        val next = state.copy(prevDist = dist)
        val gapOk = state.lastGuideAt == null || nowMs - state.lastGuideAt >= minGapMs
        val turnSoon = nextTurnM != null && nextTurnM <= tbtDeferM

        if (gapOk && !turnSoon) {
            val candidate = spots
                .filter { it.score >= 2 && it.id !in state.guided }
                .map { it to dist.getValue(it.id) }
                .filter { (s, d) -> d <= triggerM && (state.prevDist[s.id]?.let { prev -> d < prev } ?: true) }
                .sortedWith(compareByDescending<Pair<Spot, Double>> { it.first.score }.thenBy { it.second })
                .firstOrNull()
            if (candidate != null) {
                return Decision.SpotGuide(candidate.first, candidate.second) to
                    next.copy(lastGuideAt = nowMs, guided = state.guided + candidate.first.id)
            }
            if (!state.sunsetGuided && sunsetMs != null && kotlin.math.abs(nowMs - sunsetMs) <= sunsetWindowMs) {
                return Decision.SunsetGuide to next.copy(lastGuideAt = nowMs, sunsetGuided = true)
            }
        }
        return Decision.None to next
    }

    companion object {
        /** "곧 협재해수욕장이에요" / "곧 한담해변이에요" — 받침에 따라 이에요/예요. */
        fun copula(name: String): String {
            val c = name.lastOrNull() ?: return "이에요"
            if (c !in '가'..'힣') return "이에요"
            return if ((c - '가') % 28 != 0) "이에요" else "예요"
        }
    }
}
