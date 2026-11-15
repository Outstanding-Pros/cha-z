package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

/**
 * ② 오션 브릿지 시간축 (15초) — 스타일 기획서.
 * 0~2초 어둠(터널·오르막) → 2~4초 바다가 열리며 환해짐 → 4~12초 풍경이 옆으로 흐르고 실내 컷 1→4 가 차례로 얹힘
 * → 12~15초 4컷 정렬 + 장소·시각. 열리는 순간(전방 영상 밝기가 확 오르는 프레임)을 2초 지점에 맞춘다.
 */
object OceanBridgeTimeline {
    const val DURATION_MS = 15_000L
    const val OPEN_AT_MS = 2_000L
    const val OPENED_MS = 4_000L
    const val GRID_AT_MS = 12_000L
    const val CARD_EVERY_MS = 2_000L
    /** 스냅샷 간격 (동영상 촬영의 0.5초 스냅샷). */
    const val FRAME_MS = 500L

    /** 밝기(0~255)가 가장 크게 오르는 프레임 번호. 오름폭이 minJump 보다 작으면 -1 (열리는 순간 없음). */
    fun findOpening(lum: List<Float>, minJump: Float = 25f): Int {
        var best = -1; var bestD = minJump
        for (i in 1 until lum.size) {
            val d = lum[i] - lum[i - 1]
            if (d >= bestD) { bestD = d; best = i }
        }
        return best
    }

    /** 릴스 시각 t 에 보여 줄 전방 프레임 번호. 열리는 프레임이 2초에 오게 맞추고, 앞뒤는 0~count-1 로 자른다. */
    fun frameAt(tMs: Long, count: Int, opening: Int): Int {
        if (count <= 0) return 0
        val start = if (opening >= 0) opening - (OPEN_AT_MS / FRAME_MS).toInt() else 0
        // 4초 이후는 절반 속도로 흐르게 (12초 영상을 15초에 펼친다)
        val idx = if (tMs <= OPENED_MS) tMs / FRAME_MS else OPENED_MS / FRAME_MS + (tMs - OPENED_MS) / (FRAME_MS * 3 / 2)
        return (start + idx.toInt()).coerceIn(0, count - 1)
    }

    /** 어둠 덮개 (0 = 없음, 1 = 완전히 어두움). 열리는 순간이 원래 있으면 덜 덮는다. */
    fun darkness(tMs: Long, naturalOpening: Boolean): Float {
        val base = if (naturalOpening) 0.45f else 0.82f
        return when {
            tMs < OPEN_AT_MS -> base
            tMs < OPENED_MS -> base * (1f - (tMs - OPEN_AT_MS).toFloat() / (OPENED_MS - OPEN_AT_MS)).let { it * it }
            else -> 0f
        }
    }

    /** t 까지 얹힌 실내 컷 수 (4초부터 2초마다 한 장). */
    fun cardsShown(tMs: Long, n: Int): Int = if (tMs < OPENED_MS) 0 else minOf(n, 1 + ((tMs - OPENED_MS) / CARD_EVERY_MS).toInt())

    /** 가로 패닝 위치 0~1 (4~12초 동안 왼쪽 → 오른쪽). */
    fun pan(tMs: Long): Float = when {
        tMs < OPENED_MS -> 0.5f
        tMs >= GRID_AT_MS -> 0.85f
        else -> 0.5f + 0.35f * (tMs - OPENED_MS).toFloat() / (GRID_AT_MS - OPENED_MS)
    }
}

/**
 * ② 오션 브릿지 그림. front = 전방 0.5초 스냅샷들, cuts = 실내 컷(1~4).
 * 생성 단계(OpenCV 스티칭 + 좌우 아웃페인팅)는 아직 없어 기획서의 대체 경로 '전방 영상을 크롭해 패닝만'으로 그린다.
 * panorama 가 있으면(✨ 파노라마 드라이브) 4초 이후 배경을 그 파노라마로 바꿔 훑는다.
 */
class OceanBridgeScene(
    private val front: List<Bitmap>,
    private val cuts: List<Bitmap>,
    private val place: String,
    private val timeText: String,
    private val dateText: String,
    private val panorama: Bitmap? = null,
) : ReelScene {
    override val durationMs = OceanBridgeTimeline.DURATION_MS
    override val mood = BgmMood.OCEAN
    private val lum = front.map { b -> Draw.luminance(b, android.graphics.Rect(0, 0, b.width, b.height)) }
    private val opening = OceanBridgeTimeline.findOpening(lum)
    private val blurredSea: Bitmap = ReelDraw.blur(panorama ?: front.last())
    private val n = cuts.size

    override fun draw(c: Canvas, tMs: Long) {
        val T = OceanBridgeTimeline
        val f = front[T.frameAt(tMs, front.size, opening)]
        c.drawColor(Color.BLACK)
        val zoom = if (tMs < T.OPENED_MS) ReelDraw.lerp(1.18f, 1f, (tMs - T.OPEN_AT_MS).toFloat() / (T.OPENED_MS - T.OPEN_AT_MS)) else 1f
        if (panorama != null && tMs >= T.OPENED_MS) {
            val a = (255 * ((tMs - T.OPENED_MS) / 600f).coerceAtMost(1f)).toInt()
            ReelDraw.fill(c, f, T.pan(tMs), zoom)
            ReelDraw.fill(c, panorama, (T.pan(tMs) - 0.5f) / 0.35f, 1f, a)
        } else ReelDraw.fill(c, f, T.pan(tMs), zoom)
        // 어둠 → 열림 (+ 짧은 빛 번짐)
        val dark = T.darkness(tMs, opening >= 0)
        if (dark > 0f) c.drawColor(Color.argb((255 * dark).toInt(), 6, 10, 20))
        if (tMs in T.OPEN_AT_MS until T.OPEN_AT_MS + 700) {
            val u = (tMs - T.OPEN_AT_MS) / 700f
            c.drawColor(Color.argb((110 * (1 - u) * u * 4).toInt().coerceIn(0, 255), 255, 250, 235))
        }
        if (tMs >= T.GRID_AT_MS) drawGrid(c, tMs) else drawCards(c, tMs)
        // 글자
        if (tMs < T.OPEN_AT_MS) ReelDraw.centered(c, "곧 바다가 보여요", ReelDraw.H / 2, 54f, alpha = (255 * (tMs / 800f).coerceAtMost(1f)).toInt())
        else ReelDraw.title(c, "📍 $place", if (tMs >= T.OPENED_MS) dateText else null, alpha = (255 * ((tMs - T.OPEN_AT_MS) / 800f).coerceAtMost(1f)).toInt())
        if (panorama != null && tMs >= T.OPENED_MS) ReelDraw.aiBadge(c)
        ReelDraw.mark(c)
    }

    override fun drawCover(c: Canvas) {
        ReelDraw.fill(c, panorama ?: front[OceanBridgeTimeline.frameAt(10_000, front.size, opening)], 0.6f)
        drawGrid(c, OceanBridgeTimeline.DURATION_MS)
        ReelDraw.title(c, "📍 $place", dateText)
        if (panorama != null) ReelDraw.aiBadge(c)
        ReelDraw.mark(c)
    }

    /** 4~12초: 새 컷이 오른쪽에서 미끄러져 가운데에 크게, 앞 컷들은 아래에 작게 쌓인다. */
    private fun drawCards(c: Canvas, tMs: Long) {
        val shown = OceanBridgeTimeline.cardsShown(tMs, n)
        if (shown == 0) return
        val core = ReelDraw.core
        val thumbW = (core.w - 3 * 16f) / 4; val thumbH = thumbW * 0.75f
        for (k in 0 until shown - 1) {
            val x = core.l + k * (thumbW + 16f)
            ReelDraw.card(c, cuts[k], RectF(x, core.b - thumbH, x + thumbW, core.b), rot = if (k % 2 == 0) -3f else 3f, border = 6f)
        }
        val k = shown - 1
        val since = tMs - OceanBridgeTimeline.OPENED_MS - k * OceanBridgeTimeline.CARD_EVERY_MS
        val slide = ReelDraw.ease(since / 350f)
        val cw = core.w * 0.86f
        val ch = cw * cuts[k].height / cuts[k].width
        val cx = ReelDraw.lerp(ReelDraw.W + cw / 2, ReelDraw.W / 2, slide)
        val cy = core.t + (core.h - thumbH - 30f) / 2
        ReelDraw.card(c, cuts[k], RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2), rot = if (k % 2 == 0) -2f else 2f)
    }

    /** 12~15초: 4컷 정렬 + 장소·시각. */
    private fun drawGrid(c: Canvas, tMs: Long) {
        val u = ReelDraw.ease((tMs - OceanBridgeTimeline.GRID_AT_MS) / 500f)
        c.drawColor(Color.argb((70 * u).toInt(), 0, 0, 0))
        val core = ReelDraw.core
        val area = ReelBox(core.l, core.t, core.r, core.b - 90f)
        ReelTimeline.gridCells(n, area, 20f).forEachIndexed { i, b ->
            val r = ReelDraw.box(b)
            val s = 0.9f + 0.1f * u
            r.inset(r.width() * (1 - s) / 2, r.height() * (1 - s) / 2)
            ReelDraw.card(c, cuts[i], r, alpha = (255 * u).toInt(), border = 10f)
        }
        ReelDraw.centered(c, "$place · $timeText", core.b - 20f, 46f, alpha = (255 * u).toInt())
    }
}
