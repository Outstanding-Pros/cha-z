package ai.chaz

import ai.chaz.ui.ChazRoot
import ai.chaz.ui.Tab
import ai.chaz.ui.spike.SpikeRunner
import ai.chaz.ui.theme.ChazTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // 권한이 바뀌면 카메라·마이크·위치 후보 판정이 달라지므로 다시 판정
        (application as ChazApp).graph.reprobe()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as ChazApp).graph
        // 개발용: adb shell am start -n ai.chaz/.MainActivity --es tab SPIKE --es spike run
        val initial = intent.getStringExtra("tab")?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.HOME
        when (intent.getStringExtra("spike")) {
            "run" -> graph.scope.launch { SpikeRunner(applicationContext).runAll() }
            "tts" -> graph.scope.launch { SpikeRunner(applicationContext).pleosTts(ai.pleos.playground.tts.constant.Mode.ON_DEVICE) }
            "stt" -> graph.scope.launch { SpikeRunner(applicationContext).pleosStt(ai.pleos.playground.stt.constant.Mode.ON_DEVICE) }
        }
        setContent {
            ChazTheme { ChazRoot(graph, initial) }
        }
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionRequest.launch(needed.toTypedArray())
    }
}
