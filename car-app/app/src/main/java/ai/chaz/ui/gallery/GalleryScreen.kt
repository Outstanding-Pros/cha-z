package ai.chaz.ui.gallery

import ai.chaz.AppGraph
import ai.chaz.media.Layout
import ai.chaz.sync.AlbumOrigin
import ai.chaz.sync.AlbumVideo
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 편집 탭 — 왼쪽 앨범(폰 묶음 + 차~즈 단발·동영상), 오른쪽 프레임 고르기.
 * 묶음 하나 = 차량 카메라(실내·전방·후방)가 같은 순간 찍은 한 컷. 프레임을 정하고 칸을 고른 뒤 왼쪽 묶음을 누르면 그 칸에 들어간다 → '꾸미기'.
 * 방금 찍은 촬영은 AppGraph.albumDraft 로 칸에 미리 들어가 있다. 주행 중에는 DrivingLockOverlay 가 덮는다(영상 재생·다단계 터치 금지).
 */
@Composable
fun GalleryScreen(graph: AppGraph) {
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    val videos by graph.phoneAlbum.videos.collectAsState()
    val draft by graph.albumDraft.collectAsState()
    val scope = rememberCoroutineScope()
    // L1 오픈카는 창문 마스크가 있을 때만(고정 위치 차량 실내 카메라). 칸에는 실내를 차량 카메라로 찍은 묶음만 올라간다.
    val layouts = listOf(Layout.WINDOW_SWAP, Layout.CUT_FRAME, Layout.ROUTE_OVERLAY).filter { graph.composer.available(it) }
    var layout by remember { mutableStateOf(if (Layout.CUT_FRAME in layouts) Layout.CUT_FRAME else layouts.firstOrNull() ?: Layout.CUT_FRAME) }
    val slots = remember { mutableStateListOf<PhoneBundle?>(null, null, null, null) }
    var active by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val n = PhoneAlbum.cutSlots(layout)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun put(b: PhoneBundle) {
        if (!PhoneAlbum.accepts(layout, b)) { message = "L1 오픈카에는 실내를 차량 카메라로 찍은 묶음만 넣을 수 있어요"; return }
        slots[active] = b; active = PhoneAlbum.nextEmpty(slots.take(n), active); message = null
    }
    fun fill(to: Layout, picks: List<PhoneBundle>) {
        layout = to
        slots.indices.forEach { slots[it] = picks.getOrNull(it) }
        active = PhoneAlbum.nextEmpty(slots.take(PhoneAlbum.cutSlots(to)), (picks.size - 1).coerceAtLeast(0))
        message = null
    }
    // 방금 찍은 촬영: 칸에 미리 넣어 둔다
    LaunchedEffect(draft, bundles) {
        val d = draft ?: return@LaunchedEffect
        val picks = d.bundleIds.mapNotNull { id -> bundles.firstOrNull { it.id == id } }
        if (picks.isEmpty()) return@LaunchedEffect
        val to = if (d.layout in layouts) d.layout else layout
        fill(to, picks.filter { PhoneAlbum.accepts(to, it) })
        graph.albumDraft.value = null
        listState.scrollToItem(0) // 방금 찍은 촬영이 맨 위에 보이게
    }
    // 지운 묶음이 칸에 남지 않게
    LaunchedEffect(bundles) {
        slots.indices.forEach { i -> if (slots[i] != null && bundles.none { it.id == slots[i]?.id }) slots[i] = null }
    }
    LaunchedEffect(n) { if (active >= n) active = 0 }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(Modifier.weight(1f).fillMaxHeight(), title = "앨범") {
            if (bundles.isEmpty() && videos.isEmpty()) {
                Text(
                    "아직 찍은 사진이 없어요.\n'차~즈'(단발·동영상)로 찍거나, 폰 카메라에서 실내 → 전방 → 후방 3장을 찍어 보내면 여기에 쌓여요.",
                    color = Tokens.text2, fontSize = Tokens.bodySize,
                )
            } else {
                Text("묶음을 누르면 오른쪽에서 고른 칸(${active + 1}컷)에 들어가요.", color = Tokens.text2, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
                // 동영상은 같은 촬영의 묶음들 바로 위에
                val rows: List<Any> = buildList {
                    val placed = mutableSetOf<String>()
                    bundles.forEach { b ->
                        videos.firstOrNull { it.group == b.group && it.id !in placed }?.let { add(it); placed += it.id }
                        add(b)
                    }
                    videos.filter { it.id !in placed }.forEach { add(it) }
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rows, key = { (it as? PhoneBundle)?.id ?: (it as AlbumVideo).id }) { row ->
                        when (row) {
                            is PhoneBundle -> {
                                val used = slots.take(n).withIndex().filter { it.value?.id == row.id }.map { it.index + 1 }
                                BundleRow(row, used, PhoneAlbum.accepts(layout, row),
                                    onClick = { put(row) },
                                    onDelete = { scope.launch { graph.phoneAlbum.delete(row.id) } })
                            }
                            is AlbumVideo -> {
                                val cuts = bundles.filter { it.group == row.group }.sortedBy { it.cut }
                                VideoRow(row, cuts.size,
                                    onFill = { fill(Layout.CUT_FRAME, cuts.take(4)) },
                                    onDelete = { scope.launch { graph.phoneAlbum.delete(row.id) } })
                            }
                        }
                    }
                }
            }
        }
        Card(Modifier.width(720.dp).fillMaxHeight(), title = "프레임 고르고 앨범에서 올리기") {
            SectionLabel("프레임")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                layouts.forEach { l ->
                    Chip(
                        when (l) {
                            Layout.WINDOW_SWAP -> "L1 오픈카 (1컷)"; Layout.CUT_FRAME -> "L2 네컷 (2~4컷)"; Layout.ROUTE_OVERLAY -> "L3 경로 (1컷)"; else -> l.label
                        },
                        selected = l == layout,
                    ) { layout = l; message = null }
                }
            }
            Text(
                if (Layout.WINDOW_SWAP in layouts) "L1 오픈카는 실내를 차량 카메라로 찍은 묶음만 돼요. 폰 사진은 편집 화면의 ✨ AI 오픈카로 만들 수 있어요."
                else "L1 오픈카는 차량 실내 카메라가 있을 때만 돼요. 편집 화면의 ✨ AI 오픈카로 만들 수 있어요.",
                color = Tokens.text3, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
            SectionLabel("컷 칸 — 칸을 고르고 왼쪽 묶음을 누르세요", Modifier.padding(top = 20.dp))
            val grid = if (n == 4) listOf(0..1, 2..3) else listOf(0..0)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                grid.forEach { r ->
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
            val wrong = slots.take(n).filterNotNull().any { !PhoneAlbum.accepts(layout, it) }
            PrimaryButton(
                when {
                    wrong -> "오픈카에 못 쓰는 묶음이 있어요 — 빼 주세요"
                    cuts == null -> "컷을 ${PhoneAlbum.minCuts(layout)}개 이상 넣어 주세요"
                    else -> "이 프레임으로 꾸미기 (${cuts.size}컷)"
                },
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

private fun originText(b: PhoneBundle): String = when (b.origin) {
    AlbumOrigin.PHONE -> "폰"
    AlbumOrigin.CAR_SINGLE -> "단발"
    AlbumOrigin.CAR_VIDEO -> "동영상 ${b.cut}컷째"
}

@Composable
private fun BundleRow(b: PhoneBundle, usedIn: List<Int>, fits: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
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
            Tag(originText(b))
            Text("  ${whenText(b.receivedAt)} · ${b.photos.size}장", color = if (fits) Tokens.text else Tokens.text3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (on) Text("  ${usedIn.joinToString(", ")}컷에 넣음", color = Tokens.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Chip("지우기", selected = false, onClick = onDelete)
        }
    }
}

/** 동영상 촬영의 12초 영상: 실내·전방·후방 스냅샷을 0.5초마다 넘겨 재생. */
@Composable
private fun VideoRow(v: AlbumVideo, cutCount: Int, onFill: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Tokens.controlShape).background(Tokens.surface2)
            .border(1.dp, Tokens.line, Tokens.controlShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneAlbum.SLOTS.forEach { role -> ClipThumb(v.clips[role], PhoneAlbum.label(role), Modifier.weight(1f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag("▶ 영상 ${v.durationMs / 1000}초")
            Text(whenText(v.at), color = Tokens.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (cutCount >= 2) Chip("이 촬영 ${minOf(cutCount, 4)}컷 → 네컷에 넣기", selected = false, onClick = onFill)
            Chip("영상 지우기", selected = false, onClick = onDelete)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text, color = Tokens.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Tokens.accentSoft, Tokens.controlShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 컷 칸: 넣은 묶음의 실내·전방·후방. 비어 있으면 번호만. */
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
                Text("${no}컷 · ${originText(b)}", color = Tokens.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                Chip("빼기", selected = false, onClick = onClear)
            }
        }
    }
}

@Composable
private fun ClipThumb(dir: String?, label: String, modifier: Modifier) {
    val files = remember(dir) { dir?.let { d -> File(d).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } }.orEmpty() }
    var i by remember(dir) { mutableStateOf(0) }
    LaunchedEffect(dir, files.size) {
        while (files.size > 1) { delay(500); i = (i + 1) % files.size }
    }
    Thumb(files.getOrNull(i)?.absolutePath, label, modifier)
}

@Composable
private fun Thumb(path: String?, label: String, modifier: Modifier) {
    // 새 사진을 읽는 동안 이전 사진을 그대로 둔다 (영상 재생 때 깜빡임 방지)
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        img = path?.let { p -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() } }
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
