package ai.chaz.ui.light

import ai.chaz.AppGraph
import ai.chaz.ambience.LightPreset
import ai.chaz.ui.Card
import ai.chaz.ui.Chip
import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 조명·맥락 화면 (정차 중). 맥락 카드(날씨·시간대·일몰·스팟), 조명 프리셋, 데모 드라이브. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightScreen(graph: AppGraph) {
    val ctx by graph.context.collectAsState()
    val preset by graph.ambience.preset.collectAsState()
    val nearest by graph.spotGuide.nearest.collectAsState()
    val spots by graph.spotGuide.spots.collectAsState()
    val here by graph.location.location.collectAsState()
    val source by graph.location.sourceLabel.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { graph.refreshContext() }

    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), title = "맥락") {
                val c = ctx
                Text(
                    if (c == null) "판정 중…" else "${c.weather.label} · ${c.timeBand.label} · 풍경 ${c.scene.label}",
                    color = Tokens.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
                c?.sunsetMs?.let {
                    val left = (it - System.currentTimeMillis()) / 60_000
                    Text(
                        "일몰 ${SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it))}" +
                            (if (left > 0) " · ${left / 60}시간 ${left % 60}분 남음" else "") +
                            " · 출처 ${if (c.source == "clock") "시각 추정" else "Open-Meteo"}",
                        color = Tokens.text2, fontSize = 15.sp,
                    )
                }
                Text(
                    "위치: $source" + (if (here == null) " · 위치 없음" else "") +
                        (nearest?.let { " · 가장 가까운 스팟 ${it.spot.name} ${dist(it.distanceM)}" } ?: ""),
                    color = Tokens.text2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), title = "앰비언트 조명" + if (graph.ambientLight.simulated) " (시뮬레이션)" else " · ${graph.ambientLight.name}") {
                Text(
                    preset?.let { "지금: ${it.label}" } ?: "지금: 원래 조명",
                    color = Tokens.text, fontSize = 18.sp,
                )
                FlowRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("맥락에 맞게", selected = false) { scope.launch { graph.changeLight(null) } }
                    LightPreset.entries.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Chip(p.label, selected = preset == p) { scope.launch { graph.changeLight(p.label) } }
                        }
                    }
                    Chip("원래대로", selected = false) { scope.launch { graph.ambience.end() } }
                }
                val autoDrive by graph.autoLightDriving.collectAsState()
                Text("주행 중 자동 변경", color = Tokens.text2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("끔 · 정차해서 찍을 때만", selected = !autoDrive) { graph.setAutoLightDriving(false) }
                    Chip("켬 · 스팟 예고 때도", selected = autoDrive) { graph.setAutoLightDriving(true) }
                }
                Text(
                    if (autoDrive) "주행 중에도 스팟 예고·촬영 시작 때 한 번, 3초 이상 천천히, 지금보다 밝지 않게 바뀌고 끝나면 돌아와요."
                    else "주행 중에는 조명을 바꾸지 않아요. 정차해서 찍을 때 맥락에 맞게 바뀌고 끝나면 돌아와요. 손으로 바꾸기도 정차 중에만 돼요.",
                    color = Tokens.text2, fontSize = 14.sp,
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), title = "스팟 가이드") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (graph.location.demoRunning) Chip("데모 드라이브 정지", selected = true) { graph.location.stopDemo() }
                    else Chip("데모 드라이브 시작 (애월 해안도로)", selected = false) { graph.spotGuide.resetTrip(); graph.location.startDemo() }
                }
                Text("명소 400m 안으로 다가가면 음성으로 제안하고, 15초 안에 '차즈'라고 하면 그 스팟 이름으로 찍혀요.", color = Tokens.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                spots.sortedBy { s -> here?.let { ai.chaz.platform.navi.Geo.distanceM(it, s.point) } ?: 0.0 }.take(6).forEach { s ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, color = Tokens.text, fontSize = 15.sp, modifier = Modifier.width(200.dp))
                        Text("${s.kind} · ${s.score}점 · ${s.source}", color = Tokens.text2, fontSize = 13.sp, modifier = Modifier.width(220.dp))
                        here?.let { Text(dist(ai.chaz.platform.navi.Geo.distanceM(it, s.point)), color = Tokens.text2, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

fun dist(m: Double): String = if (m >= 1_000) "%.1fkm".format(m / 1_000) else "${m.toInt()}m"

/** 화면 테두리 조명 표시 — 실제 조명을 못 쓰면 '시뮬레이션', 쓰면 미러 표시. 정적(애니메이션 없음). */
@Composable
fun AmbientBorder(graph: AppGraph, modifier: Modifier = Modifier) {
    val preset by graph.ambience.preset.collectAsState()
    val p = preset ?: return
    val c = Color(p.color)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(0.dp).background(Color.Transparent)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = 10.dp.toPx()
                drawRect(c.copy(alpha = 0.85f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 2))
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(18.dp).background(Tokens.bg.copy(alpha = 0.8f), Tokens.controlShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(12.dp).background(c, CircleShape))
            Text("조명: ${p.label}" + if (graph.ambientLight.simulated) " · 시뮬레이션" else "", color = Tokens.text, fontSize = 14.sp)
        }
    }
}
