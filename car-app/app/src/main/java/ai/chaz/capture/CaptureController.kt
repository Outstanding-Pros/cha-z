package ai.chaz.capture

import ai.chaz.data.CaptureConfig
import ai.chaz.data.CaptureRules
import ai.chaz.data.TriggerSource
import ai.chaz.state.AppEvent
import ai.chaz.state.AppModel
import ai.chaz.state.AppState
import ai.chaz.state.AppStateMachine
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 트리거(T1~T4)를 받아 상태 머신과 촬영 엔진을 잇는다. 화면은 model·progress·lastResult 만 구독한다.
 */
class CaptureController(
    private val scope: CoroutineScope,
    private val machine: AppStateMachine,
    private val engine: CaptureSessionEngine,
    private val sources: () -> List<CameraSource>,
    private val isDriving: () -> Boolean,
) {
    private val _model = MutableStateFlow(AppModel())
    val model: StateFlow<AppModel> = _model.asStateFlow()

    private val _progress = MutableStateFlow<CaptureSessionEngine.Progress?>(null)
    val progress: StateFlow<CaptureSessionEngine.Progress?> = _progress.asStateFlow()

    private val _lastResult = MutableStateFlow<CaptureSessionEngine.Result?>(null)
    val lastResult: StateFlow<CaptureSessionEngine.Result?> = _lastResult.asStateFlow()

    /** 직전 촬영 설정 (RETAKE 용). */
    var lastConfig: CaptureConfig = CaptureConfig()
        private set

    private var job: Job? = null

    fun dispatch(event: AppEvent) {
        _model.update { machine.reduce(it, event) }
        Log.i(TAG, "$event → ${_model.value.state.javaClass.simpleName}")
    }

    fun start(trigger: TriggerSource, requested: CaptureConfig = CaptureConfig()): Boolean {
        if (_model.value.state is AppState.Capturing) return false
        val cams = sources()
        if (cams.isEmpty()) { Log.w(TAG, "카메라 소스 없음 — 촬영 불가"); return false }
        val (config, adjusted) = CaptureRules.normalize(requested)
        lastConfig = config
        dispatch(AppEvent.Trigger(trigger, config))
        val session = (_model.value.state as? AppState.Capturing)?.session ?: return false
        job = scope.launch {
            val result = engine.run(
                session, cams, adjusted, isDriving,
                onProgress = { _progress.value = it },
                onShot = { dispatch(AppEvent.ShotTaken(it)) },
            )
            _lastResult.value = result
            _progress.value = null
            dispatch(if (result.stopped) AppEvent.Stop else AppEvent.SessionFinished)
        }
        return true
    }

    /** 차 촬영 없이 만들어진 세션(폰 직접 셔터)을 결과로 띄운다. 정차 중이면 편집, 주행 중이면 저장(편집 잠금). */
    fun import(result: CaptureSessionEngine.Result): Boolean {
        if (_model.value.state is AppState.Capturing) return false
        _lastResult.value = result
        dispatch(AppEvent.Imported(result.session.id))
        return true
    }

    fun stop() {
        if (_model.value.state is AppState.Capturing) engine.stop()
        else dispatch(AppEvent.Stop)
    }

    companion object { private const val TAG = "ChazState" }
}
