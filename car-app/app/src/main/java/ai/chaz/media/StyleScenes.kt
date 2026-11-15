package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/** 스팟 카드 한 장 (트립 로그). */
class SpotCut(val bitmap: Bitmap, val place: String, val time: String)

/**
 * ① 트립 로그 (15초): 0~2초 경로선이 그려지며 제목 → 2~10초 스팟마다 대표 컷이 카드로 등장(경로 위 핀이 따라감)
 * → 10~13초 4컷 그리드 → 13~15초 요약 카드(스팟 수·사진 수) 후 루프.
 * 지도 타일 없이 경로선만 (route = 위경도 목록, 없으면 완만한 곡선).
 */
class TripLogScene(
    private val cuts: List<SpotCut>,
    private val title: String,
    private val line: String?,
    private val dateText: String,
    route: List<Pair<Double, Double>>,
    private val aiTitle: Boolean,
) : ReelScene {
    override val durationMs = 15_000L
    override val mood = BgmMood.BRIGHT
    private val n = cuts.size
    private val bg = ReelDraw.blur(cuts.first().bitmap)
    /** 화면 좌표 경로 (핵심 영역 위쪽 절반에 맞춤). */
    private val pts: List<Pair<Float, Float>> = layoutRoute(route)

    private fun layoutRoute(route: List<Pair<Double, Double>>): List<Pair<Float, Float>> {
        val box = RectF(ReelDraw.SIDE + 40f, ReelDraw.core.t + 120f, ReelDraw.W - ReelDraw.SIDE - 40f, ReelDraw.core.t + 620f)
        if (route.size < 2) return (0..80).map { i -> val u = i / 80f; box.left + box.width() * u to box.bottom - box.height() * (0.5f - 0.5f * cos(u * Math.PI.toFloat())) }
        val k = cos(Math.toRadians(route.map { it.first }.average())) // 경도 1도는 위도 1도보다 짧다
        val minLat = route.minOf { it.first }; val maxLat = route.maxOf { it.first }
        val minLon = route.minOf { it.second }; val maxLon = route.maxOf { it.second }
        val sx = box.width() / ((maxLon - minLon) * k).coerceAtLeast(1e-6); val sy = box.height() / (maxLat - minLat).coerceAtLeast(1e-6)
        val s = minOf(sx, sy)
        val ox = box.left + (box.width() - (maxLon - minLon) * k * s).toFloat() / 2
        val oy = box.top + (box.height() - (maxLat - minLat) * s).toFloat() / 2
        val raw = route.map { (lat, lon) -> (ox + ((lon - minLon) * k * s).toFloat()) to (oy + ((maxLat - lat) * s).toFloat()) }
        // 점 사이를 촘촘히 채워 그려지는 애니메이션이 매끄럽게
        return raw.zipWithNext().flatMap { (a, b) -> (0 until 10).map { i -> val u = i / 10f; (a.first + (b.first - a.first) * u) to (a.second + (b.second - a.second) * u) } } + raw.last()
    }

    override fun draw(c: Canvas, tMs: Long) {
        ReelDraw.background(c, bg, dim = 150)
        when {
            tMs < 2_000 -> { drawRoute(c, tMs / 2_000f, -1); ReelDraw.title(c, title, dateText, alpha = (255 * (tMs / 700f).coerceAtMost(1f)).toInt()); line?.let { ReelDraw.centered(c, it, ReelDraw.core.t + 760f, 44f, alpha = (255 * ((tMs - 600) / 700f).coerceIn(0f, 1f)).toInt()) } }
            tMs < 10_000 -> {
                val per = 8_000L / n
                val i = ((tMs - 2_000) / per).toInt().coerceIn(0, n - 1)
                val u = ((tMs - 2_000 - i * per).toFloat() / per)
                drawRoute(c, 1f, i, faint = true)
                val cw = ReelDraw.core.w * 0.78f
                val b = cuts[i].bitmap
                val ch = cw * b.height / b.width
                val cy = ReelDraw.core.t + ReelDraw.core.h * 0.58f
                val s = 0.9f + 0.1f * ReelDraw.ease(u * 4)
                val r = RectF(ReelDraw.W / 2 - cw / 2 * s, cy - ch / 2 * s, ReelDraw.W / 2 + cw / 2 * s, cy + ch / 2 * s)
                ReelDraw.card(c, b, r, rot = if (i % 2 == 0) -2f else 2f, alpha = (255 * ReelDraw.ease(u * 5)).toInt())
                ReelDraw.centered(c, "📍 ${cuts[i].place} · ${cuts[i].time}", r.bottom + 70f, 44f)
                ReelDraw.title(c, title, dateText)
            }
            tMs < 13_000 -> {
                val u = ReelDraw.ease((tMs - 10_000) / 500f)
                ReelTimeline.gridCells(minOf(n, 4), ReelDraw.core, 20f).forEachIndexed { i, b -> ReelDraw.card(c, cuts[i].bitmap, ReelDraw.box(b), alpha = (255 * u).toInt(), border = 10f) }
                ReelDraw.title(c, title, dateText)
            }
            else -> summary(c, ((tMs - 13_000) / 400f).coerceAtMost(1f))
        }
        if (aiTitle && tMs < 13_000) ReelDraw.aiBadge(c, "AI 제목")
        ReelDraw.mark(c)
    }

    override fun drawCover(c: Canvas) {
        ReelDraw.background(c, bg, dim = 150)
        ReelTimeline.gridCells(minOf(n, 4), ReelDraw.core, 20f).forEachIndexed { i, b -> ReelDraw.card(c, cuts[i].bitmap, ReelDraw.box(b), border = 10f) }
        ReelDraw.title(c, title, dateText)
        ReelDraw.mark(c)
    }

    private fun summary(c: Canvas, u: Float) {
        val a = (255 * u).toInt()
        val cy = ReelDraw.core.t + ReelDraw.core.h / 2
        ReelDraw.centered(c, "오늘의 드라이브", cy - 160f, 64f, alpha = a)
        ReelDraw.centered(c, "스팟 ${cuts.map { it.place }.distinct().size}곳 · 사진 ${n}장", cy - 40f, 46f, bold = false, alpha = a)
        ReelDraw.centered(c, dateText, cy + 50f, 40f, bold = false, alpha = a)
        ReelDraw.centered(c, "↺ 처음 장면으로", cy + 200f, 34f, color = Color.argb(200, 255, 255, 255), bold = false, alpha = a)
    }

    /** 경로선 (progress 까지), 스팟 핀 (지금 스팟 = 주황). */
    private fun drawRoute(c: Canvas, progress: Float, current: Int, faint: Boolean = false) {
        val upto = (pts.size * progress).toInt().coerceIn(2, pts.size)
        val path = Path().apply { moveTo(pts[0].first, pts[0].second); for (i in 1 until upto) lineTo(pts[i].first, pts[i].second) }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.argb(if (faint) 120 else 255, 80, 170, 255) })
        val pin = Paint(Paint.ANTI_ALIAS_FLAG)
        for (k in 0 until n) {
            val p = pts[((pts.size - 1) * (k + 0.5f) / n).toInt()]
            if (progress < (k + 0.5f) / n) continue
            pin.color = if (k == current) Color.rgb(255, 154, 60) else Color.WHITE
            c.drawCircle(p.first, p.second, if (k == current) 20f else 13f, pin)
        }
    }
}

/**
 * ③ 차박 나이트 (15초): 0~3초 노을 주행(첫 컷, 노을 색) → 3~7초 도착·정차(둘째 컷이 밤으로 저물고 별이 뜸)
 * → 7~12초 차 안 4컷 + 별하늘 → 12~15초 별 궤적 아래 장소·시각. AI(창밖 별하늘)가 없으면 블루톤 + 웜 실내 색보정(대체 경로).
 */
class CampNightScene(
    private val photos: List<Bitmap>,
    private val night: List<Bitmap>?,
    private val place: String,
    private val dateText: String,
    private val timeText: String,
) : ReelScene {
    override val durationMs = 15_000L
    override val mood = BgmMood.NIGHT
    private val n = photos.size
    private val stars = java.util.Random(11).let { r -> List(140) { Triple(r.nextFloat() * ReelDraw.W, r.nextFloat() * ReelDraw.H * 0.75f, 1.5f + r.nextFloat() * 3f) } }
    private fun nightOf(i: Int) = night?.getOrNull(i) ?: photos[i]

    override fun draw(c: Canvas, tMs: Long) {
        when {
            tMs < 3_000 -> {
                ReelDraw.fill(c, photos[0], 0.5f, 1f + 0.04f * tMs / 3_000f)
                sunset(c, 1f)
                ReelDraw.title(c, "오늘 밤, 차박", dateText)
            }
            tMs < 7_000 -> {
                val u = (tMs - 3_000) / 4_000f
                val i = minOf(1, n - 1)
                // 실내 전체가 보이게(자르지 않음): 밤하늘 배경 위에 원본 → 밤 사진으로 저문다
                c.drawColor(Color.rgb(14, 18, 34)); sky(c, tMs, u)
                val cy = (ReelDraw.core.t + ReelDraw.core.b) / 2
                ReelDraw.fitWidth(c, photos[i], cy, 1f + 0.04f * u)
                if (night != null) ReelDraw.fitWidth(c, nightOf(i), cy, 1f + 0.04f * u, alpha = (255 * u).toInt())
                else { val b = photos[i]; val h = ReelDraw.W * b.height / b.width * (1f + 0.04f * u); nightGradeRect(c, RectF(0f, cy - h / 2, ReelDraw.W, cy + h / 2), u) }
                ReelDraw.title(c, "📍 $place", dateText)
            }
            tMs < 12_000 -> {
                c.drawColor(Color.rgb(14, 18, 34)); sky(c, tMs, 1f)
                val u = ReelDraw.ease((tMs - 7_000) / 500f)
                ReelTimeline.gridCells(minOf(n, 4), ReelDraw.core, 20f).forEachIndexed { i, b ->
                    ReelDraw.card(c, nightOf(i), ReelDraw.box(b), alpha = (255 * u).toInt(), border = 10f)
                    if (night == null) nightGradeRect(c, ReelDraw.box(b), 0.6f)
                }
                ReelDraw.title(c, "📍 $place", dateText)
            }
            else -> trails(c, (tMs - 12_000) / 3_000f)
        }
        if (night != null) ReelDraw.aiBadge(c)
        ReelDraw.mark(c)
    }

    override fun drawCover(c: Canvas) {
        trails(c, 1f)
        if (night != null) ReelDraw.aiBadge(c)
        ReelDraw.mark(c)
    }

    private fun sunset(c: Canvas, a: Float) {
        c.drawRect(0f, 0f, ReelDraw.W, ReelDraw.H, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, ReelDraw.H, intArrayOf(Color.argb((150 * a).toInt(), 90, 60, 160), Color.argb((110 * a).toInt(), 255, 120, 60), Color.argb(0, 0, 0, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** 블루톤(밤) + 가운데 웜톤(실내 조명) — AI 없을 때의 대체 색보정. */
    private fun nightGrade(c: Canvas, u: Float) = nightGradeRect(c, RectF(0f, 0f, ReelDraw.W, ReelDraw.H), u)
    private fun nightGradeRect(c: Canvas, r: RectF, u: Float) {
        c.drawRect(r, Paint().apply { color = Color.argb((170 * u).toInt(), 10, 20, 60) })
        c.drawRect(r, Paint().apply {
            shader = android.graphics.RadialGradient(r.centerX(), r.centerY() + r.height() * 0.1f, r.width() * 0.55f, intArrayOf(Color.argb((90 * u).toInt(), 255, 170, 80), Color.argb(0, 255, 170, 80)), null, Shader.TileMode.CLAMP)
        })
    }

    private fun sky(c: Canvas, tMs: Long, a: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        stars.forEachIndexed { k, (x, y, r) ->
            val tw = 0.6f + 0.4f * sin(tMs / 300f + k).toFloat()
            p.color = Color.argb((220 * a * tw).toInt().coerceIn(0, 255), 255, 255, 240)
            c.drawCircle(x, y, r, p)
        }
    }

    /** 별 궤적 (북극성 주위 원호) + 마지막 컷 + 장소·시각. */
    private fun trails(c: Canvas, u: Float) {
        c.drawColor(Color.rgb(10, 14, 28))
        val cx = ReelDraw.W * 0.7f; val cy = ReelDraw.core.t + 120f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        val sweep = 40f + 60f * u
        java.util.Random(5).let { r ->
            repeat(70) {
                val rad = 60f + r.nextFloat() * 1300f
                val start = r.nextFloat() * 360f
                p.color = Color.argb(90 + r.nextInt(120), 220, 230, 255)
                c.drawArc(RectF(cx - rad, cy - rad, cx + rad, cy + rad), start, sweep, false, p)
            }
        }
        val last = nightOf(n - 1)
        val cw = ReelDraw.core.w * 0.8f; val ch = cw * last.height / last.width
        val ry = ReelDraw.core.b - ch - 120f
        ReelDraw.card(c, last, RectF(ReelDraw.W / 2 - cw / 2, ry, ReelDraw.W / 2 + cw / 2, ry + ch), border = 10f)
        if (night == null) nightGradeRect(c, RectF(ReelDraw.W / 2 - cw / 2, ry, ReelDraw.W / 2 + cw / 2, ry + ch), 0.6f)
        ReelDraw.centered(c, "$place · $timeText", ReelDraw.core.b - 40f, 48f)
        ReelDraw.title(c, "오늘 밤, 차박", dateText)
    }
}

/**
 * ④ 펫 드라이버 반전형 (장수×2초 + 3초): 앞 컷들은 원본, 마지막 컷에서 자리 교체("N번째 컷에서 운전자 교체"),
 * 끝 3초는 원본/필터 비교. 모든 컷에 'AI 필터' 표기 고정(실제 상황으로 오해 방지). swapped 가 AI 실패로 null 이면
 * 좌우 반전(운전석↔조수석) + 앞발 스티커로 대체.
 */
class PetDriverScene(
    private val photos: List<Bitmap>,
    swapped: Bitmap?,
    private val dateText: String,
) : ReelScene {
    private val n = photos.size
    private val ai = swapped != null
    private val filtered: Bitmap = swapped ?: mirror(photos.last())
    override val durationMs = n * 2_000L + 3_000L
    override val mood = BgmMood.PLAYFUL
    private val bgs = photos.map { ReelDraw.blur(it) }
    private val label = if (ai) "AI 필터" else "필터"

    override fun draw(c: Canvas, tMs: Long) {
        val cy = (ReelDraw.core.t + ReelDraw.core.b) / 2
        if (tMs < n * 2_000L) {
            val i = (tMs / 2_000).toInt()
            val u = (tMs % 2_000) / 2_000f
            ReelDraw.background(c, bgs[i])
            val last = i == n - 1
            if (last && u < 0.25f) {
                // 휙 바뀜 (가로로 눌렸다 펴짐)
                val s = kotlin.math.abs(1f - u * 8f).coerceAtLeast(0.05f)
                c.save(); c.scale(s, 1f, ReelDraw.W / 2, cy)
                ReelDraw.fitWidth(c, if (u < 0.125f) photos[i] else filtered, cy, 1f)
                c.restore()
            } else ReelDraw.fitWidth(c, if (last) filtered else photos[i], cy, 1f + 0.06f * u)
            if (last) ReelDraw.centered(c, "${n}번째 컷에서 운전자 교체", ReelDraw.core.b - 30f, 50f)
            if (last && !ai) paw(c, cy)
            ReelDraw.title(c, "오늘의 운전자", dateText)
        } else drawCompare(c)
        ReelDraw.aiBadge(c, label)
        ReelDraw.mark(c)
    }

    override fun drawCover(c: Canvas) { drawCompare(c); ReelDraw.aiBadge(c, label); ReelDraw.mark(c) }

    private fun drawCompare(c: Canvas) {
        ReelDraw.background(c, bgs.last())
        val core = ReelDraw.core
        val half = (core.h - 30f) / 2
        ReelDraw.card(c, photos.last(), RectF(core.l, core.t, core.r, core.t + half), border = 10f)
        ReelDraw.card(c, filtered, RectF(core.l, core.b - half, core.r, core.b), border = 10f)
        ReelDraw.centered(c, "원본", core.t + 60f, 40f)
        ReelDraw.centered(c, label, core.b - half + 60f, 40f)
        ReelDraw.title(c, "몇 번째 컷이 제일 웃겨요?", null, size = 64f)
    }

    private fun paw(c: Canvas, cy: Float) {
        val p = Draw.text(Color.WHITE, 120f)
        c.drawText("🐾", ReelDraw.W * 0.62f, cy + 40f, p)
    }

    private fun mirror(b: Bitmap): Bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { preScale(-1f, 1f) }, true)
}

/**
 * ⑤ 로드 무비 (15초): 0~3초 검은 화면 자막 → 3~9초 전방 영상을 2.39:1 레터박스로 + 자막 한 줄씩
 * → 9~12초 실내 4컷 빠른 컷 편집 → 12~15초 가상 제목 + 크레딧(주연 동승자 · 촬영 차~즈) = 커버.
 * 카피는 AI(실존 작품명 금지 프롬프트) — 실패하면 템플릿 카피.
 */
class RoadMovieScene(
    private val front: List<Bitmap>,
    private val cuts: List<Bitmap>,
    private val title: String,
    private val lines: List<String>,
    private val tagline: String,
    private val place: String,
    private val dateText: String,
    private val aiCopy: Boolean,
) : ReelScene {
    override val durationMs = 15_000L
    override val mood = BgmMood.TRAILER
    private val bandH = ReelDraw.W / 2.39f
    private val band = RectF(0f, (ReelDraw.core.t + ReelDraw.core.b) / 2 - bandH / 2, ReelDraw.W, (ReelDraw.core.t + ReelDraw.core.b) / 2 + bandH / 2)

    override fun draw(c: Canvas, tMs: Long) {
        c.drawColor(Color.rgb(8, 8, 10))
        when {
            tMs < 3_000 -> {
                val a = (255 * minOf(1f, tMs / 600f, (3_000 - tMs) / 400f)).toInt().coerceIn(0, 255)
                ReelDraw.centered(c, lines.getOrElse(0) { "이번 주말," }, ReelDraw.H / 2 - 40f, 66f, alpha = a)
            }
            tMs < 9_000 -> {
                val u = (tMs - 3_000) / 6_000f
                val f = front[((front.size - 1) * u).toInt().coerceIn(0, front.size - 1)]
                c.save(); c.clipRect(band)
                c.drawBitmap(f, Draw.crop(f.width, f.height, band.width() / band.height()), RectF(band).apply { inset(-band.width() * 0.04f * u, -band.height() * 0.04f * u) }, ReelDraw.paint)
                c.restore()
                val line = lines.getOrElse(if (u < 0.5f) 1 else 2) { "" }
                ReelDraw.centered(c, "“$line”", band.bottom + 90f, 46f, bold = false)
            }
            tMs < 12_000 -> {
                val k = ((tMs - 9_000) / 750).toInt() % cuts.size
                val b = cuts[k]
                c.save(); c.clipRect(band)
                c.drawBitmap(b, Draw.crop(b.width, b.height, band.width() / band.height()), band, ReelDraw.paint)
                c.restore()
            }
            else -> titleCard(c, ((tMs - 12_000) / 500f).coerceAtMost(1f))
        }
        if (aiCopy && (tMs < 3_000 || tMs >= 12_000)) ReelDraw.aiBadge(c, "AI 카피")
        ReelDraw.mark(c)
    }

    override fun drawCover(c: Canvas) {
        c.drawColor(Color.rgb(8, 8, 10)); titleCard(c, 1f)
        if (aiCopy) ReelDraw.aiBadge(c, "AI 카피")
        ReelDraw.mark(c)
    }

    private fun titleCard(c: Canvas, u: Float) {
        val a = (255 * u).toInt()
        val cy = ReelDraw.H / 2 - 120f
        ReelDraw.centered(c, title, cy, 96f, alpha = a)
        c.drawRect(ReelDraw.W / 2 - 160f, cy + 40f, ReelDraw.W / 2 + 160f, cy + 44f, Paint().apply { color = Color.argb(a, 255, 255, 255) })
        ReelDraw.centered(c, tagline, cy + 120f, 40f, bold = false, alpha = a)
        ReelDraw.centered(c, "주연 동승자 · 촬영 차~즈", cy + 210f, 34f, color = Color.rgb(200, 200, 200), bold = false, alpha = a)
        ReelDraw.centered(c, "$dateText · $place", cy + 270f, 34f, color = Color.rgb(200, 200, 200), bold = false, alpha = a)
    }
}
