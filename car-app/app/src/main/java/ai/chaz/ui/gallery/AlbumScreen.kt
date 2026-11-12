package ai.chaz.ui.gallery

import ai.chaz.AppGraph
import ai.chaz.sync.AiConsent
import ai.chaz.sync.AlbumTool
import ai.chaz.sync.AlbumTools
import ai.chaz.sync.AlbumVideo
import ai.chaz.sync.MadePhoto
import ai.chaz.sync.PhoneAlbum
import ai.chaz.sync.PhoneBundle
import ai.chaz.ui.Card
import ai.chaz.ui.Chip
import ai.chaz.ui.PrimaryButton
import ai.chaz.ui.SectionLabel
import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 앨범 탭 — 찍은 묶음(실내·전방·후방)·동영상·만든 사진을 모아 보고, 묶음에 '만들기' 기능(AlbumTools)을 쓴다.
 * 만든 사진은 앨범의 새 아이템이 되고, 편집 탭에서 프레임 칸에 넣는다. 지우기도 여기서.
 */
@Composable
fun AlbumScreen(graph: AppGraph) {
    val bundles by graph.phoneAlbum.bundles.collectAsState()
    val videos by graph.phoneAlbum.videos.collectAsState()
    val made by graph.phoneAlbum.made.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sel = selected?.let { id -> bundles.firstOrNull { it.id == id } ?: made.firstOrNull { it.id == id } ?: videos.firstOrNull { it.id == id } }
        ?: made.firstOrNull() ?: bundles.firstOrNull() ?: videos.firstOrNull()
    val selId = when (sel) { is PhoneBundle -> sel.id; is MadePhoto -> sel.id; is AlbumVideo -> sel.id; else -> null }
    // 처음 보여준 아이템을 고정 (새 사진이 생겨도 보던 묶음에 머문다)
    androidx.compose.runtime.SideEffect { if (selected != selId) selected = selId }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(Modifier.width(560.dp).fillMaxHeight(), title = "앨범") {
            if (bundles.isEmpty() && videos.isEmpty() && made.isEmpty()) {
                Text(
                    "아직 찍은 사진이 없어요.\n'차~즈'(단발·동영상)로 찍거나, 폰 카메라에서 실내 → 전방 → 후방 3장을 찍어 보내면 여기에 쌓여요.",
                    color = Tokens.text2, fontSize = Tokens.bodySize,
                )
                return@Card
            }
            val rows: List<Any> = buildList {
                if (made.isNotEmpty()) { add("만든 사진 ${made.size}장"); addAll(made) }
                add("찍은 묶음 ${bundles.size}개")
                val placed = mutableSetOf<String>()
                bundles.forEach { b ->
                    videos.firstOrNull { it.group == b.group && it.id !in placed }?.let { add(it); placed += it.id }
                    add(b)
                }
                videos.filter { it.id !in placed }.forEach { add(it) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { r -> when (r) { is PhoneBundle -> r.id; is MadePhoto -> r.id; is AlbumVideo -> r.id; else -> "h:$r" } }) { r ->
                    when (r) {
                        is String -> SectionLabel(r, Modifier.padding(top = 6.dp))
                        is MadePhoto -> ListRow(r.id == selId, onClick = { selected = r.id }) {
                            Thumb(r.path, "", Modifier.width(150.dp), ratio = 16f / 9f)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text((if (r.ai) "✨ " else "") + r.label, color = Tokens.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(whenText(r.at), color = Tokens.text3, fontSize = 14.sp)
                            }
                        }
                        is PhoneBundle -> ListRow(r.id == selId, onClick = { selected = r.id }) {
                            BundleThumbs(r, Modifier.width(300.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                AlbumTag(originText(r))
                                Text(whenText(r.receivedAt), color = Tokens.text3, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        is AlbumVideo -> ListRow(r.id == selId, onClick = { selected = r.id }) {
                            Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PhoneAlbum.SLOTS.forEach { role -> ClipThumb(r.clips[role], PhoneAlbum.label(role), Modifier.weight(1f)) }
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                AlbumTag("▶ 영상 ${r.durationMs / 1000}초")
                                Text(whenText(r.at), color = Tokens.text3, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (sel) {
                is PhoneBundle -> BundleDetail(graph, sel, made, onShow = { selected = it }, onDelete = { scope.launch { graph.phoneAlbum.delete(sel.id) }; selected = null })
                is MadePhoto -> MadeDetail(sel, bundles.firstOrNull { it.id == sel.sourceId }, onSource = { selected = it },
                    onDelete = { scope.launch { graph.phoneAlbum.delete(sel.id) }; selected = null })
                is AlbumVideo -> VideoDetail(sel, onDelete = { scope.launch { graph.phoneAlbum.delete(sel.id) }; selected = null })
                else -> Unit
            }
        }
    }
}

@Composable
private fun ListRow(on: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Tokens.controlShape)
            .background(if (on) Tokens.accentSoft else Tokens.surface2)
            .border(if (on) 3.dp else 1.dp, if (on) Tokens.accent else Tokens.line, Tokens.controlShape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** 묶음: 세 장 크게 + '만들기' 기능 목록. */
@Composable
private fun BundleDetail(graph: AppGraph, b: PhoneBundle, made: List<MadePhoto>, onShow: (String) -> Unit, onDelete: () -> Unit) {
    val jobs by graph.toolJobs.collectAsState()
    val ctx = LocalContext.current
    var askConsent by remember { mutableStateOf<Pair<AlbumTool, Boolean>?>(null) }
    fun run(t: AlbumTool, remake: Boolean) {
        if (t.ai && !AiConsent.given(ctx)) askConsent = t to remake else graph.runTool(b, t, remake)
    }
    Card(Modifier.fillMaxSize(), title = "${originText(b)} 묶음 · ${whenText(b.receivedAt)}", trailing = { Chip("묶음 지우기", selected = false, onClick = onDelete) }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneAlbum.SLOTS.forEach { role -> Thumb(b.photos[role], PhoneAlbum.label(role), Modifier.weight(1f), sample = 4) }
            }
            SectionLabel("이 묶음으로 만들기 — 만든 사진은 앨범에 새로 들어가고, 편집 탭에서 프레임에 넣을 수 있어요", Modifier.padding(top = 20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                AlbumTools.all.forEach { t ->
                    val done = made.firstOrNull { it.id == graph.phoneAlbum.madeId(b, t.id) }
                    val job = jobs[graph.toolKey(b, t)]
                    Row(
                        Modifier.fillMaxWidth().clip(Tokens.controlShape).background(Tokens.surface2)
                            .border(1.dp, Tokens.line, Tokens.controlShape).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (done != null) Thumb(done.path, "", Modifier.width(160.dp), ratio = 16f / 9f)
                        Column(Modifier.weight(1f)) {
                            Text((if (t.ai) "✨ " else "") + t.label, color = if (t.ai) Tokens.ai else Tokens.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (t.accepts(b)) t.desc else "이 묶음에는 ${t.needs.filter { it !in b.photos }.joinToString { PhoneAlbum.label(it) }} 사진이 없어요",
                                color = Tokens.text2, fontSize = 15.sp, lineHeight = 21.sp,
                            )
                            if (job is AppGraph.ToolState.Failed) Text(job.message, color = Tokens.warn, fontSize = 15.sp)
                        }
                        when {
                            job is AppGraph.ToolState.Working -> Text(if (t.ai) "✨ 만드는 중…" else "만드는 중…", color = Tokens.text, fontSize = 17.sp)
                            done != null -> {
                                Chip("보기", selected = false) { onShow(done.id) }
                                Chip("다시 만들기", selected = false, enabled = t.accepts(b)) { run(t, true) }
                            }
                            job is AppGraph.ToolState.Failed -> PrimaryButton("다시 시도", enabled = t.accepts(b), color = if (t.ai) Tokens.ai else Tokens.accent) { run(t, true) }
                            else -> PrimaryButton("만들기", enabled = t.accepts(b), color = if (t.ai) Tokens.ai else Tokens.accent) { run(t, false) }
                        }
                    }
                }
            }
            Text(
                "✨ 기능은 실내 사진(얼굴 포함)과 바깥 풍경이 차~즈 서버를 거쳐 AI(Google Gemini)로 보내져요. 서버는 사진을 저장하지 않고, 결과물에는 'AI 생성 포함'이 표시돼요.",
                color = Tokens.text3, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
    askConsent?.let { (t, remake) ->
        AlertDialog(
            onDismissRequest = { askConsent = null },
            containerColor = Tokens.surface,
            title = { Text("AI 만들기 사용 동의", color = Tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "이 묶음의 실내 사진(얼굴 포함)과 바깥 풍경이 차~즈 서버를 거쳐 AI 서비스(Google Gemini)로 전송되고, 한 장으로 만들어져요.\n\n" +
                        "• 서버는 보낸 사진을 저장하지 않아요\n" +
                        "• 결과물에는 'AI 생성 포함' 표시가 남아요\n" +
                        "• 사진에 나온 모든 사람이 동의한 경우에만 사용해 주세요",
                    color = Tokens.text2, fontSize = 18.sp, lineHeight = 27.sp,
                )
            },
            confirmButton = {
                PrimaryButton("동의하고 ${t.label} 만들기", color = Tokens.ai) {
                    AiConsent.set(ctx, true)
                    askConsent = null
                    graph.runTool(b, t, remake)
                }
            },
            dismissButton = { Chip("동의 안 함", selected = false) { askConsent = null } },
        )
    }
}

/** 만든 사진 한 장. */
@Composable
private fun MadeDetail(m: MadePhoto, source: PhoneBundle?, onSource: (String) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxSize(), title = (if (m.ai) "✨ " else "") + m.label, trailing = { Chip("사진 지우기", selected = false, onClick = onDelete) }) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(Tokens.controlShape).background(Tokens.bg), contentAlignment = Alignment.Center) {
            PhotoImage(m.path, Modifier.fillMaxSize(), crop = false, sample = 2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
            if (m.ai) AlbumTag("AI 생성 포함", Tokens.ai)
            Text(whenText(m.at), color = Tokens.text2, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            source?.let { s -> Chip("원본 묶음 보기", selected = false) { onSource(s.id) } }
        }
        Text("편집 탭에서 프레임을 고르면 이 사진을 칸에 넣을 수 있어요.", color = Tokens.text3, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/** 동영상: 실내·전방·후방 스냅샷 재생. */
@Composable
private fun VideoDetail(v: AlbumVideo, onDelete: () -> Unit) {
    Card(Modifier.fillMaxSize(), title = "▶ 영상 ${v.durationMs / 1000}초 · ${whenText(v.at)}", trailing = { Chip("영상 지우기", selected = false, onClick = onDelete) }) {
        ClipThumb(v.clips[ai.chaz.capture.CameraRole.INTERIOR] ?: v.clips.values.firstOrNull(), "실내", Modifier.height(400.dp), ratio = 16f / 9f, sample = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
            listOf(ai.chaz.capture.CameraRole.FRONT, ai.chaz.capture.CameraRole.REAR).forEach { role ->
                ClipThumb(v.clips[role], PhoneAlbum.label(role), Modifier.weight(1f), ratio = 16f / 9f, sample = 4)
            }
        }
    }
}
