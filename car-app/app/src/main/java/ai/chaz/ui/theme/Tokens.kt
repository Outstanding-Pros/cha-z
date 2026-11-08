package ai.chaz.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** 사양서 '디자인 토큰 (초안)' — 촬영 장비 콘솔. */
object Tokens {
    val bg = Color(0xFF0B0C0E)
    val surface = Color(0xFF16181B)
    val surface2 = Color(0xFF1F2226)
    val line = Color(0xFF2A2E33)
    val text = Color(0xFFFFFFFF)
    val text2 = Color(0xFF9AA0A6)
    val accent = Color(0xFF3D8BFF)
    val rec = Color(0xFFFF3B30)
    val ok = Color(0xFF34C759)
    val warn = Color(0xFFFFB020)

    val cardShape = RoundedCornerShape(24.dp)
    val controlShape = RoundedCornerShape(16.dp)
    val space = 8.dp

    /** 타이머·거리·컷 카운터용 고정폭 숫자. */
    val tabular = TextStyle(fontFeatureSettings = "tnum")
}

@Composable
fun ChazTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Tokens.bg,
            surface = Tokens.surface,
            surfaceVariant = Tokens.surface2,
            primary = Tokens.accent,
            onBackground = Tokens.text,
            onSurface = Tokens.text,
            onSurfaceVariant = Tokens.text2,
            outline = Tokens.line,
            error = Tokens.rec,
        ),
        content = content,
    )
}
