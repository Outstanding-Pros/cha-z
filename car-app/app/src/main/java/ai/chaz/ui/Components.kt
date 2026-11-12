package ai.chaz.ui

import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 카드: surface + 1dp 테두리 + 모서리 24. 제목은 본문보다 한 단계 큰 굵은 글씨, 오른쪽에 상태 슬롯. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(Tokens.surface, Tokens.cardShape)
            .border(1.dp, Tokens.line, Tokens.cardShape)
            .padding(24.dp),
    ) {
        if (title != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title != null) Text(title, color = Tokens.text, fontSize = Tokens.titleSize, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            Box(Modifier.padding(top = 16.dp))
        }
        content()
    }
}

/** 카드 안 소제목 (레이아웃·필터 같은 묶음 이름). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Tokens.text2, fontSize = Tokens.labelSize, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(bottom = 8.dp))
}

/**
 * 칩: 최소 높이 56dp (Pleos UI 가이드의 차량용 터치 크기). 선택 = accent 채움, 비활성 = 흐리게(터치 무시).
 */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Tokens.accent,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .defaultMinSize(minHeight = Tokens.touch)
            .background(if (selected) color else Tokens.surface2, Tokens.controlShape)
            .border(1.dp, if (selected) color else Tokens.line, Tokens.controlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val fg = when { !enabled -> Tokens.text3; else -> Tokens.text }
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Text(text, color = fg, fontSize = Tokens.bodySize, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** 화면의 주 동작 버튼 (저장 등): 크게, accent 채움. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = Tokens.accent, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier
            .defaultMinSize(minHeight = 68.dp)
            .background(if (enabled) color else Tokens.surface2, Tokens.controlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = Tokens.text, modifier = Modifier.size(24.dp)); Spacer(Modifier.size(10.dp)) }
        Text(text, color = if (enabled) Tokens.text else Tokens.text3, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** 상태 알약: 점 + 글자 (정차 중·연결됨 등). */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, color = color, fontSize = Tokens.labelSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
