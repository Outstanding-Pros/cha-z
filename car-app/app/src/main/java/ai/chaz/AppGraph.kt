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
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
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

    private val sessionStore by lazy { FileSessionStore(appContext) }

    /** 앨범 (편집 탭): 폰 직접 촬영 묶음 + 차~즈 촬영(단발·동영상) 결과. */
    val phoneAlbum by lazy { ai.chaz.sync.PhoneAlbumStore(appContext) }
    /** 앨범의 릴스(세로 9:16 MP4). */
    val reels by lazy { ai.chaz.sync.ReelStore(appContext) }

    /** 차~즈 촬영 방식 (단발 / 동영상). '차즈'·셔터는 이 방식으로 찍는다. */
    private val _captureMode = MutableStateFlow(
        runCatching { ai.chaz.data.CaptureMode.valueOf(appContext.getSharedPreferences("chaz", android.content.Context.MODE_PRIVATE).getString("captureMode", null)!!) }
            .getOrDefault(ai.chaz.data.CaptureMode.VIDEO),
    )
    val captureMode: StateFlow<ai.chaz.data.CaptureMode> = _captureMode.asStateFlow()
    fun setCaptureMode(m: ai.chaz.data.CaptureMode) {
        if (capture.model.value.state is AppState.Capturing) return
        appContext.getSharedPreferences("chaz", android.content.Context.MODE_PRIVATE).edit().putString("captureMode", m.name).apply()
        _captureMode.value = m
    }

    /** 방금 찍은 촬영을 편집 탭 칸에 미리 넣어 둔 초안. 편집 탭이 열리면 가져가고 비운다. */
    data class AlbumDraft(val layout: Layout, val bundleIds: List<String>)
    val albumDraft = MutableStateFlow<AlbumDraft?>(null)

    /**
     * 방금 찍은 촬영 (스타일 기획서 '정차 후'): 주행 중엔 완료 카드, 정차하면 [여기서 편집] [휴대폰에서 편집하기] 카드.
     * 사용자가 고르거나 새로 찍으면 비운다.
     */
    data class JustShot(val sessionId: String, val bundleIds: List<String>, val cuts: Int, val at: Long)
    val justShot = MutableStateFlow<JustShot?>(null)
    /** 촬영 중 "휴대폰 편집" → 이 촬영이 끝나면 폰으로. */
    @Volatile private var handoffPending = false

    /** 차 안 BGM (자체 합성 음원). */
    val bgm by lazy { ai.chaz.media.BgmPlayer(appContext) }

    private suspend fun archiveCapture(result: ai.chaz.capture.CaptureSessionEngine.Result) {
        val saved = phoneAlbum.saveCapture(result)
        if (saved.isEmpty()) return
        val cur = previewPrefs.value.layout?.takeIf { it != Layout.SURROUND_SEQUENCE && composer.available(it) } ?: Layout.CUT_FRAME
        val (layout, picks) = ai.chaz.sync.PhoneAlbum.draftFor(saved, cur)
        albumDraft.value = AlbumDraft(layout, picks.map { it.id })
        justShot.value = JustShot(result.session.id, saved.map { it.id }, saved.size, System.currentTimeMillis())
        android.util.Log.i("ChazAlbum", "촬영 저장: 묶음 ${saved.size}개" + if (result.clips.isNotEmpty()) " + 영상" else "")
        if (handoffPending) {
            handoffPending = false
            scope.launch {
                val out = handoffLatest()
                runCatching { speech.speak(if (out == ai.chaz.voice.SendOutcome.SENT) phrases.handoffSending() else phrases.sendFailed()) }
            }
        }
    }

    /** 방금 찍은 촬영(세션 원본: 컷마다 실내·전방·후방)을 연결된 폰으로 — "휴대폰에서 편집하기". */
    suspend fun handoffLatest(): ai.chaz.voice.SendOutcome {
        val js = justShot.value
        val bundles = phoneAlbum.bundles.value.let { all ->
            if (js != null) all.filter { it.id in js.bundleIds } else all.firstOrNull()?.let { f -> all.filter { (it.group ?: it.id) == (f.group ?: f.id) } }.orEmpty()
        }.sortedBy { it.cut }
        if (bundles.isEmpty()) return ai.chaz.voice.SendOutcome.NO_RESULT
        if (!phone.state.value.paired) return ai.chaz.voice.SendOutcome.FAILED
        var sent = 0
        for (b in bundles) for ((role, path) in b.photos) {
            val ok = runCatching { phone.deliverRaw("${b.id}_${role.name.lowercase()}", b.group ?: b.id, role.name, path) }
                .onFailure { android.util.Log.w("ChazPhone", "핸드오프 실패: ${it.javaClass.simpleName}") }.isSuccess
            if (!ok) return if (sent > 0) ai.chaz.voice.SendOutcome.SENT else ai.chaz.voice.SendOutcome.FAILED
            sent++
        }
        android.util.Log.i("ChazPhone", "핸드오프: 원본 ${sent}장")
        return ai.chaz.voice.SendOutcome.SENT
    }

    val capture by lazy {
        CaptureController(
            scope = scope,
            machine = AppStateMachine { trigger, config ->
                Session(newSessionId(), System.currentTimeMillis(), config, trigger, spotName = spotGuide.placeForSession())
            },
            engine = CaptureSessionEngine(speech, cues, sessionStore, phrases),
            sources = { _cameras.value },
            isDriving = { driving.restricted.value },
            archive = { archiveCapture(it) },
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
                override val defaultConfig get() = _captureMode.value.config
                override val driving get() = this@AppGraph.driving.restricted.value
                override fun startCapture(trigger: TriggerSource, config: CaptureConfig) { capture.start(trigger, config) }
                override fun stopCapture() = capture.stop()
                override val phonePaired get() = phone.state.value.paired
                override suspend fun sendToPhone() = sendLatestToPhone()
                override suspend fun changeLight(preset: String?) = this@AppGraph.changeLight(preset)
                override suspend fun awaitNextShot() {
                    val st = capture.model.value.state as? AppState.Capturing ?: return
                    kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        capture.model.first { val s = it.state; s !is AppState.Capturing || s.shotsTaken > st.shotsTaken }
                    }
                }
                override suspend fun awaitCaptureEnd() {
                    kotlinx.coroutines.withTimeoutOrNull(20_000) { capture.model.first { it.state !is AppState.Capturing } }
                    // 앨범 저장(Archived)까지 끝나 대기로 돌아갈 때까지
                    kotlinx.coroutines.withTimeoutOrNull(8_000) { capture.model.first { it.state is AppState.Standby || it.state is AppState.Editing } }
                }
                override suspend fun playBgm(mood: String?, next: Boolean) = bgm.play(ai.chaz.media.BgmPlayer.moodOf(mood), next)?.title
                override val bgmPlaying get() = bgm.now.value != null
                override fun stopBgm() = bgm.stop()
                override fun handoffAfterCapture(): Boolean { handoffPending = phone.state.value.paired; return handoffPending }
                override suspend fun handoffLatest() = this@AppGraph.handoffLatest()
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
                val ok = runCatching { phone.deliver(r) }.onFailure { android.util.Log.w("ChazAlbum", "전송 실패: ${it.javaClass.simpleName}") }.isSuccess
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
            onFailure = { android.util.Log.w("ChazPhone", "전송 실패: ${it.javaClass.simpleName}"); SendOutcome.FAILED },
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
        // 폰 직접 촬영 묶음: 바로 합성하지 않고 갤러리(폰 앨범)에 저장 → 사용자가 자리마다 골라 편집
        scope.launch { phoneAlbum.load() }
        scope.launch { reels.load() }
        // 새로 찍기 시작하면 '방금 찍은 촬영' 카드는 내린다
        scope.launch { capture.model.collect { if (it.state is AppState.Capturing) justShot.value = null } }
        phone.listen(
            onVoice = { text -> voice.handleText(text, requireWake = false) },
            onBundle = { id, roles -> onPhoneBundle(id, roles) },
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

    /** 폰 직접 촬영 묶음 도착: 사진을 받아 앨범에 저장만 한다(편집 화면으로 넘기지 않음). */
    private suspend fun onPhoneBundle(bundleId: String, roleNames: List<String>) {
        val photos = ai.chaz.sync.PhoneAlbum.parseRoles(roleNames)
            .mapNotNull { r -> phone.fetchBundlePhoto(bundleId, r.name)?.let { r to it } }.toMap()
        phone.doneBundle(bundleId)
        if (photos.isEmpty()) return
        val saved = runCatching { phoneAlbum.save(newSessionId(), photos, spotGuide.placeForSession()) }
            .onFailure { android.util.Log.w("ChazPhone", "폰 묶음 저장 실패: ${it.javaClass.simpleName}") }.getOrNull() ?: return
        android.util.Log.i("ChazPhone", "폰 묶음 받음 ${saved.photos.size}장")
        if (capture.model.value.state !is AppState.Capturing) runCatching { speech.speak(phrases.phoneBundle(saved.photos.size)) }
    }

    /**
     * 갤러리에서 프레임(layout)을 정하고 컷 칸마다 넣은 묶음들로 편집으로. 묶음 하나 = 컷 하나.
     * 정차 중이면 편집 화면(그 프레임으로 열림), 주행 중이면 저장(편집 잠금) — 상태 머신. 차가 촬영 중이면 false.
     */
    suspend fun editPhoneBundles(layout: ai.chaz.media.Layout, bundles: List<ai.chaz.sync.PhoneBundle>): Boolean {
        if (capture.model.value.state is AppState.Capturing || bundles.isEmpty()) return false
        val config = ai.chaz.data.CaptureRules.normalize(ai.chaz.data.CaptureConfig(durationSec = 4, count = bundles.size, video = false)).config
        val session = Session(newSessionId(), System.currentTimeMillis(), config, ai.chaz.data.TriggerSource.ALBUM,
            spotName = spotGuide.placeForSession())
        val frames = bundles.map { b -> b.photos.map { (role, path) -> ai.chaz.data.Frame(role, path, b.sourceOf(role)) } }
        val cuts = runCatching { sessionStore.saveBundles(session, frames) }.getOrNull() ?: return false
        previewPrefs.value = previewPrefs.value.copy(layout = layout) // 편집 화면이 고른 프레임으로 열리게
        return capture.import(ai.chaz.capture.CaptureSessionEngine.Result(session, cuts, emptyList(), stopped = false))
    }

    // ---- 앨범 '만들기': 묶음 + 기능(AlbumTools) → 앨범의 새 사진 ----
    sealed interface ToolState {
        data object Working : ToolState
        data class Failed(val message: String) : ToolState
    }
    private val _toolJobs = MutableStateFlow<Map<String, ToolState>>(emptyMap())
    /** 묶음·기능별 진행 상태 (끝나면 지워지고 결과는 phoneAlbum.made 에 들어간다). 탭을 옮겨도 이어진다. */
    val toolJobs: StateFlow<Map<String, ToolState>> = _toolJobs.asStateFlow()
    fun toolKey(b: ai.chaz.sync.PhoneBundle, t: ai.chaz.sync.AlbumTool) = "${b.id}:${t.id}"

    /** 만들기 실행. 이미 만든 결과가 있으면 다시 만들지 않는다(비용 절약) — remake 면 새로. */
    fun runTool(b: ai.chaz.sync.PhoneBundle, t: ai.chaz.sync.AlbumTool, remake: Boolean = false) {
        if (!t.accepts(b)) return
        val key = toolKey(b, t)
        if (_toolJobs.value[key] is ToolState.Working) return
        if (!remake && phoneAlbum.made(b, t.id) != null) return
        _toolJobs.update { it + (key to ToolState.Working) }
        scope.launch {
            val failed: ToolState? = try {
                val photos = withContext(Dispatchers.IO) { t.needs.associateWith { r -> BitmapFactory.decodeFile(b.photos[r]) } }
                if (photos.values.any { it == null }) error("사진을 읽지 못했어요")
                @Suppress("UNCHECKED_CAST")
                val out = t.make(photos as Map<CameraRole, android.graphics.Bitmap>, aiOpenTop)
                phoneAlbum.saveMade(b, t, out)
                android.util.Log.i("ChazAlbum", "만들기 완료 ${t.id}")
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ChazAlbum", "만들기 실패 ${t.id}: ${e.javaClass.simpleName}")
                ToolState.Failed((e as? ai.chaz.sync.AiOpenTopClient.Failed)?.message ?: "만들지 못했어요")
            }
            _toolJobs.update { if (failed == null) it - key else it + (key to failed) }
        }
    }

    /** 네컷 칸마다 넣은 앨범 사진(한 장씩)으로 편집 화면을 연다. 칸 사진은 Cut.composed 로 그대로 칸에 채운다. 촬영 중이면 false. */
    suspend fun editPicks(picks: List<ai.chaz.sync.AlbumPick>): Boolean {
        if (capture.model.value.state is AppState.Capturing || picks.isEmpty()) return false
        if (picks.any { !java.io.File(it.path).exists() }) return false
        val config = ai.chaz.data.CaptureRules.normalize(ai.chaz.data.CaptureConfig(durationSec = 4, count = picks.size, video = false)).config
        val session = Session(newSessionId(), System.currentTimeMillis(), config, ai.chaz.data.TriggerSource.ALBUM, spotName = spotGuide.placeForSession())
        val frames = picks.map { p ->
            when (p) {
                is ai.chaz.sync.AlbumPick.Photo -> listOf(ai.chaz.data.Frame(p.role, p.path, p.bundle.sourceOf(p.role)))
                is ai.chaz.sync.AlbumPick.Made -> listOf(ai.chaz.data.Frame(CameraRole.INTERIOR, p.path,
                    if (p.ai) ai.chaz.capture.SourceKind.AI_GENERATED else ai.chaz.capture.SourceKind.PHONE_CAMERA))
            }
        }
        val cuts = runCatching { sessionStore.saveBundles(session, frames, picks.map { it.path to it.ai }) }.getOrNull() ?: return false
        previewPrefs.value = previewPrefs.value.copy(layout = Layout.CUT_FRAME)
        return capture.import(ai.chaz.capture.CaptureSessionEngine.Result(session, cuts, emptyList(), stopped = false))
    }

    // ---- 릴스(세로 9:16) 만들기 ----
    sealed interface ReelJob {
        data class Working(val progress: Float) : ReelJob
        data class Done(val reel: ai.chaz.sync.AlbumReel) : ReelJob
        data class Failed(val message: String) : ReelJob
    }
    private val _reelJob = MutableStateFlow<ReelJob?>(null)
    val reelJob: StateFlow<ReelJob?> = _reelJob.asStateFlow()
    fun clearReelJob() { if (_reelJob.value !is ReelJob.Working) _reelJob.value = null }

    /** 릴스 요청 — 스타일마다 재료가 다르다(ReelStyle.input). useAi = 스타일의 AI 단계를 쓸지(끄면 대체 경로). */
    sealed interface ReelRequest {
        val style: ai.chaz.media.ReelStyle
        data class Basic(val picks: List<ai.chaz.sync.AlbumPick>, val motion: ai.chaz.media.ReelMotion) : ReelRequest { override val style get() = ai.chaz.media.ReelStyle.BASIC }
        data class Picks(override val style: ai.chaz.media.ReelStyle, val picks: List<ai.chaz.sync.AlbumPick>, val useAi: Boolean) : ReelRequest
        data class Video(override val style: ai.chaz.media.ReelStyle, val video: ai.chaz.sync.AlbumVideo, val useAi: Boolean) : ReelRequest
        data class Day(val dayStart: Long, val useAi: Boolean) : ReelRequest { override val style get() = ai.chaz.media.ReelStyle.TRIP }
    }

    /** 고른 앨범 사진(1~4장) + 연출 → 1080×1920 MP4 + 커버 (직접 연출). */
    fun makeReel(picks: List<ai.chaz.sync.AlbumPick>, motion: ai.chaz.media.ReelMotion) = makeReel(ReelRequest.Basic(picks, motion))

    /** 릴스 만들기 → 앨범(reels). 탭을 옮겨도 이어진다. AI 단계가 실패하면 기획서의 대체 경로로 만든다. */
    fun makeReel(req: ReelRequest) {
        if (_reelJob.value is ReelJob.Working) return
        _reelJob.value = ReelJob.Working(0f)
        scope.launch(Dispatchers.Default) {
            val loaded = mutableListOf<android.graphics.Bitmap>()
            fun load(path: String, maxW: Int = 1400, rgb565: Boolean = false): android.graphics.Bitmap {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                val sample = generateSequence(1) { it * 2 }.first { o.outWidth / (it * 2) < maxW }
                return (BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                    inSampleSize = sample; if (rgb565) inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }) ?: error("사진을 읽지 못했어요")).also { loaded += it }
            }
            val result = runCatching {
                val place = spotGuide.placeForSession() ?: "오늘의 드라이브"
                val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
                val date = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date())
                val now = hm.format(Date())
                var aiUsed = false
                fun atOf(p: ai.chaz.sync.AlbumPick) = when (p) { is ai.chaz.sync.AlbumPick.Photo -> p.bundle.receivedAt; is ai.chaz.sync.AlbumPick.Made -> p.photo.at }
                fun placeOf(p: ai.chaz.sync.AlbumPick) = (p as? ai.chaz.sync.AlbumPick.Photo)?.bundle?.place ?: place
                suspend fun <T> tryAi(step: String, block: suspend () -> T): T? = runCatching { block() }
                    .onFailure { android.util.Log.w("ChazReel", "AI 단계 실패($step) → 대체 경로: ${it.javaClass.simpleName}") }.getOrNull()
                    ?.also { aiUsed = true }
                fun videoParts(v: ai.chaz.sync.AlbumVideo): Pair<List<android.graphics.Bitmap>, List<ai.chaz.sync.PhoneBundle>> {
                    val dir = v.clips[CameraRole.FRONT] ?: error("전방 영상이 없어요")
                    val files = java.io.File(dir).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
                    if (files.isEmpty()) error("전방 영상이 없어요")
                    val step = maxOf(1, files.size / 30)
                    val frames = files.filterIndexed { i, _ -> i % step == 0 }.map { load(it.absolutePath, 1300, rgb565 = true) }
                    val cuts = phoneAlbum.bundles.value.filter { it.group == v.group }.sortedBy { it.cut }
                    return frames to cuts
                }
                fun interiorOf(b: ai.chaz.sync.PhoneBundle) = (b.photos[CameraRole.INTERIOR] ?: b.photos.values.first())

                val scene: ai.chaz.media.ReelScene = when (req) {
                    is ReelRequest.Basic -> {
                        val photos = req.picks.map { p -> ai.chaz.media.ReelPhoto(load(p.path), p.ai, "📍 ${placeOf(p)} · ${hm.format(Date(atOf(p)))}") }
                        val focus = if (req.motion == ai.chaz.media.ReelMotion.CROP_ZOOM) photos.map { ph ->
                            runCatching { segmenter.segment(ph.bitmap) }.getOrNull()?.let { m ->
                                val w = ph.bitmap.width.toFloat(); val h = ph.bitmap.height.toFloat()
                                val pw = (m.right - m.left) / w; val ph2 = (m.bottom - m.top) / h
                                ai.chaz.media.FocusBox(m.left / w + pw * 0.25f, m.top / h, m.right / w - pw * 0.25f, m.top / h + ph2 * 0.35f)
                            }
                        } else emptyList()
                        aiUsed = req.picks.any { it.ai }
                        ai.chaz.media.ReelRenderer(photos, req.motion, place, date, focus = focus)
                    }
                    is ReelRequest.Video -> {
                        val (front, bundles) = videoParts(req.video)
                        val cuts = bundles.take(4).map { load(interiorOf(it)) }.ifEmpty { error("실내 컷이 없어요") }
                        val vPlace = bundles.firstNotNullOfOrNull { it.place } ?: place
                        val vTime = hm.format(Date(req.video.at))
                        if (req.style == ai.chaz.media.ReelStyle.OCEAN) {
                            val pano = if (!req.useAi) null else bundles.firstOrNull { it.photos[CameraRole.FRONT] != null && it.photos[CameraRole.REAR] != null }?.let { b ->
                                tryAi("scenery") { aiOpenTop.scenery(load(b.photos.getValue(CameraRole.FRONT), 1280), load(b.photos.getValue(CameraRole.REAR), 1280)) }?.also { loaded += it }
                            }
                            ai.chaz.media.OceanBridgeScene(front, cuts, vPlace, vTime, date, pano)
                        } else {
                            val day = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(req.video.at))
                            val copy = if (req.useAi) tryAi("copy") { aiOpenTop.copy("MOVIE", vPlace, day, vTime) ?: error("카피 없음") } else null
                            val tpl = ai.chaz.media.ReelStyle.movieTemplate(vPlace)
                            val lines = copy?.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.takeIf { it.size == 3 } ?: tpl.second
                            ai.chaz.media.RoadMovieScene(front, cuts, copy?.optString("title")?.ifBlank { null } ?: tpl.first, lines,
                                copy?.optString("tagline")?.ifBlank { null } ?: tpl.third, vPlace, day, aiCopy = copy != null)
                        }
                    }
                    is ReelRequest.Day -> {
                        val end = req.dayStart + 24 * 3_600_000L
                        val sessions = phoneAlbum.bundles.value.filter { it.receivedAt in req.dayStart until end }
                            .groupBy { it.group ?: it.id }.values.map { g -> g.minBy { it.cut } }.sortedBy { it.receivedAt }
                        if (sessions.size < 2) error("같은 날 촬영이 2번 이상 필요해요")
                        val picked = if (sessions.size <= 8) sessions else (0 until 8).map { sessions[it * sessions.size / 8] }.distinct() // 고르게 8곳
                        val cuts = picked.mapIndexed { i, b -> ai.chaz.media.SpotCut(load(interiorOf(b), 1200), b.place ?: "스팟 ${i + 1}", hm.format(Date(b.receivedAt))) }
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = req.dayStart }
                        val day = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(req.dayStart))
                        val copy = if (req.useAi) tryAi("copy") { aiOpenTop.copy("TRIP", place, day, now, cuts.map { it.place }.distinct()) ?: error("카피 없음") } else null
                        ai.chaz.media.TripLogScene(cuts, copy?.optString("title")?.ifBlank { null }
                            ?: ai.chaz.media.ReelStyle.tripTemplateTitle(cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)),
                            copy?.optString("line")?.ifBlank { null }, day, demoRoute.map { it.lat to it.lon }, aiTitle = copy != null)
                    }
                    is ReelRequest.Picks -> {
                        val photos = req.picks.map { load(it.path) }
                        when (req.style) {
                            ai.chaz.media.ReelStyle.NIGHT -> {
                                // AI 별하늘은 정차 장면(둘째 컷부터)에만 — 처음 노을 컷은 원본 그대로 (기획서)
                                val night = if (req.useAi) tryAi("night") {
                                    photos.mapIndexed { i, b -> if (i == 0) b else aiOpenTop.night(b).also { loaded += it } }
                                } else null
                                ai.chaz.media.CampNightScene(photos, night, placeOf(req.picks.last()), date, hm.format(Date(atOf(req.picks.last()))))
                            }
                            ai.chaz.media.ReelStyle.PET -> {
                                val swapped = if (req.useAi) tryAi("petswap") { aiOpenTop.petSwap(photos.last()).also { loaded += it } } else null
                                ai.chaz.media.PetDriverScene(photos, swapped, date)
                            }
                            else -> error("이 스타일은 사진 셀렉이 아니에요")
                        }
                    }
                }
                val id = "reel_" + newSessionId()
                val dir = reels.newDir(id)
                val video = java.io.File(dir, "reel.mp4")
                val cover = java.io.File(dir, "cover.jpg")
                android.graphics.Bitmap.createBitmap(ai.chaz.media.ReelSpec.W, ai.chaz.media.ReelSpec.H, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                    scene.drawCover(android.graphics.Canvas(b))
                    cover.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    b.recycle()
                }
                val out = ai.chaz.media.ReelEncoder.encode(scene, video) { _reelJob.value = ReelJob.Working(it) }
                android.util.Log.i("ChazReel", "릴스 완료 ${req.style.name} ${out.width}x${out.height} ${out.frames}f 소리=${out.audio} AI=$aiUsed")
                val label = if (req is ReelRequest.Basic) req.motion.label else req.style.title
                val count = when (req) { is ReelRequest.Basic -> req.picks.size; is ReelRequest.Picks -> req.picks.size; else -> 0 }
                val reel = ai.chaz.sync.AlbumReel(id, System.currentTimeMillis(), label, count, aiUsed, scene.durationMs, video.absolutePath, cover.absolutePath)
                reels.add(reel)
                reel
            }
            loaded.forEach { runCatching { it.recycle() } }
            _reelJob.value = result.fold(
                onSuccess = { ReelJob.Done(it) },
                onFailure = {
                    android.util.Log.w("ChazReel", "릴스 실패: ${it.javaClass.simpleName}")
                    ReelJob.Failed(it.message?.takeIf { m -> m.any { ch -> ch in '가'..'힣' } } ?: "릴스를 만들지 못했어요")
                },
            )
        }
    }

    /** 릴스를 연결된 폰으로. 보낸 폰 수 (실패면 null). */
    suspend fun sendReel(r: ai.chaz.sync.AlbumReel): Int? =
        if (!phone.state.value.paired) 0
        else runCatching { phone.deliverReel(r) }.onFailure { android.util.Log.w("ChazReel", "전송 실패: ${it.javaClass.simpleName}") }.getOrNull()

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
