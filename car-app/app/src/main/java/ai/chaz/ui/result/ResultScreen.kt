package ai.chaz.ui.result

import ai.chaz.AppGraph
import ai.chaz.capture.CameraSourceFactory
import ai.chaz.data.Frame
import ai.chaz.data.TriggerSource
import ai.chaz.ui.Chip
import ai.chaz.ui.theme.Tokens
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 촬영 직후(정차 중 Editing) 화면. 지금은 컷별 실내·전방·후방 원본을 보여 주는 단계까지.
 * TODO(T08): C3 셀렉·편집 (레이아웃 L1·L2 합성, 필터, 스탬프)
 */
@Composable
fun ResultScreen(graph: AppGraph) {
    val result by graph.capture.lastResult.collectAsState()
    val r = result
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("촬영 결과", color = Tokens.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (r != null) {
                Text(
                    "컷 ${r.cuts.size}/${r.session.config.count} · 클립 " +
                        r.clips.joinToString { "${CameraSourceFactory.roleLabel(it.role)} ${it.frameCount}장" } +
                        if (r.stopped) " · 중지됨" else "",
                    color = Tokens.text2, fontSize = 16.sp,
                )
            }
            Box(Modifier.weight(1f))
            Chip("다시 찍기", selected = false) { graph.capture.start(TriggerSource.SHUTTER_BUTTON, graph.capture.lastConfig) }
            Chip("닫기", selected = false) { graph.capture.stop() }
        }
        Text("편집(레이아웃 합성)은 T08에서 연결됩니다.", color = Tokens.text2, fontSize = 14.sp)
        if (r == null) return@Column
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(r.cuts, key = { it.index }) { cut ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("#${cut.index}\n%.1f초".format(cut.tMs / 1000f), color = Tokens.text, fontSize = 16.sp, modifier = Modifier.width(64.dp))
                    cut.frames.forEach { FrameThumb(it) }
                }
            }
        }
    }
}

@Composable
private fun FrameThumb(frame: Frame) {
    val img by produceState<ImageBitmap?>(null, frame.path) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(frame.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
        }
    }
    Box(Modifier.width(260.dp).aspectRatio(16f / 9f).clip(Tokens.controlShape).background(Tokens.surface2)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            CameraSourceFactory.roleLabel(frame.role), color = Tokens.text, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Tokens.bg.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
