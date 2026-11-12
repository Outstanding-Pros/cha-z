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
    // 편집 탭에서 프레임을 먼저 골라 만든 세션은 그 프레임으로 고정 (프레임마다 따로 만든 칸 구성이라)
    val locked = r.session.trigger == TriggerSource.ALBUM
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
        if (layout != Layout.CUT_FRAME) cuts.filter { it.index == single }
        else cuts.filter { it.index in multi }.sortedBy { it.index }
    val scope = rememberCoroutineScope()
    // ✨ AI 오픈카는 얼굴이 나온 사진을 외부 AI 로 보낸다 → 처음 한 번 동의 (기획서 '얼굴 합성은 동의한 경우에만')
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var askConsent by remember { mutableStateOf(false) }
    val phone by graph.phone.state.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    var sendAfterPair by remember { mutableStateOf(false) }

    // ✨ AI 오픈카: 컷마다 한 번만 서버에 보낸다 (다시 누르면 받아 둔 결과를 보여 줌 — 비용 절약)
    val aiCache = remember(r) { androidx.compose.runtime.mutableStateMapOf<Int, Bitmap>() }
    var showAi by remember(r) { mutableStateOf(false) }
    var aiBusy by remember(r) { mutableStateOf(false) }
    fun aiCutIndex(): Int? = if (layout != Layout.CUT_FRAME) single else multi.minOrNull()
    val aiShown: Bitmap? = if (showAi) aiCutIndex()?.let { aiCache[it] } else null

    suspend fun runAi() {
        val cut = cuts.firstOrNull { it.index == aiCutIndex() } ?: return
        aiCache[cut.index]?.let { showAi = true; return }
        aiBusy = true
        message = null
        try {
            val (interior, rear) = withContext(Dispatchers.IO) {
                fun read(role: CameraRole) = cut.frames.firstOrNull { it.role == role }?.path?.let { BitmapFactory.decodeFile(it) }
                read(CameraRole.INTERIOR) to (read(CameraRole.REAR) ?: read(CameraRole.FRONT))
            }
            if (interior == null) { message = "이 컷에는 실내 사진이 없어요"; return }
            val out = graph.aiOpenTop.open(interior, rear)
            val date = composer.options(r, color, stamp, filter).stamp.dateText.takeIf { stamp }
            aiCache[cut.index] = withContext(Dispatchers.Default) { ai.chaz.media.Draw.aiFinish(out, date) }
            showAi = true
        } catch (e: ai.chaz.sync.AiOpenTopClient.Failed) {
            message = "${e.message} — 앱 합성 결과를 쓸 수 있어요"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 화면이 바뀌어(새 결과 등) 취소된 것은 오류가 아니다
        } catch (e: Exception) {
            message = "AI 편집 실패 — 앱 합성 결과를 쓸 수 있어요"
        } finally {
            aiBusy = false
        }
    }

    /** 저장·보내기 대상: AI 결과를 보고 있으면 그것, 아니면 앱 합성 원본 해상도. */
    suspend fun finalImage(opts: ai.chaz.media.RenderOptions): Pair<Bitmap, List<String>> =
        aiShown?.let { it to listOf("AI_OPENTOP") } ?: (composer.render(layout, selectedCuts(), opts, preview = false) to emptyList())

    // 지금 화면의 설정으로 저장 → 폰으로 보내기 (C4)
    suspend fun saveAndSend() {
        busy = true
        try {
            val opts = composer.options(r, color, stamp, filter)
            val (full, tags) = finalImage(opts)
            val saved = composer.save(r.session.id, layout, full, opts, tags)
            val n = graph.deliver(saved)
            message = "폰 ${n}대로 보냈어요 · 갤러리에도 저장했어요"
            graph.speech.speak(graph.phrases.sentToPhone())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 화면이 바뀌어(새 결과 등) 취소된 것은 오류가 아니다
        } catch (e: Exception) {
            message = "보내지 못했어요: ${e.message}"
        } finally {
            busy = false
        }
    }
    // QR 로 연결되면 기다리던 전송을 이어서
    LaunchedEffect(phone.paired, sendAfterPair) {
        // 연결되면 기다리던 전송만 이어 하고, QR 카드는 'QR 닫기' 전까지 그대로 둔다
        if (phone.paired && sendAfterPair) { sendAfterPair = false; saveAndSend() }
    }

    LaunchedEffect(r, layout, single, multi, color, stamp, filter) {
        showAi = false // 설정을 바꾸면 앱 합성 미리보기로 (AI 결과는 받아 둔 채)
        busy = true
        message = null
        try {
            val opts = composer.options(r, color, stamp, filter)
            preview = composer.render(layout, selectedCuts(), opts, preview = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 화면이 바뀌어(새 결과 등) 취소된 것은 오류가 아니다
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
                (aiShown ?: preview)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit) }
                if (aiBusy) {
                    Text("✨ AI가 지붕을 여는 중… (약 15초)", color = Tokens.text, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                        .background(Tokens.bg.copy(alpha = 0.7f), Tokens.controlShape).padding(horizontal = 14.dp, vertical = 8.dp))
                } else if (busy) {
                    Text("합성 중…", color = Tokens.text, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                        .background(Tokens.bg.copy(alpha = 0.7f), Tokens.controlShape).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            // 오른쪽 패널
            Card(Modifier.width(420.dp).fillMaxHeight(), title = "셀렉·편집") {
                ai.chaz.ui.SectionLabel("레이아웃")
                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Layout.WINDOW_SWAP, Layout.CUT_FRAME, Layout.ROUTE_OVERLAY).filter { !locked || it == layout }.forEach { l ->
                        val ok = composer.available(l, cuts)
                        // 패널 폭(420dp)에 세 칩이 한 줄로 들어가게 짧은 이름
                        val short = when (l) { Layout.WINDOW_SWAP -> "오픈카"; Layout.CUT_FRAME -> "네컷"; Layout.ROUTE_OVERLAY -> "경로"; else -> l.label }
                        Chip("${l.id} $short", selected = layout == l, enabled = ok) { layout = l }
                    }
                }
                if (composer.available(Layout.WINDOW_SWAP) && !composer.available(Layout.WINDOW_SWAP, cuts)) {
                    Text("폰 카메라로 찍은 컷은 L1을 쓸 수 없어요. 오픈카는 아래 ✨ AI 오픈카로 만들 수 있어요.", color = Tokens.warn, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(
                    if (locked) "편집 탭에서 고른 프레임이에요. 다른 프레임은 편집 탭에서 다시 골라 주세요."
                    else if (layout == Layout.WINDOW_SWAP) "지붕을 열어요 — 좌석과 사람만 남기고 위쪽을 같은 순간의 바깥 풍경으로 바꿔요. 아래에서 컷 1장을 고르세요."
                    else if (layout == Layout.ROUTE_OVERLAY) "전방 풍경 위에 오늘 달린 길과 다음 목적지를 얹어요. 인물은 동그란 사진으로. 아래에서 컷 1장을 고르세요."
                    else "풍경을 배경으로 인물을 오려 2~4칸에 담아요. 아래에서 컷을 2~4장 고르세요.",
                    color = Tokens.text2, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Box(Modifier.height(20.dp))
                ai.chaz.ui.SectionLabel("프레임")
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameColor.entries.forEach { c -> Chip(c.label, selected = color == c) { color = c } }
                }
                Box(Modifier.height(8.dp))
                ai.chaz.ui.SectionLabel("필터")
                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ai.chaz.media.ColorPreset.entries.forEach { f -> Chip(f.label, selected = filter == f) { filter = f } }
                }
                Box(Modifier.height(8.dp))
                ai.chaz.ui.SectionLabel("스탬프 (장소·날짜·주행 정보)")
                Chip(if (stamp) "켜짐" else "꺼짐", selected = stamp) { stamp = !stamp }
                Box(Modifier.height(20.dp))
                ai.chaz.ui.SectionLabel("AI 편집")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        when {
                            aiBusy -> "AI 오픈카 만드는 중…"
                            showAi -> "앱 합성으로 보기"
                            aiCutIndex()?.let { it in aiCache } == true -> "AI 오픈카 보기"
                            else -> "AI 오픈카"
                        },
                        selected = showAi,
                        color = Tokens.ai,
                        icon = ai.chaz.ui.ChazIcons.sparkle,
                    ) {
                        if (aiBusy || busy) return@Chip
                        when {
                            showAi -> showAi = false
                            !ai.chaz.sync.AiConsent.given(ctx) -> askConsent = true
                            else -> scope.launch { runAi() }
                        }
                    }
                }
                Text(
                    "고른 컷 1장을 AI가 진짜 오픈카 사진처럼 다시 그려요 (약 15초, 'AI 편집' 표기). 사진이 서버를 거쳐 AI 서비스로 전송돼요.",
                    color = Tokens.text2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Box(Modifier.weight(1f))
                message?.let { Text(it, color = Tokens.warn, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                ai.chaz.ui.PrimaryButton("저장", Modifier.fillMaxWidth(), icon = ai.chaz.ui.ChazIcons.save) {
                        if (busy || aiBusy) return@PrimaryButton
                        scope.launch {
                            busy = true
                            try {
                                val opts = composer.options(r, color, stamp, filter)
                                val (full, tags) = finalImage(opts)
                                composer.save(r.session.id, layout, full, opts, tags)
                                message = (if (tags.isNotEmpty()) "AI 오픈카를 " else "") + "갤러리(Pictures/Cha-z)에 저장했어요"
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e // 화면이 바뀌어(새 결과 등) 취소된 것은 오류가 아니다
                            } catch (e: Exception) {
                                message = "저장 실패: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                Chip("폰으로 보내기", selected = false, icon = ai.chaz.ui.ChazIcons.send, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (busy || aiBusy) return@Chip
                    if (phone.paired) scope.launch { saveAndSend() }
                    else { pairing = true; sendAfterPair = true; graph.phone.startPairing() }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("다시 찍기", selected = false, modifier = Modifier.weight(1f)) { graph.capture.start(TriggerSource.SHUTTER_BUTTON, graph.capture.lastConfig) }
                    Chip("닫기", selected = false, modifier = Modifier.weight(1f)) { graph.capture.stop() }
                }
            }
        }
        if (pairing) {
            ai.chaz.ui.PairingCard(graph.phone, Modifier.fillMaxWidth())
        }
        // 컷 필름스트립
        LazyRow(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cuts, key = { it.index }) { cut ->
                val selected = if (layout != Layout.CUT_FRAME) cut.index == single else cut.index in multi
                CutThumb(cut, selected) {
                    if (layout != Layout.CUT_FRAME) single = cut.index
                    else multi = when {
                        cut.index in multi && multi.size > 1 -> multi - cut.index
                        cut.index !in multi && multi.size < 4 -> multi + cut.index
                        else -> multi
                    }
                }
            }
        }
    }
    if (askConsent) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askConsent = false },
            containerColor = Tokens.surface,
            title = { Text("AI 오픈카 사용 동의", color = Tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "고른 컷 1장(얼굴 포함)이 차~즈 서버를 거쳐 AI 서비스(Google Gemini)로 전송되고, 오픈카 사진으로 다시 그려져요.\n\n" +
                        "• 서버는 보낸 사진을 저장하지 않아요\n" +
                        "• 결과물에는 'AI 편집' 표시가 남아요\n" +
                        "• 사진에 나온 모든 사람이 동의한 경우에만 사용해 주세요\n\n" +
                        "동의는 설정 → AI 편집에서 언제든 철회할 수 있어요.",
                    color = Tokens.text2, fontSize = 18.sp, lineHeight = 27.sp,
                )
            },
            confirmButton = {
                ai.chaz.ui.PrimaryButton("동의하고 만들기", icon = ai.chaz.ui.ChazIcons.sparkle, color = Tokens.ai) {
                    ai.chaz.sync.AiConsent.set(ctx, true)
                    askConsent = false
                    scope.launch { runAi() }
                }
            },
            dismissButton = { Chip("동의 안 함", selected = false) { askConsent = false } },
        )
    }
}

@Composable
private fun CutThumb(cut: Cut, selected: Boolean, onClick: () -> Unit) {
    val path = cut.composed ?: cut.frames.firstOrNull { it.role == CameraRole.INTERIOR }?.path ?: cut.frames.firstOrNull()?.path
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
