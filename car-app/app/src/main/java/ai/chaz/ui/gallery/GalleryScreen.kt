package ai.chaz.ui.gallery

import ai.chaz.AppGraph
import ai.chaz.media.Layout
import ai.chaz.sync.AlbumOrigin
import ai.chaz.sync.AlbumVideo
import ai.chaz.sync.CutRecipe
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
 * 편집 탭 — ① 프레임 먼저 고르기 → ② 그 프레임에 맞는 칸 구성(왼쪽 앨범에서 묶음을 칸에 올림) → '만들기' → 편집 화면.
 * 묶음 하나 = 차량 카메라(실내·전방·후방)가 같은 순간 찍은 한 컷.
 * L2 네컷은 칸마다 조합(실내·전방·후방 한 장 / 실내+전방·실내+후방 AI 조합)을 고른다. L1·L3 은 묶음 1개.
 * 방금 찍은 촬영(AppGraph.albumDraft)은 프레임을 고르면 칸에 들어간다. 주행 중에는 DrivingLockOverlay 가 덮는다.
 */
@Composable
fun GalleryScreen(graph: AppGraph) {
    // L1 오픈카는 창문 마스크가 있을 때만(고정 위치 차량 실내 카메라).
    val layouts = listOf(Layout.CUT_FRAME, Layout.WINDOW_SWAP, Layout.ROUTE_OVERLAY).filter { graph.composer.available(it) }
    var frame by remember { mutableStateOf<Layout?>(null) }
    val f = frame
    if (f == null) FramePicker(graph, layouts) { frame = it }
    else FrameBuilder(graph, f, onBack = { frame = null })
}

private fun frameTitle(l: Layout): String = when (l) {
    Layout.CUT_FRAME -> "L2 네컷"; Layout.WINDOW_SWAP -> "L1 오픈카"; Layout.ROUTE_OVERLAY -> "L3 경로"; else -> l.label
}

private fun frameDesc(l: Layout): String = when (l) {
    Layout.CUT_FRAME -> "4칸 필름. 칸마다 실내·전방·후방 사진을 하나씩 넣거나, ✨ AI로 실내+전방·실내+후방을 섞어 넣어요. (2~4컷)"
    Layout.WINDOW_SWAP -> "차 실내 카메라 사진의 지붕을 열고 바깥 풍경을 채워요. 실내를 차량 카메라로 찍은 묶음만 돼요. (1컷)"
    Layout.ROUTE_OVERLAY -> "전방 풍경 위에 오늘 달린 길과 다음 목적지를 얹어요. (1컷)"
    else -> l.label
}

/** ① 프레임 고르기. */
@Composable
private fun FramePicker(graph: AppGraph, layouts: List<Layout>, onPick: (Layout) -> Unit) {
    val draft by graph.albumDraft.collectAsState()
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("어떤 프레임으로 만들까요?", color = Tokens.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            draft?.let { "방금 찍은 사진 ${it.bundleIds.size}묶음이 있어요. 프레임을 고르면 칸에 들어가요." }
                ?: "프레임을 고르면 앨범(${bundles.size}묶음)에서 칸마다 사진을 골라 올려요.",
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

/** ② 프레임별 칸 구성: 왼쪽 앨범, 오른쪽 칸. */
@Composable
private fun FrameBuilder(graph: AppGraph, layout: Layout, onBack: () -> Unit) {
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    val videos by graph.phoneAlbum.videos.collectAsState()
    val draft by graph.albumDraft.collectAsState()
    val mixes by graph.mixes.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val n = PhoneAlbum.cutSlots(layout)
    val fourCut = layout == Layout.CUT_FRAME
    val slots = remember(layout) { mutableStateListOf<PhoneBundle?>(null, null, null, null) }
    val recipes = remember(layout) { mutableStateListOf(CutRecipe.INTERIOR, CutRecipe.INTERIOR, CutRecipe.INTERIOR, CutRecipe.INTERIOR) }
    var active by remember(layout) { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askConsent by remember { mutableStateOf<CutRecipe?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun put(b: PhoneBundle) {
        if (!PhoneAlbum.accepts(layout, b)) { message = "L1 오픈카에는 실내를 차량 카메라로 찍은 묶음만 넣을 수 있어요"; return }
        slots[active] = b
        if (!recipes[active].fits(b)) recipes[active] = CutRecipe.default(b)
        active = PhoneAlbum.nextEmpty(slots.take(n), active); message = null
    }
    fun setRecipe(r: CutRecipe) {
        val b = slots[active] ?: run { message = "먼저 왼쪽에서 ${active + 1}컷에 넣을 묶음을 눌러 주세요"; return }
        if (!r.fits(b)) { message = "이 묶음에는 ${r.label} 사진이 없어요"; return }
        if (r.ai && !ai.chaz.sync.AiConsent.given(ctx)) { askConsent = r; return }
        recipes[active] = r; message = null
    }
    // 방금 찍은 촬영: 칸에 미리 넣어 둔다
    LaunchedEffect(draft, bundles, layout) {
        val d = draft ?: return@LaunchedEffect
        val picks = d.bundleIds.mapNotNull { id -> bundles.firstOrNull { it.id == id } }.sortedBy { it.cut }
            .filter { PhoneAlbum.accepts(layout, it) }.take(n)
        if (picks.isEmpty()) return@LaunchedEffect
        slots.indices.forEach { i -> slots[i] = picks.getOrNull(i); picks.getOrNull(i)?.let { recipes[i] = CutRecipe.default(it) } }
        active = PhoneAlbum.nextEmpty(slots.take(n), picks.size - 1)
        graph.albumDraft.value = null
        listState.scrollToItem(0)
    }
    // 지운 묶음이 칸에 남지 않게
    LaunchedEffect(bundles) {
        slots.indices.forEach { i -> if (slots[i] != null && bundles.none { it.id == slots[i]?.id }) slots[i] = null }
    }
    // AI 조합 칸은 넣는 즉시 서버에 요청 (결과는 묶음 폴더에 남아 다시 쓰인다)
    LaunchedEffect(slots.toList(), recipes.toList()) {
        if (!fourCut) return@LaunchedEffect
        (0 until n).forEach { i -> val b = slots[i]; val r = recipes[i]; if (b != null && r.ai) graph.requestMix(b, r) }
    }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(Modifier.weight(1f).fillMaxHeight(), title = "앨범") {
            AlbumList(bundles, videos, slots.take(n), layout, listState, active,
                onPick = { put(it) },
                onFill = { cuts -> cuts.take(n).forEachIndexed { i, b -> slots[i] = b; if (!recipes[i].fits(b)) recipes[i] = CutRecipe.default(b) } },
                onDelete = { id -> scope.launch { graph.phoneAlbum.delete(id) } })
        }
        Card(Modifier.width(760.dp).fillMaxHeight(), title = frameTitle(layout), trailing = { Chip("← 프레임 다시 고르기", selected = false, onClick = onBack) }) {
            Text(frameDesc(layout), color = Tokens.text3, fontSize = 14.sp, lineHeight = 20.sp)
            SectionLabel("칸 — 칸을 고르고 왼쪽 묶음을 누르세요", Modifier.padding(top = 16.dp))
            val grid = if (n == 4) listOf(0..1, 2..3) else listOf(0..0)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                grid.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        r.forEach { i ->
                            val b = slots[i]
                            val recipe = recipes[i]
                            val state: AppGraph.MixState? = when {
                                b == null -> null
                                !fourCut || !recipe.ai -> AppGraph.MixState.Done(b.photos[if (fourCut) recipe.roles[0] else CameraRole.INTERIOR] ?: "")
                                else -> graph.recipePath(b, recipe)?.let { AppGraph.MixState.Done(it) } ?: mixes[graph.mixKey(b, recipe)] ?: AppGraph.MixState.Working
                            }
                            SlotBox(i + 1, b, if (fourCut) recipe else null, state, i == active, Modifier.weight(1f),
                                onClick = { active = i },
                                onClear = { slots[i] = null; active = i },
                                onRetry = { b?.let { graph.requestMix(it, recipe, retry = true) } })
                        }
                        if (n == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (fourCut) {
                SectionLabel("${active + 1}컷 조합", Modifier.padding(top = 16.dp))
                val cur = slots[active]
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    CutRecipe.entries.forEach { r ->
                        Chip(if (r.ai) "✨ ${r.label}" else r.label, selected = cur != null && recipes[active] == r,
                            enabled = cur == null || r.fits(cur)) { setRecipe(r) }
                    }
                }
                Text(
                    "✨ 조합은 실내 사진(얼굴 포함)과 바깥 풍경이 차~즈 서버를 거쳐 AI(Google Gemini)로 보내져 한 장으로 섞여요. 칸마다 15초쯤 걸리고, 같은 묶음·조합은 한 번만 만들어요.",
                    color = Tokens.text3, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            val filled = (0 until n).mapNotNull { i -> slots[i]?.let { it to recipes[i] } }
            val wrong = filled.any { !PhoneAlbum.accepts(layout, it.first) }
            val waiting = fourCut && filled.any { (b, r) -> graph.recipePath(b, r) == null }
            val enough = filled.size >= PhoneAlbum.minCuts(layout)
            PrimaryButton(
                when {
                    wrong -> "오픈카에 못 쓰는 묶음이 있어요 — 빼 주세요"
                    !enough -> "컷을 ${PhoneAlbum.minCuts(layout)}개 이상 넣어 주세요"
                    waiting -> "✨ AI 조합이 끝나면 만들 수 있어요"
                    else -> "이 구성으로 만들기 (${filled.size}컷)"
                },
                Modifier.fillMaxWidth(), enabled = enough && !wrong && !waiting && !busy,
            ) {
                busy = true
                scope.launch {
                    val ok = if (fourCut) graph.editRecipes(filled)
                    else PhoneAlbum.cutsFor(layout, slots.toList())?.let { graph.editPhoneBundles(layout, it) } ?: false
                    message = if (ok) null else "지금은 만들 수 없어요(촬영 중이거나 사진이 준비되지 않음). 잠시 뒤 다시 눌러 주세요"
                    busy = false
                }
            }
            message?.let { Text(it, color = Tokens.warn, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    askConsent?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askConsent = null },
            containerColor = Tokens.surface,
            title = { Text("AI 조합 사용 동의", color = Tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "고른 묶음의 실내 사진(얼굴 포함)과 바깥 풍경이 차~즈 서버를 거쳐 AI 서비스(Google Gemini)로 전송되고, 한 장으로 섞여요.\n\n" +
                        "• 서버는 보낸 사진을 저장하지 않아요\n" +
                        "• 결과물에는 'AI 생성 포함' 표시가 남아요\n" +
                        "• 사진에 나온 모든 사람이 동의한 경우에만 사용해 주세요",
                    color = Tokens.text2, fontSize = 18.sp, lineHeight = 27.sp,
                )
            },
            confirmButton = {
                PrimaryButton("동의하고 ${r.label} 만들기", color = Tokens.ai) {
                    ai.chaz.sync.AiConsent.set(ctx, true)
                    askConsent = null
                    setRecipe(r)
                }
            },
            dismissButton = { Chip("동의 안 함", selected = false) { askConsent = null } },
        )
    }
}

/** 앨범 목록: 동영상은 같은 촬영의 묶음들 바로 위에. */
@Composable
private fun AlbumList(
    bundles: List<PhoneBundle>, videos: List<AlbumVideo>, slots: List<PhoneBundle?>, layout: Layout,
    listState: androidx.compose.foundation.lazy.LazyListState, active: Int,
    onPick: (PhoneBundle) -> Unit, onFill: (List<PhoneBundle>) -> Unit, onDelete: (String) -> Unit,
) {
    if (bundles.isEmpty() && videos.isEmpty()) {
        Text(
            "아직 찍은 사진이 없어요.\n'차~즈'(단발·동영상)로 찍거나, 폰 카메라에서 실내 → 전방 → 후방 3장을 찍어 보내면 여기에 쌓여요.",
            color = Tokens.text2, fontSize = Tokens.bodySize,
        )
        return
    }
    Text("묶음을 누르면 오른쪽에서 고른 칸(${active + 1}컷)에 들어가요.", color = Tokens.text2, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
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
                    val used = slots.withIndex().filter { it.value?.id == row.id }.map { it.index + 1 }
                    BundleRow(row, used, PhoneAlbum.accepts(layout, row), onClick = { onPick(row) }, onDelete = { onDelete(row.id) })
                }
                is AlbumVideo -> {
                    val cuts = bundles.filter { it.group == row.group }.sortedBy { it.cut }
                    VideoRow(row, if (layout == Layout.CUT_FRAME) cuts.size else 0, onFill = { onFill(cuts) }, onDelete = { onDelete(row.id) })
                }
            }
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

/**
 * 칸: 비어 있으면 번호만. 네컷 조합(recipe)이면 칸에 들어갈 사진 하나(AI 는 진행 상태), 아니면 묶음의 실내·전방·후방.
 */
@Composable
private fun SlotBox(
    no: Int, b: PhoneBundle?, recipe: CutRecipe?, state: AppGraph.MixState?, active: Boolean, modifier: Modifier,
    onClick: () -> Unit, onClear: () -> Unit, onRetry: () -> Unit,
) {
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
            return@Column
        }
        if (recipe != null) {
            // 칸에 실제로 들어가는 모양(16:9 로 채움)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Tokens.controlShape).background(Tokens.bg), contentAlignment = Alignment.Center) {
                when (state) {
                    is AppGraph.MixState.Done -> CellImage(state.path, Modifier.fillMaxSize())
                    is AppGraph.MixState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.message, color = Tokens.warn, fontSize = 14.sp)
                        Chip("다시 시도", selected = false, onClick = onRetry)
                    }
                    else -> Text("✨ AI가 섞는 중… (약 15초)", color = Tokens.text, fontSize = 15.sp)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhoneAlbum.SLOTS.forEach { role -> Thumb(b.photos[role], PhoneAlbum.label(role), Modifier.weight(1f)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${no}컷 · " + (recipe?.let { (if (it.ai) "✨ " else "") + it.label } ?: originText(b)),
                color = Tokens.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f),
            )
            Chip("빼기", selected = false, onClick = onClear)
        }
    }
}

@Composable
private fun CellImage(path: String, modifier: Modifier) {
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        img = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }
    }
    img?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
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
