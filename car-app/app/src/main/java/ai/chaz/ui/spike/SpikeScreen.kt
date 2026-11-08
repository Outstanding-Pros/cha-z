package ai.chaz.ui.spike

import ai.chaz.AppGraph
import ai.chaz.ui.Chip
import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.Mode as TtsMode

/** 개발용 T04 스파이크 화면. */
@Composable
fun SpikeScreen(@Suppress("UNUSED_PARAMETER") graph: AppGraph) {
    val context = LocalContext.current
    val runner = remember { SpikeRunner(context.applicationContext) }
    val lines by runner.lines.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } finally { busy = false } }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("T04 스파이크 — 개발용", color = Tokens.text, fontSize = 22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (busy) "실행 중…" else "전체 실행", selected = busy) { run { runner.runAll() } }
            Chip("카메라", false) { run { runner.cameras() } }
            Chip("마이크", false) { run { runner.mic() } }
            Chip("Pleos TTS", false) { run { runner.pleosTts(TtsMode.HYBRID) } }
            Chip("Pleos STT", false) { run { runner.pleosStt(SttMode.HYBRID) } }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(lines) { _, l ->
                Text(l, color = if (l.startsWith("==")) Tokens.accent else Tokens.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
