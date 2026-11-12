package ai.chaz.ui

import ai.chaz.AppGraph
import ai.chaz.platform.driving.DrivingGateHub
import ai.chaz.ui.settings.SettingsScreen
import ai.chaz.ui.spike.SpikeScreen
import ai.chaz.ui.standby.StandbyScreen
import ai.chaz.ui.edit.EditScreen
import ai.chaz.state.AppState
import ai.chaz.data.TriggerSource
import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(val label: String, val ready: Boolean, val icon: () -> androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("촬영", true, { ChazIcons.camera }), ALBUM("앨범", true, { ChazIcons.gallery }), GALLERY("편집", true, { ChazIcons.sparkle }), LIGHT("조명", true, { ChazIcons.light }),
    SETTINGS("설정", true, { ChazIcons.settings }), SPIKE("점검", true, { ChazIcons.tools }),
}

@Composable
fun ChazRoot(graph: AppGraph, initialTab: Tab = Tab.HOME) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val driving by graph.driving.status.collectAsState()
    val model by graph.capture.model.collectAsState()
    val state = model.state
    val nearest by graph.spotGuide.nearest.collectAsState()
    val nextSpot = nearest?.let { "다음 스팟 ${it.spot.name} ${ai.chaz.ui.light.dist(it.distanceM)}" }
    // 결과가 새로 뜨면(차 촬영 끝·폰 직접 셔터) 다른 탭에 있어도 홈(편집)으로
    LaunchedEffect((state as? AppState.Editing)?.sessionId) { if (state is AppState.Editing) tab = Tab.HOME }
    // 차~즈 촬영이 끝나 앨범에 저장되면, 정차 중이면 편집 탭(방금 찍은 묶음이 칸에 들어간 채)으로
    val draft by graph.albumDraft.collectAsState()
    LaunchedEffect(draft, driving.restricted) { if (draft != null && !driving.restricted) tab = Tab.GALLERY }
    val (line1, line2) = when (state) {
        is AppState.Capturing -> "촬영 중 · ${state.session.config.count}컷 중 ${state.shotsTaken}" to "창밖 풍경과 함께 담고 있어요" // 주행 화면: 포즈·시선 유도 문구 없음
        is AppState.Saved -> "저장했어요" to "정차 후 편집하거나 '차즈 스마트폰에서 편집할게'라고 말해 주세요"
        else -> "촬영 대기" to (nextSpot ?: "'차~즈'라고 말하면 찍어요")
    }

    Box(Modifier.fillMaxSize().background(Tokens.bg)) {
        DrivingLockOverlay(
            hub = graph.driving,
            line1 = line1,
            line2 = line2,
            // T2: 음성 버튼 1회 탭 후 명령
            onVoice = { graph.voice.voiceButton() },
            action = if (state is AppState.Capturing) "중지" to { graph.capture.stop() } else null,
        ) {
            Row(Modifier.fillMaxSize()) {
                LeftRail(tab, graph) { tab = it }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        Tab.HOME -> if (state is AppState.Editing) EditScreen(graph) else StandbyScreen(graph)
                        Tab.ALBUM -> ai.chaz.ui.gallery.AlbumScreen(graph)
                        Tab.GALLERY -> ai.chaz.ui.gallery.GalleryScreen(graph)
                        Tab.SETTINGS -> SettingsScreen(graph)
                        Tab.SPIKE -> SpikeScreen(graph)
                        Tab.LIGHT -> ai.chaz.ui.light.LightScreen(graph)
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("${tab.label} — 준비 중", color = Tokens.text2, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
        // 앰비언트 조명 테두리 (시뮬레이션 또는 실제 조명 미러)
        ai.chaz.ui.light.AmbientBorder(graph)
        // 개발용 오버라이드로 주행을 고정했을 때 설정에 못 들어가는 일을 막는다 (시연 화면에는 안 보임)
        if (driving.override == DrivingGateHub.Override.FORCE_DRIVING) {
            Box(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                Chip("디버그: 주행 고정 해제", selected = false) { graph.driving.setOverride(DrivingGateHub.Override.AUTO) }
            }
        }
    }
}

@Composable
private fun LeftRail(selected: Tab, graph: AppGraph, onSelect: (Tab) -> Unit) {
    val phone by graph.phone.state.collectAsState()
    Column(
        Modifier.width(120.dp).fillMaxHeight().background(Tokens.surface).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("차~즈", color = Tokens.accent, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Box(Modifier.padding(top = 20.dp))
        Tab.entries.forEach { t ->
            val isSel = t == selected
            val fg = when { isSel -> Tokens.accent; t.ready -> Tokens.text; else -> Tokens.text3 }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(if (isSel) Tokens.accentSoft else Tokens.surface, Tokens.controlShape)
                    .clickable(enabled = t.ready) { onSelect(t) }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                androidx.compose.material3.Icon(t.icon(), null, tint = fg, modifier = Modifier.size(30.dp))
                Text(t.label, color = fg, fontSize = 15.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium)
            }
        }
        Box(Modifier.weight(1f))
        // 마이크 사용 중 표시 (SPEC: 마이크를 쓰는 동안 항상 표시)
        val listening by graph.voice.listening.collectAsState()
        if (listening) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                Box(Modifier.size(10.dp).background(Tokens.rec, androidx.compose.foundation.shape.CircleShape))
                androidx.compose.material3.Icon(ChazIcons.mic, "마이크 사용 중", tint = Tokens.text, modifier = Modifier.size(24.dp))
            }
        }
        // SPEC: 레일 아래에 페어링된 폰 상태
        val c = if (phone.paired) Tokens.ok else Tokens.text3
        androidx.compose.material3.Icon(ChazIcons.phone, null, tint = c, modifier = Modifier.size(28.dp))
        Text(if (phone.paired) "폰 연결" else "폰 없음", color = c, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
