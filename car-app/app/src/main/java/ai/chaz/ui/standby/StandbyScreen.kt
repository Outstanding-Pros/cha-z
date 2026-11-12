package ai.chaz.ui.standby

import ai.chaz.AppGraph
import ai.chaz.capture.CameraRole
import ai.chaz.capture.CameraSource
import ai.chaz.capture.CameraSourceFactory
import ai.chaz.capture.CaptureSessionEngine
import ai.chaz.data.TriggerSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import ai.chaz.platform.Feature
import ai.chaz.ui.Card
import ai.chaz.ambience.LightPreset
import ai.chaz.capture.ShootMode
import ai.chaz.capture.SourceKind
import ai.chaz.media.ColorPreset
import ai.chaz.media.Layout
import ai.chaz.ui.Chip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import ai.chaz.ui.theme.Tokens
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * C1 촬영 대기 (정차 중) — 사양서 '촬영 장비 콘솔' 목업 배치:
 * 가운데 큰 뷰파인더, 그 아래 컨트롤 패널(모드·미니맵·촬영 설정·레이아웃·필터·원형 패드), 오른쪽 '카메라'·'맥락' 카드.
 */
@Composable
fun StandbyScreen(graph: AppGraph) {
    val sources by graph.cameras.collectAsState()
    val progress by graph.capture.progress.collectAsState()
    val prefs by graph.previewPrefs.collectAsState()
    var mainRole by rememberSaveable { mutableStateOf(CameraRole.INTERIOR) }
    val main = sources.firstOrNull { it.role == mainRole } ?: sources.firstOrNull()
    val others = sources.filter { it !== main }

    BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
        val landscape = maxWidth > maxHeight
        val viewfinder = @Composable { m: Modifier ->
            Viewfinder(m, main, others, onSelect = { mainRole = it.role }, live = prefs.layout?.let { l -> { mm: Modifier -> LiveComposite(graph, l, prefs.filter, mm) } }, filter = prefs.filter) {
                CaptureOverlay(progress)
            }
        }
        val panel = @Composable { m: Modifier -> ControlPanel(graph, progress, m) }
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    viewfinder(Modifier.weight(1f).fillMaxWidth())
                    panel(Modifier.fillMaxWidth())
                }
                Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CameraCard(graph, Modifier.fillMaxWidth())
                    ContextCard(graph, Modifier.fillMaxWidth().weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                viewfinder(Modifier.fillMaxWidth().weight(1f))
                panel(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CameraCard(graph, Modifier.weight(1f))
                    ContextCard(graph, Modifier.weight(1f).height(360.dp))
                }
            }
        }
    }
}

/** 하단 컨트롤 패널: 모드 토글, 미니맵, 촬영 설정, 레이아웃·필터 칩, 원형 컨트롤 패드. */
@Composable
private fun ControlPanel(graph: AppGraph, progress: CaptureSessionEngine.Progress?, modifier: Modifier) {
    val prefs by graph.previewPrefs.collectAsState()
    // L1 가능 여부는 실내 카메라(창문 마스크)에 달려 있다 → 카메라 목록이 바뀌면 다시 판정
    @Suppress("UNUSED_VARIABLE") val cams by graph.cameras.collectAsState()
    val layouts = listOf<Layout?>(null) + Layout.entries
    fun layoutOk(l: Layout?) = l == null || graph.composer.available(l)
    fun setLayout(l: Layout?) { if (layoutOk(l)) graph.previewPrefs.value = graph.previewPrefs.value.copy(layout = l) }
    fun setFilter(f: ColorPreset) { graph.previewPrefs.value = graph.previewPrefs.value.copy(filter = f) }

    Row(
        modifier.background(Tokens.surface, Tokens.cardShape).border(1.dp, Tokens.line, Tokens.cardShape).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Minimap(graph, Modifier.width(240.dp).height(212.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("사진", selected = true) {}
                Chip("영상 · 준비 중", selected = false, enabled = false) {}
                Chip("동시 · 준비 중", selected = false, enabled = false) {}
                Spacer(Modifier.weight(1f))
                val cfg = graph.capture.lastConfig
                Text("촬영 설정", color = Tokens.text2, fontSize = Tokens.labelSize)
                Text("${cfg.durationSec}초 · ${cfg.count}컷", color = Tokens.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = Tokens.tabular)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("레이아웃", color = Tokens.text2, fontSize = Tokens.labelSize, modifier = Modifier.width(72.dp))
                layouts.forEach { l ->
                    val label = when {
                        l == null -> "원본"
                        !layoutOk(l) && l == Layout.SURROUND_SEQUENCE -> "${l.id} · 준비 중" // 2차 PT 예정
                        prefs.layout == l -> "${l.id} ${l.label}"
                        else -> l.id
                    }
                    Chip(label, selected = prefs.layout == l, enabled = layoutOk(l)) { setLayout(l) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("필터", color = Tokens.text2, fontSize = Tokens.labelSize, modifier = Modifier.width(72.dp))
                ColorPreset.entries.forEach { f -> Chip(f.label, selected = prefs.filter == f) { setFilter(f) } }
            }
        }
        val usable = layouts.filter(::layoutOk)
        fun cycleLayout(d: Int) { val i = usable.indexOf(prefs.layout).coerceAtLeast(0); setLayout(usable[(i + d + usable.size) % usable.size]) }
        fun cycleFilter(d: Int) { val e = ColorPreset.entries; setFilter(e[(prefs.filter.ordinal + d + e.size) % e.size]) }
        ControlPad(
            capturing = progress != null,
            onShutter = { if (progress != null) graph.capture.stop() else graph.capture.start(TriggerSource.SHUTTER_BUTTON) },
            onLeft = { cycleLayout(-1) }, onRight = { cycleLayout(1) },
            onUp = { cycleFilter(-1) }, onDown = { cycleFilter(1) },
        )
    }
}

/** 원형 컨트롤 패드: 가운데 '차~즈' 셔터, 좌우 = 레이아웃, 상하 = 필터 (SPEC 화면 명세 5). */
@Composable
private fun ControlPad(capturing: Boolean, onShutter: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    Box(Modifier.size(212.dp).background(Tokens.surface2, CircleShape).border(1.dp, Tokens.line, CircleShape)) {
        PadArrow(Modifier.align(Alignment.TopCenter), 0f, "필터", onUp)
        PadArrow(Modifier.align(Alignment.BottomCenter), 180f, "", onDown)
        PadArrow(Modifier.align(Alignment.CenterStart), -90f, "", onLeft)
        PadArrow(Modifier.align(Alignment.CenterEnd), 90f, "", onRight)
        Box(
            Modifier.align(Alignment.Center).size(104.dp)
                .background(if (capturing) Tokens.rec else Tokens.accent, CircleShape)
                .border(4.dp, Tokens.text.copy(alpha = 0.9f), CircleShape)
                .clickable(onClick = onShutter),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (capturing) "중지" else "차~즈", color = Tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun PadArrow(modifier: Modifier, rot: Float, label: String, onClick: () -> Unit) {
    Column(
        modifier.size(width = 72.dp, height = 56.dp).clip(CircleShape).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (label.isNotEmpty() && rot == 0f) Text(label, color = Tokens.text2, fontSize = 12.sp)
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            rotate(rot) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.7f); lineTo(size.width * 0.5f, size.height * 0.3f); lineTo(size.width * 0.8f, size.height * 0.7f)
                }
                drawPath(p, Tokens.text2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
    }
}

/** 미니맵: 데모 경로 + 주변 스팟 + 현재 위치. 지도 타일 없이 경로 모양만 그린다. */
@Composable
private fun Minimap(graph: AppGraph, modifier: Modifier) {
    val here by graph.location.location.collectAsState()
    val spots by graph.spotGuide.spots.collectAsState()
    val nearest by graph.spotGuide.nearest.collectAsState()
    val route = graph.demoRoute
    Box(modifier.clip(Tokens.controlShape).background(Tokens.bg).border(1.dp, Tokens.line, Tokens.controlShape)) {
        // 축척은 경로 기준 (현재 위치가 경로 밖 멀리 있으면 경로가 점처럼 작아지므로)
        val pts = route.ifEmpty { listOfNotNull(here) }
        if (pts.isNotEmpty()) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(22.dp)) {
                val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
                val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
                val span = maxOf(maxLat - minLat, maxLon - minLon, 1e-4)
                val sc = minOf(size.width, size.height) / span.toFloat()
                val ox = (size.width - ((maxLon - minLon) * sc).toFloat()) / 2
                val oy = (size.height - ((maxLat - minLat) * sc).toFloat()) / 2
                fun at(p: ai.chaz.platform.navi.GeoPoint) = androidx.compose.ui.geometry.Offset(
                    ox + ((p.lon - minLon) * sc).toFloat(), size.height - oy - ((p.lat - minLat) * sc).toFloat(),
                )
                val path = androidx.compose.ui.graphics.Path().apply {
                    route.forEachIndexed { i, p -> val o = at(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                }
                drawPath(path, Tokens.accent.copy(alpha = 0.9f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                spots.forEach { s ->
                    val o = at(s.point)
                    if (o.x in -4f..size.width + 4f && o.y in -4f..size.height + 4f) {
                        drawCircle(if (nearest?.spot?.id == s.id) Tokens.warn else Tokens.text2, radius = 7f, center = o)
                    }
                }
                here?.let { h ->
                    val o = at(h)
                    if (o.x in 0f..size.width && o.y in 0f..size.height) { drawCircle(Tokens.text, 13f, o); drawCircle(Tokens.accent, 9f, o) }
                }
            }
        }
        Text(
            "미니맵", color = Tokens.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        )
        val onRoute = here != null && route.isNotEmpty() && here!!.let { h ->
            h.lat in route.minOf { it.lat } - 0.01..route.maxOf { it.lat } + 0.01 && h.lon in route.minOf { it.lon } - 0.01..route.maxOf { it.lon } + 0.01
        }
        Text(
            if (onRoute) "데모 경로" else if (here == null) "위치 없음" else "경로 밖",
            color = Tokens.text3, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        )
        if (false) Text("위치 없음", color = Tokens.text3, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

/** 오른쪽 위 '카메라' 카드: 주행 상태, 촬영 모드, 소스별 연결 상태, 저장 공간, 조명 프리셋. */
@Composable
private fun CameraCard(graph: AppGraph, modifier: Modifier) {
    val driving by graph.driving.status.collectAsState()
    val sources by graph.cameras.collectAsState()
    val mode by graph.shootMode.collectAsState()
    val preset by graph.ambience.preset.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    Card(modifier, title = "카메라", trailing = {
        ai.chaz.ui.StatusPill(if (driving.restricted) "주행 중" else "정차 중", if (driving.restricted) Tokens.rec else Tokens.ok)
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShootMode.entries.forEach { m -> Chip(m.label, selected = mode == m, modifier = Modifier.weight(1f)) { graph.setShootMode(m) } }
        }
        if (mode == ShootMode.LIVE && sources.none { it.role == CameraRole.INTERIOR }) {
            Text("폰에서 '실내 카메라로 쓰기'를 켜 주세요", color = Tokens.warn, fontSize = Tokens.labelSize, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(12.dp))
        listOf(CameraRole.INTERIOR, CameraRole.FRONT, CameraRole.REAR).forEach { role ->
            val s = sources.firstOrNull { it.role == role }
            val (state, c) = when {
                s == null -> "없음" to Tokens.rec
                s.simulated -> "시뮬레이션" to Tokens.warn
                s.kind == SourceKind.PHONE_CAMERA -> "폰 연결됨" to Tokens.ok
                else -> "연결됨" to Tokens.ok
            }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(c, CircleShape))
                Text(CameraSourceFactory.roleLabel(role), color = Tokens.text, fontSize = Tokens.bodySize, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                Spacer(Modifier.weight(1f))
                Text(state, color = c, fontSize = Tokens.labelSize)
            }
        }
        val freeGb = remember { runCatching { android.os.StatFs(context.filesDir.path).availableBytes / 1e9 }.getOrNull() }
        if (freeGb != null) {
            Row(Modifier.padding(vertical = 6.dp)) {
                Text("저장 공간", color = Tokens.text2, fontSize = Tokens.labelSize)
                Spacer(Modifier.weight(1f))
                Text("%.1fGB 남음".format(freeGb), color = Tokens.text2, fontSize = Tokens.labelSize, style = Tokens.tabular)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("조명", color = Tokens.text2, fontSize = Tokens.labelSize)
            Spacer(Modifier.weight(1f))
            listOf(LightPreset.GOLDEN, LightPreset.OCEAN, LightPreset.FOREST, LightPreset.CITY_NIGHT, LightPreset.COZY).forEach { p ->
                val sel = preset == p
                Box(
                    Modifier.padding(start = 6.dp).size(44.dp).clip(CircleShape)
                        .border(3.dp, if (sel) Tokens.text else Tokens.surface, CircleShape)
                        .clickable { scope.launch { graph.changeLight(p.label) } }
                        .padding(6.dp)
                        .background(androidx.compose.ui.graphics.Color(p.color), CircleShape),
                )
            }
        }
        Text(
            preset?.let { "지금 조명: ${it.label}" + if (graph.ambientLight.simulated) " (시뮬레이션)" else "" } ?: "조명 기본값",
            color = Tokens.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 오른쪽 아래 '맥락' 카드: 나침반, 날씨, 일몰까지 남은 시간, 다음 스팟까지 거리 + 음성 버튼. */
@Composable
private fun ContextCard(graph: AppGraph, modifier: Modifier) {
    val ctx by graph.context.collectAsState()
    val here by graph.location.location.collectAsState()
    val nearest by graph.spotGuide.nearest.collectAsState()
    val heard by graph.voice.lastHeard.collectAsState()
    LaunchedEffect(Unit) { graph.refreshContext() }
    // 진행 방향 = 직전 위치에서 지금 위치로의 방위각 (방향 값을 주는 API가 없어서 위치 변화로 추정)
    var prev by remember { mutableStateOf<ai.chaz.platform.navi.GeoPoint?>(null) }
    var heading by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(here) {
        val h = here
        val p = prev
        if (h != null && p != null && (h.lat != p.lat || h.lon != p.lon)) {
            val dLon = Math.toRadians(h.lon - p.lon)
            val y = sin(dLon) * cos(Math.toRadians(h.lat))
            val x = cos(Math.toRadians(p.lat)) * sin(Math.toRadians(h.lat)) - sin(Math.toRadians(p.lat)) * cos(Math.toRadians(h.lat)) * cos(dLon)
            heading = ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        }
        prev = h
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() }
    }

    Card(modifier, title = "맥락") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(contentAlignment = Alignment.TopCenter) {
                Compass(heading, Modifier.size(112.dp))
                Text("N", color = Tokens.text2, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ctx?.weather?.label ?: "—", color = Tokens.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(ctx?.timeBand?.label ?: "맥락 판정 중", color = Tokens.text2, fontSize = Tokens.labelSize)
                Text(heading?.let { "진행 방향 ${dir(it)}" } ?: "방향 —", color = Tokens.text2, fontSize = Tokens.labelSize)
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoRow("일몰까지", ctx?.sunsetMs?.let { untilText(it - now) } ?: "—")
        InfoRow("다음 스팟", nearest?.let { "${it.spot.name} · ${ai.chaz.ui.light.dist(it.distanceM)}" } ?: "근처 스팟 없음")
        Spacer(Modifier.weight(1f))
        heard?.let { Text("들은 말 “$it”", color = Tokens.text2, fontSize = Tokens.labelSize, maxLines = 1, modifier = Modifier.padding(bottom = 10.dp)) }
        Chip("음성 명령", selected = false, icon = ai.chaz.ui.ChazIcons.mic, modifier = Modifier.fillMaxWidth()) { graph.voice.voiceButton() }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tokens.text2, fontSize = Tokens.labelSize, modifier = Modifier.width(84.dp))
        Text(
            value, color = Tokens.text, fontSize = Tokens.bodySize, fontWeight = FontWeight.SemiBold, style = Tokens.tabular, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun untilText(ms: Long): String {
    if (ms <= 0) return "일몰 지남"
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

private fun dir(deg: Float): String = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")[(((deg + 22.5f) % 360) / 45).toInt() % 8]

@Composable
private fun Compass(heading: Float?, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(Tokens.surface2, r)
        drawCircle(Tokens.line, r, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        for (i in 0 until 12) {
            if (i == 0) continue // 'N' 자리
            rotate(i * 30f) {
                drawLine(Tokens.text3, center.copy(y = center.y - r + 6), center.copy(y = center.y - r + if (i % 3 == 0) 18 else 12), 2f)
            }
        }
        rotate(heading ?: 0f) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(center.x, center.y - r * 0.6f); lineTo(center.x + r * 0.2f, center.y + r * 0.3f)
                lineTo(center.x, center.y + r * 0.14f); lineTo(center.x - r * 0.2f, center.y + r * 0.3f); close()
            }
            drawPath(p, if (heading == null) Tokens.text3 else Tokens.accent)
        }
    }
}

/** C2 촬영 중 표시: 오른쪽 위 REC 타이머·컷 카운터, 가운데 카운트다운 (셔터·중지는 컨트롤 패드). */
@Composable
private fun BoxScope.CaptureOverlay(progress: CaptureSessionEngine.Progress?) {
    if (progress == null) return
    Row(
        Modifier.align(Alignment.TopEnd).padding(16.dp)
            .background(Tokens.bg.copy(alpha = 0.8f), Tokens.controlShape)
            .border(2.dp, Tokens.rec, Tokens.controlShape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(14.dp).background(Tokens.rec, CircleShape))
        val sec = progress.elapsedMs / 1000
        Text("REC %02d:%02d".format(sec / 60, sec % 60), color = Tokens.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = Tokens.tabular)
        Text("컷 ${progress.shotsTaken}/${progress.total}", color = Tokens.text, fontSize = 20.sp, style = Tokens.tabular)
    }
    val big = when {
        progress.phase == CaptureSessionEngine.Phase.ANNOUNCING -> "준비"
        progress.countdown != null -> progress.countdown.toString()
        else -> null
    }
    if (big != null) {
        Text(
            big, color = Tokens.text, fontSize = if (big.length == 1) 160.sp else 56.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center).background(Tokens.bg.copy(alpha = 0.35f), Tokens.cardShape).padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun Viewfinder(
    modifier: Modifier,
    main: CameraSource?,
    others: List<CameraSource>,
    onSelect: (CameraSource) -> Unit,
    live: (@Composable (Modifier) -> Unit)? = null,
    filter: ai.chaz.media.ColorPreset = ai.chaz.media.ColorPreset.ORIGINAL,
    overlay: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(Tokens.cardShape)
            .background(Tokens.surface)
            .border(1.dp, Tokens.line, Tokens.cardShape),
    ) {
        if (main == null) {
            Text("카메라 판정 중…", color = Tokens.text2, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        if (live != null) {
            live(Modifier.fillMaxSize())
            overlay()
            return@Box
        }
        SourcePreview(main, Modifier.fillMaxSize(), filter)
        ThirdsGrid(Modifier.fillMaxSize())

        // 왼쪽 위: 카메라 소스 칩
        Row(Modifier.align(Alignment.TopStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(main) + others).sortedBy { it.role.ordinal }.forEach { s ->
                val sel = s === main
                Box(
                    Modifier
                        .background(if (sel) Tokens.accent else Tokens.bg.copy(alpha = 0.7f), Tokens.controlShape)
                        .clickable { onSelect(s) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(CameraSourceFactory.roleLabel(s.role), color = Tokens.text, fontSize = 16.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (main.simulated) {
            // 대체 소스는 화면에 '시뮬레이션' 표기 (SPEC 구현 규칙 5). 밝은 하늘 위에서도 보이게 어두운 바탕
            Box(
                Modifier.align(Alignment.BottomStart).padding(16.dp)
                    .background(Tokens.bg.copy(alpha = 0.75f), Tokens.controlShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text("시뮬레이션", color = Tokens.warn, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }

        // 오른쪽 아래: 나머지 소스 타일 (누르면 메인으로)
        Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            others.forEach { s ->
                Box(
                    Modifier
                        .width(200.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Tokens.line, RoundedCornerShape(16.dp))
                        .clickable { onSelect(s) },
                ) {
                    SourcePreview(s, Modifier.fillMaxSize())
                    Text(
                        s.label, color = Tokens.text, fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomStart).background(Tokens.bg.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        overlay()
    }
}

@Composable
private fun SourcePreview(source: CameraSource, modifier: Modifier, filter: ai.chaz.media.ColorPreset = ai.chaz.media.ColorPreset.ORIGINAL) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val needsCamera = source.kind != ai.chaz.capture.SourceKind.RECORDED_VIDEO
    // 권한 대화상자가 닫히면 액티비티가 다시 RESUMED 가 된다 → 그때 권한을 다시 확인 (첫 실행에 허용해도 안 뜨던 문제)
    val lifecycleState by owner.lifecycle.currentStateFlow.collectAsState()
    val cameraGranted = remember(lifecycleState) {
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    if (needsCamera && !cameraGranted) {
        Box(modifier.background(Tokens.surface2), contentAlignment = Alignment.Center) {
            Text("카메라 권한이 필요해요", color = Tokens.text2, fontSize = 16.sp)
        }
        return
    }
    key(source) {
        AndroidView(
            factory = { ctx -> source.createPreview(ctx, owner) },
            update = { v ->
                // 원본 미리보기에도 같은 색 필터 (API 31+ RenderEffect, GPU)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    v.setRenderEffect(filter.colorFilter()?.let { android.graphics.RenderEffect.createColorFilterEffect(it) })
                }
            },
            modifier = modifier,
            onRelease = { view -> source.releasePreview(view) },
        )
    }
}

/** 라이브 합성 미리보기 (T09). 화면에 있는 동안만 수집 → 주행 잠금으로 덮이면 즉시 멈춘다. */
@Composable
private fun LiveComposite(graph: AppGraph, layout: ai.chaz.media.Layout, filter: ai.chaz.media.ColorPreset, modifier: Modifier) {
    val frame by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, layout, filter) {
        graph.live.frames(layout, filter).collect { value = it }
    }
    val stats by graph.live.stats.collectAsState()
    Box(modifier.background(Tokens.bg), contentAlignment = Alignment.Center) {
        frame?.let {
            androidx.compose.foundation.Image(
                it.asImageBitmap(), null, Modifier.fillMaxSize().padding(8.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        } ?: Text("미리보기 준비 중…", color = Tokens.text2, fontSize = 18.sp)
        Text(
            "LIVE ${layout.id} · ${"%.0f".format(stats.fps)}fps" + if (stats.segmentation) "" else " · 프레임만",
            color = Tokens.text, fontSize = 13.sp, style = Tokens.tabular,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                .background(Tokens.bg.copy(alpha = 0.7f), Tokens.controlShape).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 뷰파인더 3분할 그리드. */
@Composable
private fun ThirdsGrid(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = Tokens.text.copy(alpha = 0.18f)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1.5f)
            drawLine(c, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.5f)
        }
    }
}
