package ai.chaz.ui

import ai.chaz.AppGraph
import ai.chaz.capture.CameraRole
import ai.chaz.ui.theme.Tokens
import ai.chaz.voice.SendOutcome
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 정차 후 '방금 찍은 촬영' 카드 (스타일 기획서): 방금 찍은 컷 + [여기서 편집] [휴대폰에서 편집하기].
 * 휴대폰에서 편집하기: 폰이 연결돼 있으면 세션 원본을 폰으로, 아니면 QR(PairingCard). 정차 중에만 그려진다(ChazRoot).
 */
@Composable
fun JustShotCard(graph: AppGraph, js: AppGraph.JustShot, onEditHere: () -> Unit, onClose: () -> Unit) {
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    val phone by graph.phone.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showQr by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val shots = bundles.filter { it.id in js.bundleIds }.sortedBy { it.cut }
    Box(Modifier.fillMaxSize().background(Color(0xB0000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(1100.dp).background(Tokens.surface, Tokens.cardShape).border(1.dp, Tokens.line, Tokens.cardShape).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("방금 찍은 ${js.cuts}컷", color = Tokens.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Chip("닫기", selected = false, onClick = onClose)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                shots.take(4).forEach { b ->
                    Box(Modifier.weight(1f).aspectRatio(16f / 9f).background(Tokens.bg, Tokens.controlShape)) { ai.chaz.ui.gallery.PhotoImage(b.photos[CameraRole.INTERIOR] ?: b.photos.values.firstOrNull(), Modifier.fillMaxSize()) }
                }
                repeat((4 - shots.size).coerceAtLeast(0).coerceAtMost(3)) { Box(Modifier.weight(1f)) }
            }
            if (showQr && !phone.paired) {
                PairingCard(graph.phone, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton("여기서 편집", Modifier.weight(1f), onClick = onEditHere)
                    PrimaryButton(if (sending) "보내는 중…" else "휴대폰에서 편집하기", Modifier.weight(1f), color = Tokens.ai, enabled = !sending) {
                        if (!phone.paired) { showQr = true; graph.phone.startPairing() }
                        else {
                            sending = true
                            scope.launch {
                                status = when (graph.handoffLatest()) {
                                    SendOutcome.SENT -> "휴대폰으로 보냈어요. 폰에서 저장·공유할 수 있어요"
                                    SendOutcome.NO_RESULT -> "보낼 사진이 없어요"
                                    SendOutcome.FAILED -> "보내지 못했어요. 서버 연결을 확인해 주세요"
                                }
                                sending = false
                            }
                        }
                    }
                }
            }
            status?.let { Text(it, color = Tokens.accent, fontSize = 18.sp) }
            Text("여기서 편집 = 스타일·프레임을 골라 릴스나 네컷으로. 이 카드는 정차 중에만 보이고, 주행하면 바로 사라져요.", color = Tokens.text3, fontSize = 15.sp)
        }
    }
}
