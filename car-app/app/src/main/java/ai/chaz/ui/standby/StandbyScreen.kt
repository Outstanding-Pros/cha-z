package ai.chaz.ui.standby

import ai.chaz.AppGraph
import ai.chaz.platform.Feature
import ai.chaz.ui.Card
import ai.chaz.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** C1 촬영 대기 (정차 중). 뷰파인더·카메라 소스는 T05에서 연결. */
@Composable
fun StandbyScreen(graph: AppGraph) {
    val selections by graph.availability.state.collectAsState()
    val driving by graph.driving.status.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
        val landscape = maxWidth > maxHeight
        val viewfinder = @Composable { m: Modifier ->
            Box(
                m
                    .background(Tokens.surface, Tokens.cardShape)
                    .border(1.dp, Tokens.line, Tokens.cardShape),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("뷰파인더", color = Tokens.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("카메라 소스 연결 예정 (T05)", color = Tokens.text2, fontSize = 16.sp)
                }
            }
        }
        val status = @Composable { m: Modifier ->
            Card(m, title = "상태") {
                Text(if (driving.restricted) "주행 중" else "정차 중", color = Tokens.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${driving.source} · ${driving.detail}", color = Tokens.text2, fontSize = 14.sp)
                Box(Modifier.padding(top = 16.dp))
                listOf(Feature.CAMERA_EXTERIOR, Feature.CAMERA_INTERIOR, Feature.SPEECH_OUTPUT, Feature.SPEECH_INPUT).forEach { f ->
                    val sel = selections[f]
                    Text(
                        "${f.label}: " + (sel?.selected?.label ?: if (sel == null) "판정 중" else "없음"),
                        color = Tokens.text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                viewfinder(Modifier.weight(1f).fillMaxHeight())
                status(Modifier.width(340.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                viewfinder(Modifier.fillMaxWidth().weight(1f))
                status(Modifier.fillMaxWidth())
            }
        }
    }
}
