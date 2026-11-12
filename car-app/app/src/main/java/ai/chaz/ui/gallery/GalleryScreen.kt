package ai.chaz.ui.gallery

import ai.chaz.AppGraph
import ai.chaz.capture.CameraRole
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
 * 갤러리 — 폰 앨범. 폰에서 직접 찍어 보낸 실내·전방·후방 묶음이 모인다.
 * 묶음을 고르고, 자리(실내·전방·후방 프레임)마다 넣을 사진을 고른 뒤 편집으로 넘긴다.
 * 주행 중에는 DrivingLockOverlay 가 덮는다(다단계 터치 금지).
 */
@Composable
fun GalleryScreen(graph: AppGraph) {
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = bundles.firstOrNull { it.id == selectedId } ?: bundles.firstOrNull()

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(Modifier.width(520.dp).fillMaxHeight(), title = "폰 앨범") {
            if (bundles.isEmpty()) {
                Text(
                    "아직 받은 묶음이 없어요.\n폰의 '실내 카메라로 쓰기'에서 실내 → 전방 → 후방 3장을 찍어 보내면 여기에 모여요.",
                    color = Tokens.text2, fontSize = Tokens.bodySize,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(bundles, key = { it.id }) { b ->
                        BundleRow(b, b.id == selected?.id) { selectedId = b.id }
                    }
                }
            }
        }
        if (selected != null) BundleEditor(graph, selected, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun BundleRow(b: PhoneBundle, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Tokens.controlShape)
            .background(if (selected) Tokens.accentSoft else Tokens.surface2)
            .border(if (selected) 3.dp else 1.dp, if (selected) Tokens.accent else Tokens.line, Tokens.controlShape)
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneAlbum.SLOTS.forEach { role ->
                Thumb(b.photos[role], PhoneAlbum.label(role), Modifier.weight(1f))
            }
        }
        Text("${when0(b.receivedAt)} · ${b.photos.size}장", color = Tokens.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BundleEditor(graph: AppGraph, b: PhoneBundle, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    // 자리 → 넣을 사진(찍은 자리). 기본은 찍은 자리 그대로.
    val slots = remember(b.id) { mutableStateMapOf<CameraRole, CameraRole?>().apply { putAll(PhoneAlbum.defaultSlots(b)) } }
    var message by remember(b.id) { mutableStateOf<String?>(null) }
    var busy by remember(b.id) { mutableStateOf(false) }

    Card(modifier, title = "자리마다 사진 고르기") {
        Text("각 프레임 자리에 넣을 사진을 고르세요. 같은 사진을 여러 자리에 넣거나 비워 둘 수도 있어요.", color = Tokens.text2, fontSize = 15.sp)
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PhoneAlbum.SLOTS.forEach { slot ->
                Column {
                    SectionLabel("${PhoneAlbum.label(slot)} 자리")
                    Row(Modifier.height(132.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhoneAlbum.SLOTS.filter { it in b.photos }.forEach { photo ->
                            val on = slots[slot] == photo
                            Box(
                                Modifier.fillMaxHeight().aspectRatio(4f / 3f).clip(Tokens.controlShape)
                                    .border(if (on) 4.dp else 1.dp, if (on) Tokens.accent else Tokens.line, Tokens.controlShape)
                                    .clickable { slots[slot] = photo },
                            ) { Thumb(b.photos[photo], "${PhoneAlbum.label(photo)} 사진", Modifier.fillMaxSize()) }
                        }
                        Chip("비움", selected = slots[slot] == null) { slots[slot] = null }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("이 구성으로 꾸미기", Modifier.weight(1f), enabled = !busy && slots.values.any { it != null }) {
                busy = true
                scope.launch {
                    val ok = graph.editPhoneBundle(b, slots.toMap())
                    message = if (ok) null else "지금은 촬영 중이라 넘길 수 없어요. 끝난 뒤 다시 눌러 주세요"
                    busy = false
                }
            }
            Chip("묶음 지우기", selected = false) { scope.launch { graph.phoneAlbum.delete(b.id) } }
        }
        message?.let { Text(it, color = Tokens.warn, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun Thumb(path: String?, label: String, modifier: Modifier) {
    val img by produceState<ImageBitmap?>(null, path) {
        value = path?.let { p -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() } }
    }
    Box(modifier.aspectRatio(4f / 3f).clip(Tokens.controlShape).background(Tokens.bg)) {
        img?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text("없음", color = Tokens.text3, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        Text(
            label, color = Tokens.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).background(Tokens.bg.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun when0(ms: Long): String = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(ms))
