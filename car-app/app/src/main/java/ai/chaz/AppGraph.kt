package ai.chaz

import ai.chaz.capture.CameraRole
import ai.chaz.capture.CameraSource
import ai.chaz.capture.CameraXSource
import ai.chaz.media.CutFrameRenderer
import ai.chaz.media.FakePersonSegmenter
import ai.chaz.media.Layout
import ai.chaz.media.LivePreviewEngine
import ai.chaz.media.PreviewPrefs
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
import ai.chaz.ambience.AmbienceDirector
import ai.chaz.ambience.AmbientLightController
import ai.chaz.ambience.CaptureContext
import ai.chaz.ambience.ContextProvider
import ai.chaz.ambience.LightPreset
import ai.chaz.ambience.PleosAmbientLight
import ai.chaz.ambience.SimulatedAmbientLight
import ai.chaz.ambience.SpotGuideRunner
import ai.chaz.platform.navi.AndroidLocationSource
import ai.chaz.platform.navi.DemoRouteSource
import ai.chaz.platform.navi.LocationHub
import ai.chaz.platform.navi.NaviHelperLocationSource
import ai.chaz.spot.DemoSpotSource
import ai.chaz.spot.OverpassSpotSource
import ai.chaz.spot.SpotGuidePolicy
import ai.chaz.spot.SpotRepository
import kotlinx.coroutines.Job
import ai.chaz.voice.SendOutcome
import ai.chaz.sync.DeviceIdentity
import ai.chaz.sync.PhoneLink
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

    private val cameraFactory = CameraSourceFactory(appContext, availability, BuildConfig.CHAZ_SERVER_URL, DeviceIdentity.carId(appContext))
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
    val phrases = ResourceTtsPhrases(appContext)
    private val cues by lazy { SoundPoolCuePlayer(appContext) }

    val capture by lazy {
        CaptureController(
            scope = scope,
            machine = AppStateMachine { trigger, config ->
                Session(newSessionId(), System.currentTimeMillis(), config, trigger, spotName = spotGuide.placeForSession())
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
                override val phonePaired get() = phone.state.value.paired
                override suspend fun sendToPhone() = sendLatestToPhone()
                override suspend fun changeLight(preset: String?) = this@AppGraph.changeLight(preset)
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
        ai.chaz.media.Draw.handwriting = runCatching {
            android.graphics.Typeface.createFromAsset(appContext.assets, "fonts/NanumPenScript-Regular.ttf")
        }.getOrNull()
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

    val previewPrefs = MutableStateFlow(PreviewPrefs())

    // ---- T12 위치·스팟 가이드 / T13 앰비언트 ----
    /** 데모 경로 (미니맵에 그린다). */
    val demoRoute: List<ai.chaz.platform.navi.GeoPoint> by lazy { DemoSpotSource.route(appContext) }
    val location = LocationHub(scope, DemoRouteSource(demoRoute, scope))
    private val naviLocation by lazy {
        NaviHelperLocationSource(appContext, scope).also { src ->
            src.onRoutingCompleted = { scope.launch { location.location.value?.let { spotRepo.spotsNear(it, force = true) } } }
        }
    }
    private val androidLocation by lazy { AndroidLocationSource(appContext) }
    private val demoSpots = DemoSpotSource(appContext)
    private val overpass = OverpassSpotSource()
    val spotRepo = SpotRepository {
        listOfNotNull(overpass.takeIf { availability.selectedId(Feature.SPOTS) == ProbeChains.Ids.SPOTS_OVERPASS }, demoSpots)
    }
    private val contextProvider = ContextProvider(useNetwork = { availability.selectedId(Feature.WEATHER) == ProbeChains.Ids.WEATHER_OPEN_METEO })
    private val _context = MutableStateFlow<CaptureContext?>(null)
    val context: StateFlow<CaptureContext?> = _context.asStateFlow()
    suspend fun refreshContext(): CaptureContext =
        contextProvider.current(location.location.value, spotGuide.spots.value).also { _context.value = it }

    private val simulatedLight = SimulatedAmbientLight()
    private val pleosLight by lazy { PleosAmbientLight(vehicleHub) }
    val ambientLight: AmbientLightController
        get() = if (availability.selectedId(Feature.AMBIENT_LIGHT) == ProbeChains.Ids.LIGHT_PLEOS) pleosLight else simulatedLight
    val ambience = AmbienceDirector(light = { ambientLight }, context = { refreshContext() }, driving = { driving.restricted.value })

    val spotGuide: SpotGuideRunner by lazy {
        SpotGuideRunner(scope, location, spotRepo, SpotGuidePolicy(), sunsetMs = { refreshContext().sunsetMs }, onGuide = { onSpotGuide(it) })
    }
    private var spotEpisode: Job? = null

    /** 스팟·노을 가이드: 조명 에피소드 시작 → 안내 → 15초 '차즈' 대기 → 촬영이 없으면 조명 복원. */
    private suspend fun onSpotGuide(d: SpotGuidePolicy.Decision) {
        if (capture.model.value.state is AppState.Capturing) return
        val text = when (d) {
            is SpotGuidePolicy.Decision.SpotGuide -> phrases.spotGuide(d.spot.name)
            SpotGuidePolicy.Decision.SunsetGuide -> phrases.sunsetGuide()
            SpotGuidePolicy.Decision.None -> return
        }
        android.util.Log.i("ChazSpot", "가이드: $d")
        scope.launch { ambience.begin("spot-guide") }
        runCatching { speech.speak(text) }
        voice.spotWindowUntil = System.currentTimeMillis() + 15_000
        spotEpisode?.cancel()
        spotEpisode = scope.launch {
            kotlinx.coroutines.delay(15_000)
            if (capture.model.value.state !is AppState.Capturing) ambience.end()
        }
    }

    private fun attachLocation() {
        when (availability.selectedId(Feature.LOCATION)) {
            ProbeChains.Ids.LOC_NAVI -> location.attach(naviLocation)
            ProbeChains.Ids.LOC_ANDROID -> location.attach(androidLocation)
            else -> location.detach() // 데모 경로는 조명 화면·adb 로 켠다
        }
    }

    /** 정차 중 조명 화면·음성에서 수동 변경. */
    suspend fun changeLight(name: String?): String? = ambience.manual(name?.let { LightPreset.byLabel(it) })?.label

    // 폰 연결 (T10: 로컬 서버가 Supabase 대체)
    val phone = PhoneLink(BuildConfig.CHAZ_SERVER_URL, DeviceIdentity.carId(appContext), scope)

    /** 마지막 완성본을 폰으로. 저장본이 없으면 마지막 세션을 미리보기에서 고른(없으면 L2) 레이아웃으로 만들어 보낸다. */
    suspend fun sendLatestToPhone(): SendOutcome {
        val result = composer.lastSaved.value ?: run {
            val session = capture.lastResult.value ?: return SendOutcome.NO_RESULT
            if (session.cuts.isEmpty()) return SendOutcome.NO_RESULT
            val prefs = previewPrefs.value
            val layout = prefs.layout?.takeIf { composer.available(it, session.cuts) } ?: Layout.CUT_FRAME
            runCatching {
                val opts = composer.options(session, ai.chaz.media.FrameColor.FILM, true, prefs.filter)
                composer.save(session.session.id, layout, composer.render(layout, session.cuts, opts, preview = false), opts)
            }.getOrElse { return SendOutcome.FAILED }
        }
        return runCatching { phone.deliver(result) }.fold(
            onSuccess = {
                capture.dispatch(AppEvent.SendToPhone)
                SendOutcome.SENT
            },
            onFailure = { android.util.Log.w("ChazPhone", "전송 실패: ${it.message}"); SendOutcome.FAILED },
        )
    }

    val live by lazy {
        val withSeg = mapOf(Layout.WINDOW_SWAP to WindowSwapRenderer(liveSegmenter), Layout.CUT_FRAME to CutFrameRenderer(liveSegmenter))
        val noSeg = mapOf(Layout.WINDOW_SWAP to WindowSwapRenderer(noSegmenter), Layout.CUT_FRAME to CutFrameRenderer(noSegmenter))
        LivePreviewEngine(
            sources = { _cameras.value },
            renderers = { seg -> if (seg) withSeg else noSeg },
            options = { composer.previewOptions(_cameras.value.map { it.kind }.toSet()) },
        )
    }
    /** 라이브용은 결과물용과 따로 둔다 (동시에 돌 때 서로 기다리지 않게). */
    private val liveSegmenter: PersonSegmenter by lazy {
        if (availability.selectedId(Feature.PERSON_SEGMENTER) == ProbeChains.Ids.SEGMENTER_MEDIAPIPE) MediaPipePersonSegmenter(appContext) else noSegmenter
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
        // 스팟 가이드도 앱이 화면에 있을 때만 (백그라운드 B1 미검증 → SPEC 'window 에 있을 때만')
        if (foreground) spotGuide.start() else spotGuide.stop()
    }

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private var phoneCamWatch: Job? = null

    /** 촬영 모드(시연/실촬영). 바꾸면 실내 소스만 다시 판정한다. 촬영 중에는 바꾸지 않는다. */
    val shootMode: StateFlow<ai.chaz.capture.ShootMode?> = ai.chaz.capture.ShootModeStore.flow(appContext)

    fun setShootMode(mode: ai.chaz.capture.ShootMode) {
        if (capture.model.value.state is AppState.Capturing) return
        ai.chaz.capture.ShootModeStore.set(appContext, mode)
        scope.launch {
            withContext(Dispatchers.Default) { probe.probe(Feature.CAMERA_INTERIOR) }
            rebuildCameras()
        }
    }

    /** ✨ AI 오픈카 (서버 /frames/opentop). */
    val aiOpenTop = ai.chaz.sync.AiOpenTopClient(BuildConfig.CHAZ_SERVER_URL)

    /** T11: 폰 '실내 카메라 모드'가 켜지거나 꺼지면 실내 소스를 다시 판정한다. 촬영 중에는 바꾸지 않는다. */
    private fun watchPhoneCamera() {
        if (phoneCamWatch?.isActive == true) return
        phoneCamWatch = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(3_000)
                if (!foreground || _probing.value || capture.model.value.state is AppState.Capturing) continue
                val online = ai.chaz.capture.PhoneCameraSource.online(BuildConfig.CHAZ_SERVER_URL, phone.carId) ?: continue
                val usingPhone = availability.selectedId(Feature.CAMERA_INTERIOR) == ProbeChains.Ids.INTERIOR_PHONE
                val wantPhone = online && ai.chaz.capture.ShootModeStore.get(appContext) == ai.chaz.capture.ShootMode.LIVE
                if (wantPhone != usingPhone) {
                    withContext(Dispatchers.Default) { probe.probe(Feature.CAMERA_INTERIOR) }
                    if (capture.model.value.state !is AppState.Capturing) rebuildCameras()
                }
            }
        }
    }

    fun start() {
        reprobe()
        watchPhoneCamera()
        scope.launch {
            driving.restricted.collect { capture.dispatch(AppEvent.DrivingChanged(it)) }
        }
        scope.launch {
            // 마지막 세션 종료 후 3초는 호출어 무시 (SPEC)
            var wasCapturing = false
            capture.model.collect { m ->
                val c = m.state is AppState.Capturing
                if (wasCapturing && !c) voice.ignoreWakeUntil = System.currentTimeMillis() + 3_000
                // 앰비언트: 세션 시작 때 한 번 적용(가이드에서 이어지면 그대로), 끝나면 복원
                if (!wasCapturing && c) { spotEpisode?.cancel(); scope.launch { ambience.begin("session") } }
                if (wasCapturing && !c) scope.launch { kotlinx.coroutines.delay(1_000); ambience.end() }
                wasCapturing = c
            }
        }
        scope.launch {
            driving.status.collect { android.util.Log.i("ChazDriving", "restricted=${it.restricted} source=${it.source} detail=${it.detail} override=${it.override}") }
        }
    }

    @Volatile private var reprobeAgain = false

    fun reprobe() {
        // 판정 중에 권한이 바뀌면(첫 실행 권한 대화상자) 끝난 뒤 한 번 더 판정한다
        if (_probing.value) { reprobeAgain = true; return }
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
                attachLocation()
                updateWakeLoop()
                phone.refresh()
                refreshContext()
                speech.prepare(listOf(phrases.captureStart(CaptureConfig()), phrases.doneParked(), phrases.doneDriving()))
            } finally {
                _probing.value = false
                if (reprobeAgain) { reprobeAgain = false; reprobe() }
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
