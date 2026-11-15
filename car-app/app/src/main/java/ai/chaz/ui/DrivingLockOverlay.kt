package ai.chaz.ui

import ai.chaz.platform.driving.DrivingGateHub
import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 공통 주행 잠금. DrivingGate 를 구독해 주행 중이면 정차용 화면을 덮는다.
 * 애니메이션 없는 정적 화면: 큰 '차~즈' 음성 버튼 + 상태 텍스트 두 줄.
 */
@Composable
fun DrivingLockOverlay(
    hub: DrivingGateHub,
    line1: String,
    line2: String,
    onVoice: () -> Unit,
    /** 촬영 중 '중지' 같은 한 번 누르는 버튼 (다단계 터치 금지). */
    action: Pair<String, () -> Unit>? = null,
    /** 촬영 진행 (찍은 컷, 전체 컷) — 진행 바 칸 + ✓ 표시. 사진 썸네일은 넣지 않는다. */
    slots: Pair<Int, Int>? = null,
    /** 정보 줄: ♪ 곡명 · 조명 · 장소. lightColor = 조명 색 점. */
    info: String? = null,
    lightColor: Int? = null,
    /** 음성 힌트 (예: 🎙 "차즈, 휴대폰 편집"). */
    hint: String? = null,
    parkedContent: @Composable () -> Unit,
) {
    val restricted by hub.restricted.collectAsState()
    if (!restricted) {
        parkedContent()
        return
    }
    Box(Modifier.fillMaxSize().background(Tokens.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .size(220.dp)
                    .background(Tokens.accent, CircleShape)
                    .clickable(onClick = onVoice),
                contentAlignment = Alignment.Center,
            ) {
                Text("차~즈", color = Tokens.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(40.dp))
            slots?.let { (taken, total) ->
                // 진행 바: 부드러운 애니메이션 없이 컷마다 한 칸 (스타일 기획서)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(total.coerceIn(1, 8)) { i ->
                        Box(Modifier.size(width = 96.dp, height = 18.dp).background(if (i < taken) Tokens.accent else Tokens.surface2, RoundedCornerShape(9.dp)))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    (1..total).joinToString("  ") { if (it <= taken) "✓ $it" else "○ $it" } + "  저장",
                    color = Tokens.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(20.dp))
            }
            Text(line1, color = Tokens.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(line2, color = Tokens.text2, fontSize = 22.sp)
            info?.let {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    lightColor?.let { c -> Box(Modifier.size(14.dp).background(androidx.compose.ui.graphics.Color(c), CircleShape)); Spacer(Modifier.width(8.dp)) }
                    Text(it, color = Tokens.text2, fontSize = 20.sp)
                }
            }
            hint?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = Tokens.accent, fontSize = 22.sp)
            }
            if (action != null) {
                Spacer(Modifier.height(32.dp))
                Box(
                    Modifier
                        .background(Tokens.rec, Tokens.controlShape)
                        .clickable(onClick = action.second)
                        .padding(horizontal = 40.dp, vertical = 20.dp),
                ) { Text(action.first, color = Tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
