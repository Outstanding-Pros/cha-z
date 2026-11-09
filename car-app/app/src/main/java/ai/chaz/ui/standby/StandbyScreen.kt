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

/** C1 촬영 대기 (정차 중). 큰 뷰파인더 + 카메라 소스 칩 + 나머지 소스 타일. */
@Composable
fun StandbyScreen(graph: AppGraph) {
    val selections by graph.availability.state.collectAsState()
    val driving by graph.driving.status.collectAsState()
    val sources by graph.cameras.collectAsState()
    val progress by graph.capture.progress.collectAsState()
    val prefs by graph.previewPrefs.collectAsState()
    var mainRole by rememberSaveable { mutableStateOf(CameraRole.INTERIOR) }
    val main = sources.firstOrNull { it.role == mainRole } ?: sources.firstOrNull()
    val others = sources.filter { it !== main }

    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
        val landscape = maxWidth > maxHeight
        val viewfinder = @Composable { m: Modifier ->
            Viewfinder(m, main, others, onSelect = { mainRole = it.role }, live = prefs.layout?.let { l -> { mm: Modifier -> LiveComposite(graph, l, prefs.filter, mm) } }, filter = prefs.filter) {
                CaptureOverlay(
                    progress = progress,
                    onShutter = { graph.capture.start(TriggerSource.SHUTTER_BUTTON) },
                    onStop = { graph.capture.stop() },
                )
            }
        }
        val status = @Composable { m: Modifier ->
            Card(m, title = "카메라") {
                Text(if (driving.restricted) "주행 중" else "정차 중", color = Tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${driving.source} · ${driving.detail}", color = Tokens.text2, fontSize = 14.sp)
                Box(Modifier.padding(top = 16.dp))
                listOf(CameraRole.INTERIOR, CameraRole.FRONT, CameraRole.REAR).forEach { role ->
                    val s = sources.firstOrNull { it.role == role }
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(CameraSourceFactory.roleLabel(role), color = Tokens.text2, fontSize = 15.sp, modifier = Modifier.width(48.dp))
                        Text(s?.label ?: "없음", color = if (s == null) Tokens.rec else Tokens.text, fontSize = 15.sp)
                    }
                }
                Box(Modifier.padding(top = 16.dp))
                Text("미리보기", color = Tokens.text2, fontSize = 13.sp)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf<ai.chaz.media.Layout?>(null, ai.chaz.media.Layout.WINDOW_SWAP, ai.chaz.media.Layout.CUT_FRAME).forEach { l ->
                        val ok = l == null || graph.composer.available(l)
                        ai.chaz.ui.Chip(l?.let { "${it.id} ${it.label}" } ?: "원본", selected = prefs.layout == l) {
                            if (ok) graph.previewPrefs.value = prefs.copy(layout = l)
                        }
                    }
                }
                Text("필터", color = Tokens.text2, fontSize = 13.sp)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ai.chaz.media.ColorPreset.entries.forEach { f ->
                        ai.chaz.ui.Chip(f.label, selected = prefs.filter == f) { graph.previewPrefs.value = prefs.copy(filter = f) }
                    }
                }
                Box(Modifier.padding(top = 8.dp))
                ai.chaz.ui.Chip("🎙 음성 명령", selected = false) { graph.voice.voiceButton() }
                val heard by graph.voice.lastHeard.collectAsState()
                heard?.let { Text("들은 말: \"$it\"", color = Tokens.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Box(Modifier.padding(top = 12.dp))
                listOf(Feature.WAKE_WORD, Feature.SPEECH_OUTPUT, Feature.SPEECH_INPUT).forEach { f ->
                    val sel = selections[f]
                    Text(
                        "${f.label}: " + (sel?.selected?.label ?: if (sel == null) "판정 중" else "없음"),
                        color = Tokens.text2, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                viewfinder(Modifier.weight(1f).fillMaxHeight())
                status(Modifier.width(320.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                viewfinder(Modifier.fillMaxWidth().weight(1f))
                status(Modifier.fillMaxWidth())
            }
        }
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
                Modifier.align(Alignment.TopEnd).padding(16.dp)
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

/** C2 촬영 중 표시: REC 타이머·컷 카운터·카운트다운 + 셔터/중지 버튼 (정차 중 화면). */
@Composable
private fun BoxScope.CaptureOverlay(
    progress: CaptureSessionEngine.Progress?,
    onShutter: () -> Unit,
    onStop: () -> Unit,
) {
    val capturing = progress != null
    if (progress != null) {
        Row(
            Modifier.align(Alignment.TopCenter).padding(16.dp)
                .background(Tokens.bg.copy(alpha = 0.75f), Tokens.controlShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(14.dp).background(Tokens.rec, CircleShape))
            val sec = progress.elapsedMs / 1000
            Text("%02d:%02d".format(sec / 60, sec % 60), color = Tokens.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = Tokens.tabular)
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
    // 왼쪽 아래: 셔터(차~즈) / 중지
    Row(
        Modifier.align(Alignment.BottomStart).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .background(if (capturing) Tokens.rec else Tokens.accent, CircleShape)
                .border(4.dp, Tokens.text.copy(alpha = 0.85f), CircleShape)
                .clickable(onClick = if (capturing) onStop else onShutter),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (capturing) "중지" else "차~즈", color = Tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if (!capturing) {
            Text(
                "12초 · 4컷", color = Tokens.text, fontSize = 18.sp,
                modifier = Modifier.background(Tokens.bg.copy(alpha = 0.7f), Tokens.controlShape).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SourcePreview(source: CameraSource, modifier: Modifier, filter: ai.chaz.media.ColorPreset = ai.chaz.media.ColorPreset.ORIGINAL) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val needsCamera = source.kind != ai.chaz.capture.SourceKind.RECORDED_VIDEO
    if (needsCamera && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
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
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
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
