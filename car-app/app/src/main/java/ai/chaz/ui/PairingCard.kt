package ai.chaz.ui

import ai.chaz.sync.PhoneLink
import ai.chaz.ui.theme.Tokens
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 폰 연결 상태 + 페어링 QR (C4·C6). 정차 중 화면에만 놓인다 — 주행 중에는 DrivingLockOverlay 가 덮어
 * QR 이 보이지 않는다 (SPEC: 지역별 주행 규제상 QR 금지).
 */
@Composable
fun PairingCard(link: PhoneLink, modifier: Modifier = Modifier) {
    val s by link.state.collectAsState()
    Card(modifier, title = "폰 연결") {
        Text(
            when {
                !s.reachable -> "서버에 연결할 수 없어요 (server/ 실행 확인)"
                s.paired -> "연결됨 · 폰 ${s.phones}대"
                else -> "연결된 폰이 없어요"
            },
            color = if (s.paired) Tokens.ok else Tokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        )
        val t = s.ticket
        if (t != null) {
            val qr = remember(t.url) { qrBitmap(t.url, 512) }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Image(qr.asImageBitmap(), "페어링 QR", Modifier.size(200.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("동승자 폰 카메라로 QR을 찍어 주세요", color = Tokens.text, fontSize = 16.sp)
                    Text("폰과 이 차가 같은 Wi-Fi 에 있어야 해요 · 10분 동안 유효", color = Tokens.text2, fontSize = 13.sp)
                    Text(t.url, color = Tokens.text2, fontSize = 12.sp)
                }
            }
            Chip("QR 닫기", selected = false, modifier = Modifier.padding(top = 12.dp)) { link.stopPairing() }
        } else {
            Chip(if (s.paired) "다른 폰 연결" else "QR 띄우기", selected = !s.paired, modifier = Modifier.padding(top = 12.dp)) { link.startPairing() }
        }
    }
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
