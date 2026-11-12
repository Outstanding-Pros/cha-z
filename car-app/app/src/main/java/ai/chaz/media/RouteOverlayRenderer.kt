package ai.chaz.media

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 여행 경로 정보 (L3). 좌표는 (위도, 경도). 지금은 데모 경로 + 현재 위치 + 다음 스팟으로 채운다.
 * here 가 경로 근처면 지나온 구간을 진하게, 남은 구간을 점선으로 그린다.
 */
data class RouteInfo(
    val points: List<Pair<Double, Double>>,
    val here: Pair<Double, Double>? = null,
    val destination: String? = null,
    val remainingM: Double? = null,
    val etaText: String? = null,
    /** 목적지(다음 스팟) 좌표 — 주황 핀. */
    val destPoint: Pair<Double, Double>? = null,
)

/**
 * L3 여행 경로형 (SPEC F5) — 전방 풍경 위에 손글씨 캡션, 경로·위치 카드, 동그란 인물 사진.
 * 컷 1장. 지도 타일은 쓰지 않고 경로 모양만 그린다 (외부 지도 이미지 라이선스·네트워크 의존 없음).
 */
class RouteOverlayRenderer(private val segmenter: PersonSegmenter) : LayoutRenderer {
    override val layout = Layout.ROUTE_OVERLAY
    override val maxCuts = 1

    override suspend fun render(cuts: List<CutImages>, opts: RenderOptions, scale: Float): Bitmap {
        val cut = cuts.firstOrNull() ?: error("컷을 하나 골라 주세요")
        val mask = cut.interior?.let { runCatching { segmenter.segment(it) }.getOrNull() }
        return withContext(Dispatchers.Default) {
            val s = scale
            val w = (1600 * s).toInt()
            val h = (900 * s).toInt()
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val all = RectF(0f, 0f, w.toFloat(), h.toFloat())

            // 1) 전방 풍경 (필터는 사진에만)
            cv.saveLayer(all, Paint().apply { colorFilter = opts.filter.colorFilter() })
            val bg = cut.front ?: cut.rear
            if (bg != null) Draw.drawCover(cv, bg, all, 0.5f) else cv.drawColor(0xFF2A3440.toInt())
            Draw.vignette(cv, all, 0.45f)
            // 위·아래 글씨가 풍경 위에서도 읽히게 살짝 어둡게
            cv.drawRect(0f, 0f, w.toFloat(), h * 0.32f, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h * 0.32f, 0x8C000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            })
            cv.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), 0x00000000, 0x73000000, Shader.TileMode.CLAMP)
            })
            // 2) 동그란 인물 사진 (오른쪽 아래)
            cut.interior?.let { portrait(cv, it, mask, RectF(w - 330 * s, h - 360 * s, w - 50 * s, h - 80 * s), s) }
            Draw.grain(cv, all, s, alpha = 30)
            cv.restore()

            // 3) 손글씨 캡션 + 날짜
            val caption = opts.stamp.place ?: "오늘의 드라이브"
            val hand = Draw.hand(0xFFFFFFFF.toInt(), 92 * s).apply { setShadowLayer(10 * s, 0f, 3 * s, 0x99000000.toInt()) }
            cv.drawText(caption, 64 * s, 138 * s, hand)
            if (opts.showStamp) {
                val info = Draw.text(0xEEFFFFFF.toInt(), 24 * s).apply { letterSpacing = 0.04f; setShadowLayer(6 * s, 0f, 2 * s, 0xAA000000.toInt()) }
                cv.drawText(Draw.stampLine(opts.stamp), 68 * s, 186 * s, info)
            }
            Draw.dateStamp(cv, Draw.filmDate(opts.stamp.dateText), w - 56 * s, 100 * s, 34 * s)

            // 4) 경로·위치 카드 (왼쪽 아래)
            routeCard(cv, RectF(56 * s, h - 360 * s, 56 * s + 560 * s, h - 56 * s), opts.route, s)

            // 5) 로고 + 시뮬레이션 표기
            val logo = Draw.text(0xFFFFFFFF.toInt(), 30 * s, bold = true).apply { letterSpacing = 0.08f; setShadowLayer(6 * s, 0f, 2 * s, 0x99000000.toInt()) }
            cv.drawText("CHA~Z", w - 190 * s - logo.measureText("CHA~Z") / 2, h - 34 * s, logo)
            opts.simulationNote?.let { n ->
                val p = Draw.text(0xCCFFFFFF.toInt(), 17 * s).apply { setShadowLayer(4 * s, 0f, 1 * s, 0xAA000000.toInt()) }
                cv.drawText(n, w - 56 * s - p.measureText(n), 136 * s, p)
            }
            out
        }
    }

    /** 실내 컷에서 얼굴·상반신 쪽을 정사각형으로 잘라 원 안에 넣고 흰 테두리 + 그림자. */
    private fun portrait(cv: Canvas, interior: Bitmap, mask: PersonMask?, dst: RectF, s: Float) {
        val src: Rect = if (mask != null && !mask.isEmpty) {
            val mw = mask.right - mask.left
            val mh = mask.bottom - mask.top
            val side = (maxOf(mw * 1.05f, mh * 0.62f)).toInt().coerceAtMost(minOf(interior.width, interior.height))
            val cx = (mask.left + mw / 2).coerceIn(side / 2, interior.width - side / 2)
            val top = (mask.top - mh * 0.06f).toInt().coerceIn(0, interior.height - side)
            Rect(cx - side / 2, top, cx + side / 2, top + side)
        } else Draw.crop(interior.width, interior.height, 1f, 0.35f)
        val r = dst.width() / 2
        cv.drawCircle(dst.centerX(), dst.centerY() + 6 * s, r + 8 * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66000000; maskFilter = BlurMaskFilter(16 * s, BlurMaskFilter.Blur.NORMAL)
        })
        cv.drawCircle(dst.centerX(), dst.centerY(), r + 8 * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7F2E8.toInt() })
        cv.save()
        cv.clipPath(Path().apply { addCircle(dst.centerX(), dst.centerY(), r, Path.Direction.CW) })
        cv.drawBitmap(interior, src, dst, Draw.filterPaint())
        cv.restore()
    }

    private fun routeCard(cv: Canvas, card: RectF, route: RouteInfo?, s: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3101418.toInt() }
        cv.drawRoundRect(card, 28 * s, 28 * s, fill)
        cv.drawRoundRect(card, 28 * s, 28 * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * s; color = 0x33FFFFFF
        })
        val pad = 28 * s
        val title = Draw.text(0xFFFFFFFF.toInt(), 30 * s, bold = true)
        val sub = Draw.text(0xFFB8C2CC.toInt(), 22 * s)
        val dest = route?.destination ?: "오늘의 여정"
        cv.drawText(ellipsize(dest, title, card.width() - pad * 2), card.left + pad, card.top + pad + 26 * s, title)
        val line = listOfNotNull(
            route?.remainingM?.let { "남은 거리 " + if (it >= 1000) "%.1fkm".format(it / 1000) else "${it.toInt()}m" },
            route?.etaText?.let { "도착 $it" },
        ).joinToString("  ·  ").ifEmpty { "지나온 길" }
        cv.drawText(line, card.left + pad, card.top + pad + 64 * s, sub)

        val map = RectF(card.left + pad, card.top + pad + 96 * s, card.right - pad, card.bottom - pad)
        val pts = route?.points.orEmpty()
        if (pts.size < 2) return
        val box = pts + listOfNotNull(route?.destPoint)
        val minLat = box.minOf { it.first }; val maxLat = box.maxOf { it.first }
        val minLon = box.minOf { it.second }; val maxLon = box.maxOf { it.second }
        // 위도·경도 축척을 같게 (위도에 따른 경도 축소 반영)
        val k = Math.cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = (maxLon - minLon) * k; val spanY = maxLat - minLat
        val sc = minOf(map.width() / spanX.coerceAtLeast(1e-5), map.height() / spanY.coerceAtLeast(1e-5)).toFloat()
        val ox = map.left + (map.width() - (spanX * sc).toFloat()) / 2
        val oy = map.top + (map.height() - (spanY * sc).toFloat()) / 2
        fun x(p: Pair<Double, Double>) = ox + ((p.second - minLon) * k * sc).toFloat()
        fun y(p: Pair<Double, Double>) = oy + ((maxLat - p.first) * sc).toFloat()

        // 현재 위치에 가장 가까운 경로 점까지 = 지나온 구간
        val here = route?.here
        val split = here?.let { hp ->
            val i = pts.indices.minBy { (pts[it].first - hp.first).let { a -> a * a } + ((pts[it].second - hp.second) * k).let { b -> b * b } }
            val d = Math.hypot(pts[i].first - hp.first, (pts[i].second - hp.second) * k)
            if (d < 0.01) i else null // 약 1km 밖이면 경로 위가 아님
        }
        fun path(from: Int, to: Int) = Path().apply { for (i in from..to) if (i == from) moveTo(x(pts[i]), y(pts[i])) else lineTo(x(pts[i]), y(pts[i])) }
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val rest = Paint(base).apply { strokeWidth = 6 * s; color = 0x99FFFFFF.toInt(); pathEffect = DashPathEffect(floatArrayOf(4 * s, 14 * s), 0f) }
        val done = Paint(base).apply { strokeWidth = 9 * s; color = 0xFF3D8BFF.toInt() }
        if (split == null) {
            cv.drawPath(path(0, pts.lastIndex), Paint(base).apply { strokeWidth = 8 * s; color = 0xFF3D8BFF.toInt() })
        } else {
            if (split < pts.lastIndex) cv.drawPath(path(split, pts.lastIndex), rest)
            if (split > 0) cv.drawPath(path(0, split), done)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        // 출발점
        cv.drawCircle(x(pts.first()), y(pts.first()), 8 * s, dot.apply { color = 0xFFFFFFFF.toInt() })
        cv.drawCircle(x(pts.last()), y(pts.last()), 6 * s, dot.apply { color = 0xCCFFFFFF.toInt() })
        // 목적지(다음 스팟) 핀 — 호박색, 없으면 경로 끝
        val d = route?.destPoint ?: pts.last()
        cv.drawCircle(x(d), y(d), 15 * s, dot.apply { color = 0xFF1A1206.toInt() })
        cv.drawCircle(x(d), y(d), 11 * s, dot.apply { color = 0xFFFF9A3C.toInt() })
        // 현재 위치 (흰 테두리 파란 점)
        split?.let {
            val p = pts[it]
            cv.drawCircle(x(p), y(p), 15 * s, dot.apply { color = 0xFFFFFFFF.toInt() })
            cv.drawCircle(x(p), y(p), 10 * s, dot.apply { color = 0xFF3D8BFF.toInt() })
        }
    }

    private fun ellipsize(t: String, p: Paint, max: Float): String {
        if (p.measureText(t) <= max) return t
        var e = t
        while (e.isNotEmpty() && p.measureText("$e…") > max) e = e.dropLast(1)
        return "$e…"
    }
}
