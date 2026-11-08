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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
            Text(line1, color = Tokens.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(line2, color = Tokens.text2, fontSize = 22.sp)
        }
    }
}
