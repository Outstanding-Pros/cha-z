package ai.chaz.ui.edit

import ai.chaz.AppGraph
import ai.chaz.capture.CameraRole
import ai.chaz.data.Cut
import ai.chaz.data.TriggerSource
import ai.chaz.media.FrameColor
import ai.chaz.media.Layout
import ai.chaz.ui.Card
import ai.chaz.ui.Chip
import ai.chaz.ui.theme.Tokens
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * C3 셀렉·편집 (정차 중만). 왼쪽 결과 미리보기, 오른쪽 레이아웃·프레임·스탬프, 아래 컷 필름스트립.
 * TODO(T17): AI 필터 / TODO(F4): BGM — 영상 레이아웃(T14)과 함께.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditScreen(graph: AppGraph) {
    val result by graph.capture.lastResult.collectAsState()
    val r = result ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("촬영 결과가 없어요", color = Tokens.text2) }
        return
    }
    val composer = graph.composer
    val cuts = r.cuts
    val prefs = graph.previewPrefs.value
    var layout by remember(r) {
        mutableStateOf(prefs.layout?.takeIf { composer.available(it, cuts) } ?: if (composer.available(Layout.WINDOW_SWAP, cuts)) Layout.WINDOW_SWAP else Layout.CUT_FRAME)
    }
    var filter by remember(r) { mutableStateOf(prefs.filter) }
    // 기본 셀렉: L1 = 가운데 컷 하나, L2 = 앞에서부터 최대 4컷
    var single by remember(r) { mutableStateOf(cuts.getOrNull(cuts.size / 2)?.index ?: 1) }
    var multi by remember(r) { mutableStateOf(cuts.take(4).map { it.index }.toSet()) }
    var color by remember { mutableStateOf(FrameColor.FILM) }
    var stamp by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun selectedCuts(): List<Cut> =
        if (layout == Layout.WINDOW_SWAP) cuts.filter { it.index == single }
        else cuts.filter { it.index in multi }.sortedBy { it.index }
    val scope = rememberCoroutineScope()
    val phone by graph.phone.state.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    var sendAfterPair by remember { mutableStateOf(false) }

    // 지금 화면의 설정으로 저장 → 폰으로 보내기 (C4)
    suspend fun saveAndSend() {
        busy = true
        try {
            val opts = composer.options(r, color, stamp, filter)
            val full = composer.render(layout, selectedCuts(), opts, preview = false)
            val saved = composer.save(r.session.id, layout, full, opts)
            val n = graph.phone.deliver(saved)
            message = "폰 ${n}대로 보냈어요 · 갤러리에도 저장했어요"
            graph.speech.speak(graph.phrases.sentToPhone())
        } catch (e: Exception) {
            message = "보내지 못했어요: ${e.message}"
        } finally {
            busy = false
        }
    }
    // QR 로 연결되면 기다리던 전송을 이어서
    LaunchedEffect(phone.paired, sendAfterPair) {
        if (phone.paired && sendAfterPair) { sendAfterPair = false; pairing = false; saveAndSend() }
    }

    LaunchedEffect(r, layout, single, multi, color, stamp, filter) {
        busy = true
        message = null
        try {
            val opts = composer.options(r, color, stamp, filter)
            preview = composer.render(layout, selectedCuts(), opts, preview = true)
        } catch (e: Exception) {
            preview = null
            message = e.message ?: "합성 실패"
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // 결과 미리보기
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(Tokens.cardShape).background(Tokens.surface).border(1.dp, Tokens.line, Tokens.cardShape),
                contentAlignment = Alignment.Center,
            ) {
                preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit) }
                if (busy) {
                    Text("합성 중…", color = Tokens.text, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                        .background(Tokens.bg.copy(alpha = 0.7f), Tokens.controlShape).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            // 오른쪽 패널
            Card(Modifier.width(380.dp).fillMaxHeight(), title = "셀렉·편집") {
                Text("레이아웃", color = Tokens.text2, fontSize = 13.sp)
                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Layout.WINDOW_SWAP, Layout.CUT_FRAME).forEach { l ->
                        val ok = composer.available(l, cuts)
                        Chip("${l.id} ${l.label}" + if (ok) "" else " (폰 촬영 불가)".takeIf { l == Layout.WINDOW_SWAP && composer.available(l) } ?: " (불가)", selected = layout == l) { if (ok) layout = l }
                    }
                }
                Text(
                    if (layout == Layout.WINDOW_SWAP) "실내 사진의 창밖을 같은 순간의 전방·후방 풍경으로 바꿔요. 아래에서 컷 1장을 고르세요."
                    else "풍경을 배경으로 인물을 오려 2~4칸에 담아요. 아래에서 컷을 2~4장 고르세요.",
                    color = Tokens.text2, fontSize = 13.sp,
                )
                Box(Modifier.height(16.dp))
                Text("프레임", color = Tokens.text2, fontSize = 13.sp)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameColor.entries.forEach { c -> Chip(c.label, selected = color == c) { color = c } }
                }
                Text("필터", color = Tokens.text2, fontSize = 13.sp)
                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ai.chaz.media.ColorPreset.entries.forEach { f -> Chip(f.label, selected = filter == f) { filter = f } }
                }
                Chip(if (stamp) "스탬프 켜짐" else "스탬프 꺼짐", selected = stamp) { stamp = !stamp }
                Box(Modifier.weight(1f))
                message?.let { Text(it, color = Tokens.warn, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("저장", selected = true) {
                        if (busy) return@Chip
                        scope.launch {
                            busy = true
                            try {
                                val opts = composer.options(r, color, stamp, filter)
                                val full = composer.render(layout, selectedCuts(), opts, preview = false)
                                composer.save(r.session.id, layout, full, opts)
                                message = "갤러리(Pictures/Cha-z)에 저장했어요"
                            } catch (e: Exception) {
                                message = "저장 실패: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                    Chip("폰으로 보내기", selected = false) {
                        if (busy) return@Chip
                        if (phone.paired) scope.launch { saveAndSend() }
                        else { pairing = true; sendAfterPair = true; graph.phone.startPairing() }
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("다시 찍기", selected = false) { graph.capture.start(TriggerSource.SHUTTER_BUTTON, graph.capture.lastConfig) }
                    Chip("닫기", selected = false) { graph.capture.stop() }
                }
            }
        }
        if (pairing) {
            ai.chaz.ui.PairingCard(graph.phone, Modifier.fillMaxWidth())
        }
        // 컷 필름스트립
        LazyRow(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cuts, key = { it.index }) { cut ->
                val selected = if (layout == Layout.WINDOW_SWAP) cut.index == single else cut.index in multi
                CutThumb(cut, selected) {
                    if (layout == Layout.WINDOW_SWAP) single = cut.index
                    else multi = when {
                        cut.index in multi && multi.size > 1 -> multi - cut.index
                        cut.index !in multi && multi.size < 4 -> multi + cut.index
                        else -> multi
                    }
                }
            }
        }
    }
}

@Composable
private fun CutThumb(cut: Cut, selected: Boolean, onClick: () -> Unit) {
    val path = cut.frames.firstOrNull { it.role == CameraRole.INTERIOR }?.path ?: cut.frames.firstOrNull()?.path
    val img by produceState<ImageBitmap?>(null, path) {
        value = path?.let { p -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() } }
    }
    Box(
        Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(Tokens.controlShape)
            .border(if (selected) 4.dp else 1.dp, if (selected) Tokens.accent else Tokens.line, Tokens.controlShape)
            .clickable(onClick = onClick),
    ) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            "#${cut.index} · %.1f초".format(cut.tMs / 1000f), color = Tokens.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).background(Tokens.bg.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
