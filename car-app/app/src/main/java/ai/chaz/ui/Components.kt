package ai.chaz.ui

import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Card(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(Tokens.surface, Tokens.cardShape)
            .border(1.dp, Tokens.line, Tokens.cardShape)
            .padding(20.dp),
    ) {
        if (title != null) {
            Text(title, color = Tokens.text2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.padding(top = 12.dp))
        }
        content()
    }
}

/** 터치 영역 최소 56dp (Pleos UI 가이드의 차량용 최소 터치 크기 기준으로 넉넉히). */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, color: Color = Tokens.accent, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (selected) color else Tokens.surface2, Tokens.controlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(text, color = Tokens.text, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
