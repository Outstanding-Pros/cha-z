package ai.chaz.ui.gallery

import ai.chaz.AppGraph
import ai.chaz.capture.CameraRole
import ai.chaz.media.Layout
import ai.chaz.sync.PhoneAlbum
import ai.chaz.sync.PhoneBundle
import ai.chaz.ui.Card
import ai.chaz.ui.Chip
import ai.chaz.ui.PrimaryButton
import ai.chaz.ui.SectionLabel
import ai.chaz.ui.theme.Tokens
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 갤러리 — 폰 앨범. 묶음 하나 = 차량 카메라(실내·전방·후방)가 같은 순간 찍은 한 컷 (지금은 폰으로 대체).
 * 오른쪽에서 프레임을 정하고, 프레임의 컷 칸을 고른 뒤 왼쪽 묶음을 누르면 그 칸에 들어간다 → '꾸미기'.
 * 주행 중에는 DrivingLockOverlay 가 덮는다(다단계 터치 금지).
 */
@Composable
fun GalleryScreen(graph: AppGraph) {
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    val scope = rememberCoroutineScope()
    // 갤러리에서 고를 수 있는 프레임. L1 오픈카는 고정 위치 차량 실내 카메라 전용(창문 마스크)이라 폰 실내 사진으로는 못 만든다.
    val layouts = listOf(Layout.CUT_FRAME, Layout.ROUTE_OVERLAY).filter { graph.composer.available(it) }
    var layout by remember { mutableStateOf(layouts.firstOrNull() ?: Layout.CUT_FRAME) }
    val slots = remember { mutableStateListOf<PhoneBundle?>(null, null, null, null) }
    var active by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val n = PhoneAlbum.cutSlots(layout)
    // 지운 묶음이 칸에 남지 않게, 칸 수가 줄면 고른 칸을 앞으로
    androidx.compose.runtime.LaunchedEffect(bundles) {
        slots.indices.forEach { i -> if (slots[i] != null && bundles.none { it.id == slots[i]?.id }) slots[i] = null }
    }
    androidx.compose.runtime.LaunchedEffect(n) { if (active >= n) active = 0 }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(Modifier.weight(1f).fillMaxHeight(), title = "폰 앨범") {
            if (bundles.isEmpty()) {
                Text(
                    "아직 받은 묶음이 없어요.\n폰 카메라에서 실내 → 전방 → 후방 3장을 찍어 보내면 여기에 한 묶음씩 쌓여요.",
                    color = Tokens.text2, fontSize = Tokens.bodySize,
                )
            } else {
                Text("묶음을 누르면 오른쪽에서 고른 칸(${active + 1}컷)에 들어가요.", color = Tokens.text2, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(bundles, key = { it.id }) { b ->
                        val used = slots.take(n).withIndex().filter { it.value?.id == b.id }.map { it.index + 1 }
                        BundleRow(b, used,
                            onClick = { slots[active] = b; active = PhoneAlbum.nextEmpty(slots.take(n), active); message = null },
                            onDelete = { scope.launch { graph.phoneAlbum.delete(b.id) } })
                    }
                }
            }
        }
        Card(Modifier.width(720.dp).fillMaxHeight(), title = "프레임 만들기") {
            SectionLabel("프레임")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                layouts.forEach { l ->
                    Chip(
                        when (l) { Layout.CUT_FRAME -> "L2 네컷 (2~4컷)"; Layout.ROUTE_OVERLAY -> "L3 경로 (1컷)"; else -> l.label },
                        selected = l == layout,
                    ) { layout = l }
                }
            }
            Text(
                "L1 오픈카는 차량 실내 카메라 전용이라 여기서는 빠져요. 편집 화면의 ✨ AI 오픈카로 만들 수 있어요.",
                color = Tokens.text3, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
            SectionLabel("컷 칸 — 칸을 고르고 왼쪽 묶음을 누르세요", Modifier.padding(top = 20.dp))
            val rows = if (n == 4) listOf(0..1, 2..3) else listOf(0..0)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        r.forEach { i ->
                            SlotBox(i + 1, slots[i], i == active, Modifier.weight(1f),
                                onClick = { active = i },
                                onClear = { slots[i] = null; active = i })
                        }
                        if (n == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val cuts = PhoneAlbum.cutsFor(layout, slots.toList())
            PrimaryButton(
                if (cuts == null) "컷을 ${PhoneAlbum.minCuts(layout)}개 이상 넣어 주세요" else "이 프레임으로 꾸미기 (${cuts.size}컷)",
                Modifier.fillMaxWidth(), enabled = cuts != null && !busy,
            ) {
                val c = cuts ?: return@PrimaryButton
                busy = true
                scope.launch {
                    val ok = graph.editPhoneBundles(layout, c)
                    message = if (ok) null else "지금은 촬영 중이라 넘길 수 없어요. 끝난 뒤 다시 눌러 주세요"
                    busy = false
                }
            }
            message?.let { Text(it, color = Tokens.warn, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun BundleRow(b: PhoneBundle, usedIn: List<Int>, onClick: () -> Unit, onDelete: () -> Unit) {
    val on = usedIn.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().clip(Tokens.controlShape)
            .background(if (on) Tokens.accentSoft else Tokens.surface2)
            .border(if (on) 3.dp else 1.dp, if (on) Tokens.accent else Tokens.line, Tokens.controlShape)
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneAlbum.SLOTS.forEach { role -> Thumb(b.photos[role], PhoneAlbum.label(role), Modifier.weight(1f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${whenText(b.receivedAt)} · ${b.photos.size}장", color = Tokens.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (on) Text("  ${usedIn.joinToString(", ")}컷에 넣음", color = Tokens.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Chip("지우기", selected = false, onClick = onDelete)
        }
    }
}

/** 컷 칸: 넣은 묶음의 실내(크게) + 전방·후방(작게). 비어 있으면 번호만. */
@Composable
private fun SlotBox(no: Int, b: PhoneBundle?, active: Boolean, modifier: Modifier, onClick: () -> Unit, onClear: () -> Unit) {
    Column(
        modifier.clip(Tokens.controlShape).background(Tokens.surface2)
            .border(if (active) 4.dp else 1.dp, if (active) Tokens.accent else Tokens.line, Tokens.controlShape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (b == null) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tokens.bg, Tokens.controlShape), contentAlignment = Alignment.Center) {
                Text(if (active) "${no}컷 — 왼쪽에서 묶음을 누르세요" else "${no}컷 비어 있음", color = if (active) Tokens.accent else Tokens.text3, fontSize = 15.sp)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhoneAlbum.SLOTS.forEach { role -> Thumb(b.photos[role], PhoneAlbum.label(role), Modifier.weight(1f)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${no}컷 · ${whenText(b.receivedAt)}", color = Tokens.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Chip("빼기", selected = false, onClick = onClear)
            }
        }
    }
}

@Composable
private fun Thumb(path: String?, label: String, modifier: Modifier) {
    val img by produceState<ImageBitmap?>(null, path) {
        value = path?.let { p -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() } }
    }
    Box(modifier.aspectRatio(4f / 3f).clip(Tokens.controlShape).background(Tokens.bg)) {
        img?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } // 찍힌 프레임 전체(자르지 않음)
            ?: Text("없음", color = Tokens.text3, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        Text(
            label, color = Tokens.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).background(Tokens.bg.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun whenText(ms: Long): String = SimpleDateFormat("M월 d일 HH:mm:ss", Locale.KOREA).format(Date(ms))
