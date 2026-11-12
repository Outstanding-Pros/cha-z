package ai.chaz.ui.gallery

import ai.chaz.AppGraph
import ai.chaz.media.Layout
import ai.chaz.sync.AlbumOrigin
import ai.chaz.sync.AlbumVideo
import ai.chaz.sync.AlbumPick
import ai.chaz.sync.MadePhoto
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
 * 편집 탭 — ① 프레임 먼저 고르기 → ② 그 프레임 전용 칸 구성(왼쪽 앨범에서 아이템을 칸에 올림) → '만들기' → 편집 화면.
 * L2 네컷: 칸마다 앨범 사진 한 장(묶음의 실내·전방·후방 중 한 장, 또는 앨범에서 만든 사진). L1·L3: 묶음 1개.
 * 앨범 보기·지우기·'만들기'(AI 합치기 등)는 앨범 탭(AlbumScreen). 방금 찍은 촬영(AppGraph.albumDraft)은 프레임을 고르면 칸에 들어간다.
 * 주행 중에는 DrivingLockOverlay 가 덮는다.
 */
@Composable
fun GalleryScreen(graph: AppGraph) {
    // L1 오픈카는 창문 마스크가 있을 때만(고정 위치 차량 실내 카메라).
    val layouts = listOf(Layout.CUT_FRAME, Layout.WINDOW_SWAP, Layout.ROUTE_OVERLAY).filter { graph.composer.available(it) }
    var frame by remember { mutableStateOf<Layout?>(null) }
    val f = frame
    if (f == null) FramePicker(graph, layouts) { frame = it }
    else if (f == Layout.CUT_FRAME) FourCutBuilder(graph, onBack = { frame = null })
    else BundleFrameBuilder(graph, f, onBack = { frame = null })
}

private fun frameTitle(l: Layout): String = when (l) {
    Layout.CUT_FRAME -> "L2 네컷"; Layout.WINDOW_SWAP -> "L1 오픈카"; Layout.ROUTE_OVERLAY -> "L3 경로"; else -> l.label
}

private fun frameDesc(l: Layout): String = when (l) {
    Layout.CUT_FRAME -> "4칸 필름. 칸마다 앨범 사진을 한 장씩 — 묶음의 실내·전방·후방, 또는 앨범에서 만든 사진(✨ 사람+풍경, 파노라마 등). (2~4컷)"
    Layout.WINDOW_SWAP -> "차 실내 카메라 사진의 지붕을 열고 바깥 풍경을 채워요. 실내를 차량 카메라로 찍은 묶음만 돼요. (1컷)"
    Layout.ROUTE_OVERLAY -> "전방 풍경 위에 오늘 달린 길과 다음 목적지를 얹어요. (1컷)"
    else -> l.label
}

/** ① 프레임 고르기. */
@Composable
private fun FramePicker(graph: AppGraph, layouts: List<Layout>, onPick: (Layout) -> Unit) {
    val draft by graph.albumDraft.collectAsState()
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    val made by graph.phoneAlbum.made.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("어떤 프레임으로 만들까요?", color = Tokens.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            draft?.let { "방금 찍은 사진 ${it.bundleIds.size}묶음이 있어요. 프레임을 고르면 칸에 들어가요." }
                ?: "프레임을 고르면 앨범(묶음 ${bundles.size}개 · 만든 사진 ${made.size}장)에서 칸마다 골라 올려요.",
            color = if (draft != null) Tokens.accent else Tokens.text2, fontSize = Tokens.bodySize,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            layouts.forEach { l ->
                Column(
                    Modifier.weight(1f).clip(Tokens.cardShape).background(Tokens.surface)
                        .border(1.dp, Tokens.line, Tokens.cardShape).clickable { onPick(l) }.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(frameTitle(l), color = Tokens.accent, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text(frameDesc(l), color = Tokens.text2, fontSize = 17.sp, lineHeight = 26.sp)
                    Spacer(Modifier.padding(top = 8.dp))
                    PrimaryButton("이 프레임으로", Modifier.fillMaxWidth()) { onPick(l) }
                }
            }
        }
    }
}

/** 오른쪽 칸 카드 공통: 제목·설명·칸 격자·만들기 버튼. */
@Composable
private fun SlotsCard(
    layout: Layout, onBack: () -> Unit, hint: String, n: Int,
    slot: @Composable (Int, Modifier) -> Unit,
    buttonText: String, enabled: Boolean, message: String?, onMake: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier.fillMaxHeight(), title = frameTitle(layout), trailing = { Chip("← 프레임 다시 고르기", selected = false, onClick = onBack) }) {
        Text(frameDesc(layout), color = Tokens.text3, fontSize = 14.sp, lineHeight = 20.sp)
        SectionLabel(hint, Modifier.padding(top = 16.dp))
        val grid = if (n == 4) listOf(0..1, 2..3) else listOf(0..0)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            grid.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    r.forEach { i -> slot(i, Modifier.weight(1f)) }
                    if (n == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(buttonText, Modifier.fillMaxWidth(), enabled = enabled, onClick = onMake)
        message?.let { Text(it, color = Tokens.warn, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun SlotFrame(no: Int, active: Boolean, filled: Boolean, caption: String, modifier: Modifier, onClick: () -> Unit, onClear: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier.clip(Tokens.controlShape).background(Tokens.surface2)
            .border(if (active) 4.dp else 1.dp, if (active) Tokens.accent else Tokens.line, Tokens.controlShape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!filled) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tokens.bg, Tokens.controlShape), contentAlignment = Alignment.Center) {
                Text(if (active) "${no}컷 — 왼쪽에서 고르세요" else "${no}컷 비어 있음", color = if (active) Tokens.accent else Tokens.text3, fontSize = 15.sp)
            }
            return@Column
        }
        content()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${no}컷 · $caption", color = Tokens.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Chip("빼기", selected = false, onClick = onClear)
        }
    }
}

private const val BUSY_TEXT = "지금은 만들 수 없어요(촬영 중이거나 사진이 준비되지 않음). 잠시 뒤 다시 눌러 주세요"

/** ② L2 네컷: 칸마다 앨범 사진 한 장(묶음의 실내·전방·후방 중 하나, 또는 만든 사진). */
@Composable
private fun FourCutBuilder(graph: AppGraph, onBack: () -> Unit) {
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    val videos by graph.phoneAlbum.videos.collectAsState()
    val made by graph.phoneAlbum.made.collectAsState()
    val draft by graph.albumDraft.collectAsState()
    val scope = rememberCoroutineScope()
    val n = 4
    val slots = remember { mutableStateListOf<AlbumPick?>(null, null, null, null) }
    var active by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun put(p: AlbumPick) { slots[active] = p; active = PhoneAlbum.nextEmpty(slots.toList(), active); message = null }
    // 방금 찍은 촬영: 컷마다 실내 사진을 칸에 미리 넣는다
    LaunchedEffect(draft, bundles) {
        val d = draft ?: return@LaunchedEffect
        val picks = d.bundleIds.mapNotNull { id -> bundles.firstOrNull { it.id == id } }.sortedBy { it.cut }.mapNotNull(PhoneAlbum::firstPhoto).take(n)
        if (picks.isEmpty()) return@LaunchedEffect
        slots.indices.forEach { i -> slots[i] = picks.getOrNull(i) }
        active = PhoneAlbum.nextEmpty(slots.toList(), picks.size - 1)
        graph.albumDraft.value = null
        listState.scrollToItem(0)
    }
    // 앨범에서 지운 사진이 칸에 남지 않게
    LaunchedEffect(bundles, made) {
        slots.indices.forEach { i ->
            val gone = when (val p = slots[i]) {
                is AlbumPick.Photo -> bundles.none { it.id == p.bundle.id }
                is AlbumPick.Made -> made.none { it.id == p.photo.id }
                null -> false
            }
            if (gone) slots[i] = null
        }
    }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(Modifier.weight(1f).fillMaxHeight(), title = "앨범에서 고르기") {
            if (bundles.isEmpty() && made.isEmpty()) {
                Text("앨범이 비어 있어요. '차~즈'로 찍거나 폰에서 묶음을 보내 주세요.", color = Tokens.text2, fontSize = Tokens.bodySize)
                return@Card
            }
            Text("사진 한 장을 누르면 고른 칸(${active + 1}컷)에 들어가요. 합친 사진은 앨범 탭에서 만들어요.", color = Tokens.text2, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
            val used = slots.mapIndexedNotNull { i, p -> p?.let { it.key to i + 1 } }.groupBy({ it.first }, { it.second })
            val rows: List<Any> = buildList {
                if (made.isNotEmpty()) { add("만든 사진"); addAll(made) }
                add("찍은 묶음")
                val placed = mutableSetOf<String>()
                bundles.forEach { b ->
                    videos.firstOrNull { it.group == b.group && it.id !in placed }?.let { add(it); placed += it.id }
                    add(b)
                }
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { r -> when (r) { is PhoneBundle -> r.id; is MadePhoto -> r.id; is AlbumVideo -> r.id; else -> "h:$r" } }) { r ->
                    when (r) {
                        is String -> SectionLabel(r, Modifier.padding(top = 6.dp))
                        is MadePhoto -> {
                            val pick = AlbumPick.Made(r)
                            PickRow(used[pick.key], onClick = { put(pick) }) {
                                Thumb(r.path, "", Modifier.width(220.dp), ratio = 16f / 9f)
                                Text((if (r.ai) "✨ " else "") + r.label, color = Tokens.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                        is PhoneBundle -> Column(
                            Modifier.fillMaxWidth().clip(Tokens.controlShape).background(Tokens.surface2).border(1.dp, Tokens.line, Tokens.controlShape).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PhoneAlbum.SLOTS.forEach { role ->
                                    val pick = AlbumPick.Photo(r, role)
                                    val on = used[pick.key]
                                    Box(
                                        Modifier.weight(1f).clip(Tokens.controlShape)
                                            .border(if (on != null) 3.dp else 0.dp, if (on != null) Tokens.accent else Tokens.surface2, Tokens.controlShape)
                                            .clickable(enabled = r.photos.containsKey(role)) { put(pick) },
                                    ) {
                                        Thumb(r.photos[role], PhoneAlbum.label(role) + (on?.let { " · ${it.joinToString(",")}컷" } ?: ""), Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AlbumTag(originText(r))
                                Text("  ${whenText(r.receivedAt)}", color = Tokens.text2, fontSize = 15.sp)
                            }
                        }
                        is AlbumVideo -> {
                            val cuts = bundles.filter { it.group == r.group }.sortedBy { it.cut }.mapNotNull(PhoneAlbum::firstPhoto)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AlbumTag("▶ 영상 ${r.durationMs / 1000}초 · ${whenText(r.at)}")
                                Spacer(Modifier.weight(1f))
                                if (cuts.size >= 2) Chip("이 촬영 ${minOf(cuts.size, n)}컷 실내 → 네컷에 넣기", selected = false) {
                                    cuts.take(n).forEachIndexed { i, p -> slots[i] = p }; message = null
                                }
                            }
                        }
                    }
                }
            }
        }
        val filled = slots.filterNotNull()
        SlotsCard(
            Layout.CUT_FRAME, onBack, "칸 — 칸을 고르고 왼쪽 사진을 누르세요", n,
            slot = { i, m ->
                val p = slots[i]
                SlotFrame(i + 1, i == active, p != null, p?.let { (if (it.ai) "✨ " else "") + it.label } ?: "", m,
                    onClick = { active = i }, onClear = { slots[i] = null; active = i }) {
                    // 칸에 실제로 들어가는 모양(16:9 로 채움)
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Tokens.controlShape).background(Tokens.bg)) {
                        PhotoImage(p?.path, Modifier.fillMaxSize())
                    }
                }
            },
            buttonText = if (filled.size >= 2) "이 구성으로 만들기 (${filled.size}컷)" else "컷을 2개 이상 넣어 주세요",
            enabled = filled.size >= 2 && !busy, message = message,
            onMake = {
                busy = true
                scope.launch { message = if (graph.editPicks(filled)) null else BUSY_TEXT; busy = false }
            },
            modifier = Modifier.width(760.dp),
        )
    }
}

@Composable
private fun PickRow(usedIn: List<Int>?, onClick: () -> Unit, content: @Composable () -> Unit) {
    val on = usedIn != null
    Row(
        Modifier.fillMaxWidth().clip(Tokens.controlShape)
            .background(if (on) Tokens.accentSoft else Tokens.surface2)
            .border(if (on) 3.dp else 1.dp, if (on) Tokens.accent else Tokens.line, Tokens.controlShape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.weight(1f))
        usedIn?.let { Text("${it.joinToString(", ")}컷에 넣음", color = Tokens.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}

/** ② L1 오픈카 · L3 경로: 칸 1개 = 묶음 하나(실내·전방·후방을 프레임이 알아서 쓴다). */
@Composable
private fun BundleFrameBuilder(graph: AppGraph, layout: Layout, onBack: () -> Unit) {
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    val draft by graph.albumDraft.collectAsState()
    val scope = rememberCoroutineScope()
    var slot by remember(layout) { mutableStateOf<PhoneBundle?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun put(b: PhoneBundle) {
        if (!PhoneAlbum.accepts(layout, b)) { message = "L1 오픈카에는 실내를 차량 카메라로 찍은 묶음만 넣을 수 있어요"; return }
        slot = b; message = null
    }
    LaunchedEffect(draft, bundles, layout) {
        val d = draft ?: return@LaunchedEffect
        bundles.filter { it.id in d.bundleIds && PhoneAlbum.accepts(layout, it) }.minByOrNull { it.cut }?.let { slot = it }
        graph.albumDraft.value = null
    }
    LaunchedEffect(bundles) { if (slot != null && bundles.none { it.id == slot?.id }) slot = null }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(Modifier.weight(1f).fillMaxHeight(), title = "앨범에서 고르기") {
            if (bundles.isEmpty()) {
                Text("앨범이 비어 있어요. '차~즈'로 찍거나 폰에서 묶음을 보내 주세요.", color = Tokens.text2, fontSize = Tokens.bodySize)
                return@Card
            }
            Text("묶음을 누르면 칸에 들어가요.", color = Tokens.text2, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(bundles, key = { it.id }) { b ->
                    val fits = PhoneAlbum.accepts(layout, b)
                    val on = slot?.id == b.id
                    Column(
                        Modifier.fillMaxWidth().clip(Tokens.controlShape)
                            .background(if (on) Tokens.accentSoft else Tokens.surface2)
                            .border(if (on) 3.dp else 1.dp, if (on) Tokens.accent else Tokens.line, Tokens.controlShape)
                            .clickable { put(b) }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BundleThumbs(b)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AlbumTag(originText(b))
                            Text("  ${whenText(b.receivedAt)}" + if (fits) "" else " · 이 프레임에 못 씀", color = if (fits) Tokens.text2 else Tokens.text3, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
        SlotsCard(
            layout, onBack, "칸 — 왼쪽 묶음을 누르세요", 1,
            slot = { _, m ->
                val b = slot
                SlotFrame(1, true, b != null, b?.let(::originText) ?: "", m, onClick = {}, onClear = { slot = null }) {
                    b?.let { BundleThumbs(it) }
                }
            },
            buttonText = if (slot != null) "이 구성으로 만들기" else "묶음을 하나 넣어 주세요",
            enabled = slot != null && !busy, message = message,
            onMake = {
                slot?.let { b ->
                    busy = true
                    scope.launch { message = if (graph.editPhoneBundles(layout, listOf(b))) null else BUSY_TEXT; busy = false }
                }
            },
            modifier = Modifier.width(760.dp),
        )
    }
}
