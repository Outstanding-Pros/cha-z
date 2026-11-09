package ai.chaz

import ai.chaz.ui.ChazRoot
import ai.chaz.ui.Tab
import ai.chaz.ui.spike.SpikeRunner
import ai.chaz.ui.theme.ChazTheme
import android.Manifest
import android.content.Intent
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
        // 개발용 발화 주입: --es say "차즈 1분 동안 10번 찍어줘" (호출어 경로와 같게 처리)
        injectSay(intent)
        injectDrive(intent)
        injectLocation(intent)
        setContent {
            ChazTheme { ChazRoot(graph, initial) }
        }
        requestRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        injectSay(intent)
        injectDrive(intent)
        injectLocation(intent)
    }

    /** 개발용: --es drive on|off|auto (Virtual Console 없이 주행/정차 전환 — 개발용 오버라이드) */
    private fun injectDrive(intent: Intent?) {
        val v = intent?.getStringExtra("drive") ?: return
        val hub = (application as ChazApp).graph.driving
        hub.setOverride(when (v) {
            "on" -> ai.chaz.platform.driving.DrivingGateHub.Override.FORCE_DRIVING
            "off" -> ai.chaz.platform.driving.DrivingGateHub.Override.FORCE_PARKED
            else -> ai.chaz.platform.driving.DrivingGateHub.Override.AUTO
        })
    }

    /** 개발용: --es loc 33.46,126.31 | off  /  --es demo start|stop */
    private fun injectLocation(intent: Intent?) {
        val graph = (application as ChazApp).graph
        intent?.getStringExtra("loc")?.let { v ->
            graph.location.setManual(
                v.split(",").takeIf { it.size == 2 }?.let { (a, b) -> ai.chaz.platform.navi.GeoPoint(a.trim().toDouble(), b.trim().toDouble()) },
            )
        }
        when (intent?.getStringExtra("demo")) {
            "start" -> { graph.spotGuide.resetTrip(); graph.location.startDemo() }
            "stop" -> graph.location.stopDemo()
        }
    }

    private fun injectSay(intent: Intent?) {
        // adb shell 은 한글 인자가 깨지므로 base64(UTF-8) 도 받는다: --es say64 <base64>
        val text = intent?.getStringExtra("say")
            ?: intent?.getStringExtra("say64")?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            ?: return
        android.util.Log.i("ChazVoice", "주입된 발화 ${text.length}자")
        val graph = (application as ChazApp).graph
        graph.scope.launch { graph.voice.handleText(text, requireWake = true) }
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
