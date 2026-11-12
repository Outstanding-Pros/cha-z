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
import kotlinx.coroutines.flow.first
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

    private val sessionStore by lazy { FileSessionStore(appContext) }

    val capture by lazy {
        CaptureController(
            scope = scope,
            machine = AppStateMachine { trigger, config ->
                Session(newSessionId(), System.currentTimeMillis(), config, trigger, spotName = spotGuide.placeForSession())
            },
            engine = CaptureSessionEngine(speech, cues, sessionStore, phrases),
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
            renderers = mapOf(
                Layout.WINDOW_SWAP to WindowSwapRenderer(seg), Layout.CUT_FRAME to CutFrameRenderer(seg),
                Layout.ROUTE_OVERLAY to ai.chaz.media.RouteOverlayRenderer(seg),
            ),
            vehicle = { vehicleState },
            windowMask = { currentWindowMask() },
            route = { currentRoute() },
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
    /** 설정: 주행 중 조명 자동 변경 (기본 끔 = 정차해서 찍을 때만 바뀜). 11/12 팀 논의용으로 둘 다 둔다. */
    private val _autoLightDriving = MutableStateFlow(
        appContext.getSharedPreferences("chaz", android.content.Context.MODE_PRIVATE).getBoolean("autoLightDriving", false),
    )
    val autoLightDriving: StateFlow<Boolean> = _autoLightDriving.asStateFlow()
    fun setAutoLightDriving(on: Boolean) {
        appContext.getSharedPreferences("chaz", android.content.Context.MODE_PRIVATE).edit().putBoolean("autoLightDriving", on).apply()
        _autoLightDriving.value = on
    }
    val ambience = AmbienceDirector(
        light = { ambientLight }, context = { refreshContext() }, driving = { driving.restricted.value },
        autoWhileDriving = { _autoLightDriving.value },
    )

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

    // ---- 여정 앨범 (도착 = 정차 중 도어 열림 → 오늘 안 보낸 결과물 전부 폰으로) ----
    private val prefs = appContext.getSharedPreferences("chaz", android.content.Context.MODE_PRIVATE)
    private val _autoAlbum = MutableStateFlow(prefs.getBoolean("autoAlbum", true))
    /** 설정: 도착하면 자동 전송 (기본 켜짐). 꺼도 '지금 앨범 보내기'·음성 전송은 된다. */
    val autoAlbum: StateFlow<Boolean> = _autoAlbum.asStateFlow()
    fun setAutoAlbum(on: Boolean) { prefs.edit().putBoolean("autoAlbum", on).apply(); _autoAlbum.value = on }

    private val pleosDoor by lazy { ai.chaz.platform.vehicle.PleosDoorSensor(vehicleHub) }
    private var doorSensor: ai.chaz.platform.vehicle.DoorSensor? = null
    /** 개발·시연용 도어 신호 (--es door open|close). 실제 센서 값보다 우선. */
    private val debugDoor = MutableStateFlow<Boolean?>(null)
    private val sensorDoor = MutableStateFlow<Boolean?>(null)
    private var doorWatch: kotlinx.coroutines.Job? = null
    private var lastAutoAlbumMs: Long? = null
    private val _doorName = MutableStateFlow("판정 중")
    /** 설정 화면 표시용: 도착 감지에 쓰는 구현 이름. */
    val doorSensorName: StateFlow<String> = _doorName.asStateFlow()

    private val _album = MutableStateFlow(AlbumStatus())
    data class AlbumStatus(val pending: Int = 0, val sending: Boolean = false, val last: String? = null)
    val album: StateFlow<AlbumStatus> = _album.asStateFlow()

    private fun deliveredIds(): Set<String> = prefs.getStringSet("deliveredIds", emptySet()).orEmpty()
    private fun markDelivered(id: String) { prefs.edit().putStringSet("deliveredIds", deliveredIds() + id).apply() }

    private fun todayStartMs(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    suspend fun pendingAlbum(): List<ai.chaz.media.Result> { val sent = deliveredIds(); return composer.savedSince(todayStartMs()).filter { it.id !in sent } }
    suspend fun refreshAlbum() { _album.value = _album.value.copy(pending = pendingAlbum().size) }

    /** 결과물 하나를 폰으로 (보낸 것으로 기록 → 앨범에서 다시 보내지 않음). */
    suspend fun deliver(result: ai.chaz.media.Result): Int = phone.deliver(result).also { markDelivered(result.id); refreshAlbum() }

    /** 오늘 안 보낸 결과물 전부를 폰으로. 보낸 장 수 (실패하면 거기서 멈춘다). */
    suspend fun sendAlbum(auto: Boolean): Int {
        if (_album.value.sending) return 0
        val list = pendingAlbum()
        if (list.isEmpty()) { _album.value = _album.value.copy(pending = 0, last = "보낼 새 사진이 없어요"); return 0 }
        if (!phone.state.value.paired) { _album.value = _album.value.copy(last = "폰이 연결되지 않았어요"); return 0 }
        _album.value = _album.value.copy(sending = true)
        var n = 0
        try {
            for (r in list) {
                val ok = runCatching { phone.deliver(r) }.onFailure { android.util.Log.w("ChazAlbum", "전송 실패: ${it.message}") }.isSuccess
                if (!ok) break
                markDelivered(r.id); n++
            }
        } finally {
            _album.value = AlbumStatus(pending = pendingAlbum().size, sending = false, last = (if (auto) "도착 · " else "") + "${n}장을 폰으로 보냈어요")
        }
        if (n > 0) runCatching { speech.speak(if (auto) phrases.albumArrived(n) else phrases.albumSent(n)) }
        return n
    }

    fun setDebugDoor(open: Boolean?) { debugDoor.value = open }

    private fun attachDoor() {
        val want = if (availability.selectedId(Feature.DOOR) == ProbeChains.Ids.DOOR_PLEOS) pleosDoor else null
        if (want !== doorSensor) {
            doorSensor?.stop()
            doorSensor = want
            sensorDoor.value = null
            _doorName.value = want?.name ?: "감지 안 함 (수동 전송만)"
            want?.let { s ->
                scope.launch {
                    runCatching { s.start() }.onFailure { android.util.Log.w("ChazAlbum", "도어 감지 시작 실패: ${it.message}") }
                    s.anyOpen.collect { sensorDoor.value = it }
                }
            }
        }
        if (want == null) _doorName.value = "감지 안 함 (수동 전송만)"
        if (doorWatch?.isActive == true) return
        doorWatch = scope.launch {
            var was: Boolean? = null
            kotlinx.coroutines.flow.combine(debugDoor, sensorDoor) { d, s -> d ?: s }.collect { open ->
                val decision = ai.chaz.sync.ArrivalAlbumPolicy.decide(
                    wasOpen = was, isOpen = open, enabled = _autoAlbum.value, driving = driving.restricted.value,
                    paired = phone.state.value.paired, pending = pendingAlbum().size,
                    nowMs = System.currentTimeMillis(), lastAutoSendMs = lastAutoAlbumMs,
                )
                was = open
                if (decision != ai.chaz.sync.ArrivalAlbumPolicy.Decision.NO_EDGE) android.util.Log.i("ChazAlbum", "도어 열림 → $decision")
                if (decision == ai.chaz.sync.ArrivalAlbumPolicy.Decision.SEND) {
                    lastAutoAlbumMs = System.currentTimeMillis()
                    sendAlbum(auto = true)
                }
            }
        }
    }

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
        return runCatching { deliver(result) }.fold(
            onSuccess = {
                capture.dispatch(AppEvent.SendToPhone)
                SendOutcome.SENT
            },
            onFailure = { android.util.Log.w("ChazPhone", "전송 실패: ${it.message}"); SendOutcome.FAILED },
        )
    }

    val live by lazy {
        val withSeg = mapOf(
            Layout.WINDOW_SWAP to WindowSwapRenderer(liveSegmenter), Layout.CUT_FRAME to CutFrameRenderer(liveSegmenter),
            Layout.ROUTE_OVERLAY to ai.chaz.media.RouteOverlayRenderer(liveSegmenter),
        )
        val noSeg = mapOf(
            Layout.WINDOW_SWAP to WindowSwapRenderer(noSegmenter), Layout.CUT_FRAME to CutFrameRenderer(noSegmenter),
            Layout.ROUTE_OVERLAY to ai.chaz.media.RouteOverlayRenderer(noSegmenter),
        )
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

    /**
     * L3 경로 카드: 데모 경로 + 지금 위치 + 다음 스팟(목적지 대신). 도착 시각은 남은 직선거리를 평균 40km/h 로 나눈 추정.
     * TODO(T15): Navi 목적지·경로(onDestinationInfo/onTripInfo)가 열리면 그 값으로 바꾼다.
     */
    private fun currentRoute(): ai.chaz.media.RouteInfo? {
        val pts = demoRoute.map { it.lat to it.lon }
        if (pts.size < 2) return null
        val here = location.location.value
        val next = runCatching { spotGuide.nearest.value }.getOrNull()
        val eta = next?.let {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(System.currentTimeMillis() + (it.distanceM / (40_000.0 / 3_600_000)).toLong()))
        }
        return ai.chaz.media.RouteInfo(pts, here?.let { it.lat to it.lon }, next?.spot?.name, next?.distanceM, eta, next?.spot?.point?.let { it.lat to it.lon })
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
        // 폰 음성 리모컨: 폰에서 말한 문장을 차 마이크로 들은 것처럼 처리 (버튼을 눌렀으니 호출어는 생략)
        // 폰 직접 셔터: 폰이 찍어 보낸 사진을 1컷 세션으로 받아 편집 화면에 띄운다
        phone.listen(
            onVoice = { text -> voice.handleText(text, requireWake = false) },
            onPhoto = { id -> onPhonePhoto(id) },
        )
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
                attachDoor()
                refreshAlbum()
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

    /**
     * 폰 직접 셔터로 온 사진. 먼저 받아 두고(서버는 60초만 보관), 차가 촬영 중이면 끝날 때까지 기다렸다가 넣는다.
     * 창밖은 받은 순간 차 카메라로 같이 찍는다. 주행 중이면 저장만(편집 잠금) — 상태 머신이 정한다.
     */
    private suspend fun onPhonePhoto(photoId: String) {
        val jpeg = phone.fetchPushed(photoId) ?: return
        capture.model.first { it.state !is AppState.Capturing }
        val config = ai.chaz.data.CaptureRules.normalize(ai.chaz.data.CaptureConfig(durationSec = 4, count = 1)).config
        val session = Session(newSessionId(), System.currentTimeMillis(), config, ai.chaz.data.TriggerSource.PHONE_SHUTTER,
            spotName = spotGuide.placeForSession())
        val exterior = _cameras.value.filter { it.role != CameraRole.INTERIOR }
        val cut = runCatching { sessionStore.saveImported(session, jpeg, exterior) }
            .onFailure { android.util.Log.w("ChazPhone", "폰 사진 저장 실패: ${it.javaClass.simpleName}") }.getOrNull() ?: return
        android.util.Log.i("ChazPhone", "폰 직접 셔터 사진 받음 (창밖 ${cut.frames.size - 1}장)")
        if (capture.import(ai.chaz.capture.CaptureSessionEngine.Result(session, listOf(cut), emptyList(), stopped = false))) {
            runCatching { speech.speak(phrases.phonePhoto(driving.restricted.value)) }
        }
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
