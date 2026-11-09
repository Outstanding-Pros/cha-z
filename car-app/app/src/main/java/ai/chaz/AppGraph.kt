package ai.chaz

import ai.chaz.capture.CameraRole
import ai.chaz.capture.CameraSource
import ai.chaz.capture.CameraXSource
import ai.chaz.media.CutFrameRenderer
import ai.chaz.media.FakePersonSegmenter
import ai.chaz.media.Layout
import ai.chaz.media.MediaPipePersonSegmenter
import ai.chaz.media.PersonSegmenter
import ai.chaz.media.ResultComposer
import ai.chaz.media.WindowMask
import ai.chaz.media.WindowSwapRenderer
import ai.chaz.platform.vehicle.FakeVehicleStateProvider
import ai.chaz.platform.vehicle.PleosVehicleStateProvider
import ai.chaz.platform.vehicle.VehicleStateProvider
import ai.chaz.capture.CaptureController
import ai.chaz.capture.CaptureSessionEngine
import ai.chaz.capture.FileSessionStore
import ai.chaz.data.CaptureConfig
import ai.chaz.data.Session
import ai.chaz.state.AppEvent
import ai.chaz.state.AppStateMachine
import ai.chaz.data.TriggerSource
import ai.chaz.platform.Feature
import ai.chaz.state.AppState
import ai.chaz.sync.IntentClient
import ai.chaz.voice.AudioFocus
import ai.chaz.voice.CommandSink
import ai.chaz.voice.PleosSpeechInput
import ai.chaz.voice.ServerSpeechInput
import ai.chaz.voice.SpeechInputRouter
import ai.chaz.voice.VoiceCommandController
import ai.chaz.voice.PleosSpeechOutput
import ai.chaz.voice.ResourceTtsPhrases
import ai.chaz.voice.ServerSpeechOutput
import ai.chaz.voice.SoundPoolCuePlayer
import ai.chaz.voice.SpeechOutputRouter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ai.chaz.capture.CameraSourceFactory
import ai.chaz.platform.CapabilityProbe
import ai.chaz.platform.ProbeChains
import ai.chaz.platform.SdkAvailability
import ai.chaz.platform.driving.CarUxDrivingGate
import ai.chaz.platform.driving.DebugDrivingGate
import ai.chaz.platform.driving.DrivingGate
import ai.chaz.platform.driving.DrivingGateHub
import ai.chaz.platform.driving.VehicleGearDrivingGate
import ai.chaz.platform.vehicle.PleosVehicleHub
import ai.chaz.sync.ChazServer
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 수동 DI. 어댑터 구현체는 SdkAvailability 의 선택 결과로만 만든다. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val server = ChazServer(BuildConfig.CHAZ_SERVER_URL)
    val vehicleHub = PleosVehicleHub(appContext)
    val availability = SdkAvailability()
    val probe = CapabilityProbe(availability, ProbeChains.build(appContext, vehicleHub, server))
    val driving = DrivingGateHub(scope)
    val debugGate = DebugDrivingGate()

    private val cameraFactory = CameraSourceFactory(appContext, availability)
    private val _cameras = MutableStateFlow<List<CameraSource>>(emptyList())
    /** 현재 선택된 카메라 소스 (실내·전방·후방). 판정이 끝날 때마다 다시 만든다. */
    val cameras: StateFlow<List<CameraSource>> = _cameras.asStateFlow()

    // 음성 안내: SdkAvailability 선택(Pleos TTS → 비즈라우터) + 카운트다운 클립
    private val audioFocus = AudioFocus(appContext)
    private val serverSpeech = ServerSpeechOutput(appContext, BuildConfig.CHAZ_SERVER_URL, audioFocus)
    val speech = SpeechOutputRouter(
        availability, probe,
        impls = mapOf(
            ProbeChains.Ids.TTS_PLEOS to PleosSpeechOutput(appContext, audioFocus),
            ProbeChains.Ids.TTS_BIZROUTER to serverSpeech,
        ),
        fallback = serverSpeech,
    )
    private val phrases = ResourceTtsPhrases(appContext)
    private val cues by lazy { SoundPoolCuePlayer(appContext) }

    val capture by lazy {
        CaptureController(
            scope = scope,
            machine = AppStateMachine { trigger, config ->
                Session(newSessionId(), System.currentTimeMillis(), config, trigger)
            },
            engine = CaptureSessionEngine(speech, cues, FileSessionStore(appContext), phrases),
            sources = { _cameras.value },
            isDriving = { driving.restricted.value },
        )
    }

    // 음성 입력: Pleos STT(온디바이스) → 비즈라우터 전사. 호출어는 Pleos 연속 인식으로만 (무료·로컬)
    private val pleosStt = PleosSpeechInput(appContext)
    private val serverStt = ServerSpeechInput(appContext, BuildConfig.CHAZ_SERVER_URL)
    val speechInput = SpeechInputRouter(
        availability, probe,
        impls = mapOf(ProbeChains.Ids.STT_PLEOS to pleosStt, ProbeChains.Ids.STT_BIZROUTER to serverStt),
        fallback = serverStt,
    )
    private val intentClient = IntentClient(BuildConfig.CHAZ_SERVER_URL)

    val voice by lazy {
        VoiceCommandController(
            scope = scope,
            sink = object : CommandSink {
                override val capturing get() = capture.model.value.state is AppState.Capturing
                override val lastConfig get() = capture.lastConfig
                override val driving get() = this@AppGraph.driving.restricted.value
                override fun startCapture(trigger: TriggerSource, config: CaptureConfig) { capture.start(trigger, config) }
                override fun stopCapture() = capture.stop()
            },
            speech = speech,
            input = speechInput,
            wakeInput = { if (availability.selectedId(Feature.WAKE_WORD) == ProbeChains.Ids.WAKE_PLEOS) pleosStt else null },
            cues = cues,
            phrases = phrases,
            llm = { intentClient.classify(it) },
        )
    }

    // 편집·합성 (T08): 인물 분리(MediaPipe) + L1·L2 렌더러
    // 판정이 끝난 뒤의 선택을 매번 읽는다 (lazy 로 굳히면 판정 전 값에 묶일 수 있음)
    private val mediapipe by lazy { MediaPipePersonSegmenter(appContext) }
    private val noSegmenter = FakePersonSegmenter()
    private val segmenter: PersonSegmenter
        get() = if (availability.selectedId(Feature.PERSON_SEGMENTER) == ProbeChains.Ids.SEGMENTER_MEDIAPIPE) mediapipe else noSegmenter
    private val pleosVehicleState by lazy { PleosVehicleStateProvider(vehicleHub) }
    private val vehicleState: VehicleStateProvider
        get() = if (availability.selectedId(Feature.VEHICLE_STATE) == ProbeChains.Ids.VEHICLE_PLEOS) pleosVehicleState else FakeVehicleStateProvider()
    val composer by lazy {
        val seg = object : PersonSegmenter {
            override val name get() = segmenter.name
            override suspend fun segment(image: android.graphics.Bitmap) = segmenter.segment(image)
        }
        ResultComposer(
            appContext,
            renderers = mapOf(Layout.WINDOW_SWAP to WindowSwapRenderer(seg), Layout.CUT_FRAME to CutFrameRenderer(seg)),
            vehicle = { vehicleState },
            windowMask = { currentWindowMask() },
        )
    }

    /** 실내 카메라 위치가 고정이면 정적 창문 마스크 (assets/masks/{cameraId}_windows.json). */
    private fun currentWindowMask(): WindowMask? {
        val interior = _cameras.value.firstOrNull { it.role == CameraRole.INTERIOR } as? CameraXSource ?: return null
        return runCatching {
            appContext.assets.open("masks/${interior.cameraId}_windows.json").bufferedReader().use { WindowMask.parse(it.readText()) }
        }.getOrNull()
    }

    @Volatile private var foreground = false

    /** 앱이 화면에 보일 때만 호출어를 듣는다 (ChazApp 이 ProcessLifecycle 로 호출). */
    fun onForeground(visible: Boolean) {
        foreground = visible
        updateWakeLoop()
    }

    private fun updateWakeLoop() {
        val micOk = appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (foreground && micOk) voice.startWakeLoop() else voice.stopWakeLoop()
    }

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    fun start() {
        reprobe()
        scope.launch {
            driving.restricted.collect { capture.dispatch(AppEvent.DrivingChanged(it)) }
        }
        scope.launch {
            // 마지막 세션 종료 후 3초는 호출어 무시 (SPEC)
            var wasCapturing = false
            capture.model.collect { m ->
                val c = m.state is AppState.Capturing
                if (wasCapturing && !c) voice.ignoreWakeUntil = System.currentTimeMillis() + 3_000
                wasCapturing = c
            }
        }
        scope.launch {
            driving.status.collect { android.util.Log.i("ChazDriving", "restricted=${it.restricted} source=${it.source} detail=${it.detail} override=${it.override}") }
        }
    }

    fun reprobe() {
        if (_probing.value) return
        _probing.value = true
        scope.launch {
            try {
                // 주행 규제는 화면 잠금에 바로 쓰이므로 먼저 판정해 붙인다
                withContext(Dispatchers.Default) { probe.probe(Feature.DRIVING_GATE) }
                attachDrivingGate()
                withContext(Dispatchers.Default) { probe.probeAll() }
                attachDrivingGate()
                rebuildCameras()
                // 기본 안내 문구를 미리 준비 (Pleos 는 모델 로딩, 비즈라우터는 사전 합성)
                updateWakeLoop()
                speech.prepare(listOf(phrases.captureStart(CaptureConfig()), phrases.doneParked(), phrases.doneDriving()))
            } finally {
                _probing.value = false
            }
        }
    }

    private fun rebuildCameras() {
        val old = _cameras.value
        val fresh = runCatching { cameraFactory.create() }.getOrElse {
            android.util.Log.w("ChazCamera", "카메라 소스 생성 실패: ${it.message}"); emptyList()
        }
        // 같은 구성이면 그대로 둔다 (미리보기가 깜빡이지 않게)
        if (old.map { it.role to it.label } == fresh.map { it.role to it.label }) return
        old.forEach { it.stop() }
        _cameras.value = fresh
        android.util.Log.i("ChazCamera", "소스: " + fresh.joinToString { "${it.role}=${it.label}" })
    }

    private fun newSessionId(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + (1000..9999).random()

    private fun attachDrivingGate() {
        val gate: DrivingGate = when (availability.selectedId(Feature.DRIVING_GATE)) {
            ProbeChains.Ids.DRIVING_CAR_UX -> (driving.current as? CarUxDrivingGate) ?: CarUxDrivingGate(appContext)
            ProbeChains.Ids.DRIVING_PLEOS_GEAR -> (driving.current as? VehicleGearDrivingGate) ?: VehicleGearDrivingGate(vehicleHub)
            else -> debugGate
        }
        driving.attach(gate)
    }
}
