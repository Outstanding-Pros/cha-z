package ai.chaz.ui.standby

import ai.chaz.AppGraph
import ai.chaz.capture.CameraRole
import ai.chaz.capture.CameraSource
import ai.chaz.capture.CameraSourceFactory
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
import androidx.lifecycle.compose.LocalLifecycleOwner

/** C1 촬영 대기 (정차 중). 큰 뷰파인더 + 카메라 소스 칩 + 나머지 소스 타일. */
@Composable
fun StandbyScreen(graph: AppGraph) {
    val selections by graph.availability.state.collectAsState()
    val driving by graph.driving.status.collectAsState()
    val sources by graph.cameras.collectAsState()
    var mainRole by rememberSaveable { mutableStateOf(CameraRole.INTERIOR) }
    val main = sources.firstOrNull { it.role == mainRole } ?: sources.firstOrNull()
    val others = sources.filter { it !== main }

    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
        val landscape = maxWidth > maxHeight
        val viewfinder = @Composable { m: Modifier ->
            Viewfinder(m, main, others, onSelect = { mainRole = it.role })
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
                listOf(Feature.SPEECH_OUTPUT, Feature.SPEECH_INPUT).forEach { f ->
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
private fun Viewfinder(modifier: Modifier, main: CameraSource?, others: List<CameraSource>, onSelect: (CameraSource) -> Unit) {
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
        SourcePreview(main, Modifier.fillMaxSize())
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
    }
}

@Composable
private fun SourcePreview(source: CameraSource, modifier: Modifier) {
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
            modifier = modifier,
            onRelease = { view -> source.releasePreview(view) },
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
