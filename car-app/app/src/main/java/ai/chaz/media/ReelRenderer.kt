package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** 릴스 한 편에 들어가는 사진 한 장. */
class ReelPhoto(val bitmap: Bitmap, val ai: Boolean, val caption: String?)

/**
 * 기본 릴스(직접 연출): 고른 사진 1~4장 + 연출(그리드·이어붙이기·쌓아서 공개·크롭줌).
 * - 이어붙이기 장면: 흐린 같은 사진을 배경으로, 가로 사진 전체를 가운데에 천천히 줌인.
 * - 그리드 장면: 핵심 영역 안에 1~4칸, 흰 테두리.
 * - 공통: 제목(장소·시각), '차~즈로 찍음', AI 사진이 보이면 'AI' 배지.
 */
class ReelRenderer(
    private val photos: List<ReelPhoto>,
    private val motion: ReelMotion,
    private val title: String,
    private val dateText: String,
    override val mood: BgmMood = BgmMood.BRIGHT,
    /** 크롭·줌 연출용: 사진마다 얼굴(없으면 사람) 영역 0~1 좌표. */
    private val focus: List<FocusBox?> = emptyList(),
    /** 사진에 덧입힐 색 (스타일 필터). */
    private val grade: ((Canvas) -> Unit)? = null,
    private val badge: String = "AI",
) : ReelScene {
    private val n = photos.size
    private val blurred: List<Bitmap> = photos.map { ReelDraw.blur(it.bitmap) }

    override val durationMs: Long get() = ReelTimeline.durationMs(motion, n)

    override fun draw(c: Canvas, tMs: Long) {
        if (motion == ReelMotion.CROP_ZOOM) { drawCropZoom(c, tMs); return }
        val m = ReelTimeline.at(motion, n, tMs)
        val single = m.single
        if (single != null) drawSingle(c, single, m.progress) else drawGrid(c, m)
        grade?.invoke(c)
        ReelDraw.title(c, if (single != null) photos[single].caption ?: title else title, dateText)
        if (single != null && n > 1) drawDots(c, single)
        val aiShown = if (single != null) photos[single].ai else photos.any { it.ai }
        if (aiShown) ReelDraw.aiBadge(c, badge)
        ReelDraw.mark(c)
    }

    /** 커버: 그리드가 다 보인 장면. */
    override fun drawCover(c: Canvas) {
        drawGrid(c, ReelMoment(null, 0f, 1f))
        grade?.invoke(c)
        ReelDraw.title(c, title, dateText)
        if (photos.any { it.ai }) ReelDraw.aiBadge(c, badge)
        ReelDraw.mark(c)
    }

    private fun drawSingle(c: Canvas, i: Int, p: Float) {
        ReelDraw.background(c, blurred[i])
        ReelDraw.fitWidth(c, photos[i].bitmap, (ReelDraw.core.t + ReelDraw.core.b) / 2, 1f + 0.08f * p)
    }

    private fun drawGrid(c: Canvas, m: ReelMoment) {
        ReelDraw.background(c, blurred.first())
        val zoom = 1f + 0.05f * (if (motion == ReelMotion.GRID) m.progress else 0f)
        val s = zoom * (0.85f + 0.15f * ReelDraw.ease(m.reveal))
        val core = ReelDraw.core
        c.save()
        c.scale(s, s, ReelDraw.W / 2, (core.t + core.b) / 2)
        val alpha = (255 * ReelDraw.ease(m.reveal)).toInt()
        ReelTimeline.gridCells(n, core, 22f).forEachIndexed { i, b -> ReelDraw.card(c, photos[i].bitmap, ReelDraw.box(b), alpha = alpha, border = 10f) }
        c.restore()
    }

    /** 크롭·줌: 사진마다 [얼굴 클로즈업 → 사람 → 전체] 3박자로 줌아웃. 마지막 장은 그리드 없이 끝. */
    private fun drawCropZoom(c: Canvas, tMs: Long) {
        val beat = ReelTimeline.cropBeat(n)
        val per = beat * 3
        val i = (tMs / per).toInt().coerceIn(0, n - 1)
        val u = (tMs - i * per).coerceIn(0, per - 1)
        val stage = (u / beat).toInt()
        val p = (u % beat).toFloat() / beat
        val bmp = photos[i].bitmap
        val f = focus.getOrNull(i) ?: FocusBox(0.35f, 0.25f, 0.65f, 0.75f)
        c.drawColor(Color.BLACK)
        // 목표 창(0~1 좌표): 0 = 얼굴 클로즈업, 1 = 얼굴 주변 넉넉히, 2 = 전체
        val windows = listOf(f.expand(1.6f), f.expand(3.2f), FocusBox(0f, 0f, 1f, 1f))
        val from = windows[stage]
        val to = windows.getOrElse(stage + 1) { windows[2] }
        val w = from.lerp(to, ReelDraw.ease(p * 0.6f))
        if (stage == 2) {
            ReelDraw.background(c, blurred[i])
            ReelDraw.fitWidth(c, bmp, (ReelDraw.core.t + ReelDraw.core.b) / 2, 1f + 0.05f * p)
        } else {
            // 9:16 창으로 잘라 화면 가득
            val src = w.toRect(bmp.width, bmp.height, ReelDraw.W / ReelDraw.H)
            c.drawBitmap(bmp, src, android.graphics.RectF(0f, 0f, ReelDraw.W, ReelDraw.H), ReelDraw.paint)
        }
        grade?.invoke(c)
        ReelDraw.title(c, photos[i].caption ?: title, dateText)
        if (photos[i].ai) ReelDraw.aiBadge(c, badge)
        if (n > 1) drawDots(c, i)
        ReelDraw.mark(c)
    }

    private fun drawDots(c: Canvas, cur: Int) {
        val r = 9f; val gap = 30f
        val y = ReelDraw.core.b + 34f
        val x0 = ReelDraw.W / 2 - gap * (n - 1) / 2
        val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }
        for (i in 0 until n) c.drawCircle(x0 + gap * i, y, r, if (i <= cur) on else off)
    }
}

/** 사진 안의 관심 영역 (0~1 좌표). */
data class FocusBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    fun expand(k: Float): FocusBox {
        val hw = (r - l) * k / 2; val hh = (b - t) * k / 2
        return FocusBox((cx - hw).coerceAtLeast(0f), (cy - hh).coerceAtLeast(0f), (cx + hw).coerceAtMost(1f), (cy + hh).coerceAtMost(1f))
    }
    fun lerp(o: FocusBox, t: Float) = FocusBox(l + (o.l - l) * t, this.t + (o.t - this.t) * t, r + (o.r - r) * t, b + (o.b - b) * t)
    /** 이 창을 품는, aspect(가로/세로) 비율의 원본 픽셀 사각형. */
    fun toRect(w: Int, h: Int, aspect: Float): android.graphics.Rect {
        var rw = (r - l) * w; var rh = (b - t) * h
        if (rw / rh > aspect) rh = rw / aspect else rw = rh * aspect
        if (rw > w) { rw = w.toFloat(); rh = rw / aspect }
        if (rh > h) { rh = h.toFloat(); rw = rh * aspect }
        val x = (cx * w - rw / 2).coerceIn(0f, w - rw)
        val y = (cy * h - rh / 2).coerceIn(0f, h - rh)
        return android.graphics.Rect(x.toInt(), y.toInt(), (x + rw).toInt(), (y + rh).toInt())
    }
}
