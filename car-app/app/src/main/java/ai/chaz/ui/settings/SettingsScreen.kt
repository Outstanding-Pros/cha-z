package ai.chaz.ui.settings

import ai.chaz.AppGraph
import ai.chaz.platform.Attempt
import ai.chaz.platform.AttemptStatus
import ai.chaz.platform.Feature
import ai.chaz.platform.Selection
import ai.chaz.platform.Tier
import ai.chaz.platform.driving.DrivingGateHub
import ai.chaz.ui.Card
import ai.chaz.ui.Chip
import ai.chaz.ui.Tag
import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** C6 설정 — 주행 규제 판정과 'SDK 연동 상태' 패널. */
@Composable
fun SettingsScreen(graph: AppGraph) {
    val selections by graph.availability.state.collectAsState()
    val probing by graph.probing.collectAsState()
    val driving by graph.driving.status.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(title = "주행 규제 (DrivingGate)") {
                Text(
                    (if (driving.restricted) "주행 중" else "정차 중") + "  ·  ${driving.source}  ·  ${driving.detail}",
                    color = Tokens.text, fontSize = 18.sp,
                )
                Spacer(Modifier.padding(top = 12.dp))
                Text("개발용 오버라이드", color = Tokens.text2, fontSize = 13.sp)
                Spacer(Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrivingGateHub.Override.entries.forEach { o ->
                        Chip(o.label, selected = driving.override == o) { graph.driving.setOverride(o) }
                    }
                    if (graph.driving.current === graph.debugGate) {
                        Spacer(Modifier.width(16.dp))
                        Chip("디버그 토글: " + if (driving.restricted) "주행" else "정차", selected = false) {
                            graph.debugGate.set(!graph.debugGate.restricted.value)
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SDK 연동 상태", color = Tokens.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Chip(if (probing) "판정 중…" else "다시 판정", selected = false) { graph.reprobe() }
            }
            Text(
                "기능마다 Pleos SDK → 플랫폼 API → 비즈라우터 → 대체 순서로 시도해 실제로 동작하는 구현을 고릅니다.",
                color = Tokens.text2, fontSize = 14.sp,
            )
        }
        items(Feature.entries.toList(), key = { it.name }) { f ->
            FeatureRow(f, selections[f])
        }
    }
}

@Composable
private fun FeatureRow(feature: Feature, sel: Selection?) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(feature.label, color = Tokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(feature.adapter, color = Tokens.text2, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            when {
                sel == null -> Tag("판정 중", Tokens.text2)
                sel.selected == null -> Tag("사용 불가", Tokens.rec)
                else -> Tag("${sel.selected.tier.label} · ${sel.selected.label}", tierColor(sel.selected.tier))
            }
        }
        if (sel?.note != null) {
            Text(sel.note, color = Tokens.warn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (sel != null) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Tokens.line)
            sel.attempts.forEach { AttemptLine(it) }
        }
    }
}

@Composable
private fun AttemptLine(a: Attempt) {
    val (mark, color) = when (a.status) {
        AttemptStatus.OK -> "✓" to Tokens.ok
        AttemptStatus.FAILED -> "✗" to Tokens.rec
        AttemptStatus.PENDING -> "…" to Tokens.warn
        AttemptStatus.SKIPPED -> "·" to Tokens.text2
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(mark, color = color, fontSize = 15.sp, modifier = Modifier.width(22.dp))
        Column {
            Text(a.label, color = if (a.status == AttemptStatus.SKIPPED) Tokens.text2 else Tokens.text, fontSize = 15.sp)
            Text(a.reason, color = Tokens.text2, fontSize = 13.sp)
        }
    }
}

private fun tierColor(t: Tier): Color = when (t) {
    Tier.PLEOS_SDK -> Tokens.accent
    Tier.PLATFORM -> Tokens.ok
    Tier.BIZROUTER -> Color(0xFFB57BFF)
    Tier.FALLBACK -> Tokens.warn
}
