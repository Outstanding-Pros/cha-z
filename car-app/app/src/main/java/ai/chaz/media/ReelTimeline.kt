package ai.chaz.media

/**
 * 릴스(세로 9:16) 규격과 연출 시간축 — 스타일 기획서(릴스 규격) 11/13.
 * 그림 없이 '몇 초에 무엇을 보여 주나'만 정한다(단위 테스트 대상). 그리는 건 ReelRenderer.
 */
object ReelSpec {
    const val W = 1080
    const val H = 1920
    const val FPS = 30
    /** 안전 영역: 텍스트·얼굴·로고는 이 안에. */
    const val SAFE_TOP = 270
    const val SAFE_BOTTOM = 670
    const val SAFE_SIDE = 65
    /** 핵심 영역 약 950×980 (가운데). */
    const val CORE_W = 950
    const val CORE_H = 980
}

/** 연출 — 같은 셀렉으로 만드는 릴스. */
enum class ReelMotion(val label: String, val desc: String, val minPicks: Int) {
    GRID("한 장 그리드", "고른 사진을 한 화면에 모아 살짝 줌인 (6초)", 1),
    SEQUENCE("1컷 이어붙이기", "한 장씩 2초 간격으로 넘겨요 (장수 × 2초)", 2),
    STACK("쌓아서 공개", "한 장씩 보여 준 뒤 마지막에 한 화면으로 합쳐 공개", 2),
    CROP_ZOOM("크롭·줌", "한 장에서 얼굴 클로즈업 → 사람 → 전체로 줌아웃 (1장부터)", 1),
}

/** 한 순간의 화면. single = 꽉 차게 보여 줄 사진 번호(없으면 그리드). progress = 그 장면 안에서 0~1. */
data class ReelMoment(val single: Int?, val progress: Float, val reveal: Float = 1f)

/** 화면 위 사각형 (android.graphics 없이 테스트하려고 따로 둔다). */
data class ReelBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
}

object ReelTimeline {
    /** 한 박자 = BPM 120 의 4박 = 2초. */
    const val BEAT_MS = 2_000L
    const val GRID_MS = 6_000L
    const val REVEAL_MS = 2_000L
    /** 공개 애니메이션(그리드가 커지며 나타남) 길이. */
    const val POP_MS = 400L

    fun durationMs(m: ReelMotion, n: Int): Long = when (m) {
        ReelMotion.GRID -> GRID_MS
        ReelMotion.SEQUENCE -> n * BEAT_MS
        ReelMotion.STACK -> n * BEAT_MS + REVEAL_MS
        ReelMotion.CROP_ZOOM -> 3 * cropBeat(n) * n
    }

    /** 크롭·줌 한 단계 길이: 1장이면 2초(6초), 여러 장이면 1초(장당 3초). */
    fun cropBeat(n: Int): Long = if (n <= 1) BEAT_MS else BEAT_MS / 2

    fun frames(m: ReelMotion, n: Int): Int = (durationMs(m, n) * ReelSpec.FPS / 1000).toInt()

    fun at(m: ReelMotion, n: Int, tMs: Long): ReelMoment {
        val d = durationMs(m, n)
        val t = tMs.coerceIn(0, d - 1)
        return when (m) {
            ReelMotion.GRID -> ReelMoment(null, t.toFloat() / d)
            ReelMotion.CROP_ZOOM -> ReelMoment((t / (3 * cropBeat(n))).toInt(), (t % (3 * cropBeat(n))).toFloat() / (3 * cropBeat(n)))
            ReelMotion.SEQUENCE -> ReelMoment((t / BEAT_MS).toInt(), (t % BEAT_MS).toFloat() / BEAT_MS)
            ReelMotion.STACK -> if (t < n * BEAT_MS) ReelMoment((t / BEAT_MS).toInt(), (t % BEAT_MS).toFloat() / BEAT_MS)
            else {
                val u = t - n * BEAT_MS
                ReelMoment(null, u.toFloat() / REVEAL_MS, reveal = (u.toFloat() / POP_MS).coerceAtMost(1f))
            }
        }
    }

    /** 그리드 칸 (1·2·3·4장). area 안을 gap 간격으로 나눈다. */
    fun gridCells(n: Int, area: ReelBox, gap: Float): List<ReelBox> {
        val halfW = (area.w - gap) / 2
        val halfH = (area.h - gap) / 2
        fun row(t: Float, b: Float) = ReelBox(area.l, t, area.r, b)
        fun two(t: Float, b: Float) = listOf(ReelBox(area.l, t, area.l + halfW, b), ReelBox(area.r - halfW, t, area.r, b))
        val top = area.t; val mid = area.t + halfH; val bot = area.b
        return when (n.coerceIn(1, 4)) {
            1 -> listOf(area)
            2 -> listOf(row(top, mid), row(bot - halfH, bot))
            3 -> listOf(row(top, mid)) + two(bot - halfH, bot)
            else -> two(top, mid) + two(bot - halfH, bot)
        }
    }
}
