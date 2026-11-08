package ai.chaz.ui

import ai.chaz.AppGraph
import ai.chaz.platform.driving.DrivingGateHub
import ai.chaz.ui.settings.SettingsScreen
import ai.chaz.ui.spike.SpikeScreen
import ai.chaz.ui.standby.StandbyScreen
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

enum class Tab(val label: String, val ready: Boolean) {
    HOME("촬영", true), GALLERY("갤러리", false), LIGHT("조명", false), SETTINGS("설정", true), SPIKE("점검", true)
}

@Composable
fun ChazRoot(graph: AppGraph, initialTab: Tab = Tab.HOME) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val driving by graph.driving.status.collectAsState()

    Box(Modifier.fillMaxSize().background(Tokens.bg)) {
        DrivingLockOverlay(
            hub = graph.driving,
            line1 = "촬영 대기",
            line2 = "'차~즈'라고 말하면 찍어요",
            onVoice = { /* T07: SpeechInput 시작 */ },
        ) {
            Row(Modifier.fillMaxSize()) {
                LeftRail(tab) { tab = it }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        Tab.HOME -> StandbyScreen(graph)
                        Tab.SETTINGS -> SettingsScreen(graph)
                        Tab.SPIKE -> SpikeScreen(graph)
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("${tab.label} — 준비 중", color = Tokens.text2, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
        // 개발용 오버라이드로 주행을 고정했을 때 설정에 못 들어가는 일을 막는다 (시연 화면에는 안 보임)
        if (driving.override == DrivingGateHub.Override.FORCE_DRIVING) {
            Box(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                Chip("디버그: 주행 고정 해제", selected = false) { graph.driving.setOverride(DrivingGateHub.Override.AUTO) }
            }
        }
    }
}

@Composable
private fun LeftRail(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier.width(112.dp).fillMaxHeight().background(Tokens.surface).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("차~즈", color = Tokens.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(top = 16.dp))
        Tab.entries.forEach { t ->
            val isSel = t == selected
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(if (isSel) Tokens.surface2 else Tokens.surface, Tokens.controlShape)
                    .clickable(enabled = t.ready) { onSelect(t) }
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    color = when { isSel -> Tokens.accent; t.ready -> Tokens.text; else -> Tokens.line },
                    fontSize = 16.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
