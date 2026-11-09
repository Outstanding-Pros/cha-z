package ai.chaz.platform

import ai.chaz.capture.CameraInventory
import ai.chaz.capture.CameraRole
import ai.chaz.platform.driving.CarUxDrivingGate
import ai.chaz.platform.driving.VehicleGearDrivingGate
import ai.chaz.platform.navi.NaviProbes
import ai.chaz.platform.vehicle.PleosVehicleHub
import ai.chaz.sync.ChazServer
import ai.chaz.voice.VoiceProbes
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 사양서 '기능별 후보 체인' 표를 그대로 옮긴 것. 왼쪽(위)부터 시도한다.
 * 후보 id 는 어댑터 팩토리가 구현체를 고를 때 쓰므로 바꾸면 팩토리도 같이 바꾼다.
 */
object ProbeChains {

    object Ids {
        const val DRIVING_CAR_UX = "aaos.car-ux-restrictions"
        const val DRIVING_PLEOS_GEAR = "pleos.vehicle.gear"
        const val DRIVING_DEBUG = "debug.toggle"

        const val TTS_PLEOS = "pleos.tts"
        const val TTS_ANDROID = "android.tts"
        const val TTS_BIZROUTER = "bizrouter.tts"
        const val TTS_CLIPS = "fallback.clips"

        const val STT_PLEOS = "pleos.stt"
        const val STT_ANDROID = "android.speech-recognizer"
        const val STT_BIZROUTER = "bizrouter.transcribe"
        const val STT_VOSK = "vosk.free"

        const val WAKE_PLEOS = "pleos.stt.wakeword"
        const val WAKE_VOSK = "vosk.grammar"
        const val WAKE_BUTTON = "fallback.voice-button"

        const val EXTERIOR_VEHICLE = "vehicle.camera.exterior"
        const val EXTERIOR_RECORDED = "fallback.recorded-video"
        const val INTERIOR_VEHICLE = "vehicle.camera.interior"
        const val INTERIOR_WEBCAM = "emulator.webcam"
    }

    fun build(context: Context, hub: PleosVehicleHub, server: ChazServer): Map<Feature, List<Candidate>> {
        val ctx = context.applicationContext
        suspend fun bizrouter(): ProbeResult {
            val h = server.health()
            return when {
                !h.reachable -> ProbeResult.Fail(h.detail)
                !h.keyConfigured -> ProbeResult.Fail(h.detail)
                else -> ProbeResult.Ok(h.detail)
            }
        }
        val cameras by lazy { CameraInventory.scan(ctx) }

        return linkedMapOf(
            Feature.DRIVING_GATE to listOf(
                Candidate(Ids.DRIVING_CAR_UX, "CarUxRestrictions", Tier.PLATFORM) { CarUxDrivingGate.probe(ctx) },
                Candidate(Ids.DRIVING_PLEOS_GEAR, "Vehicle SDK 기어", Tier.PLEOS_SDK) { VehicleGearDrivingGate.probe(hub) },
                Candidate(Ids.DRIVING_DEBUG, "개발용 디버그 토글", Tier.FALLBACK) { ProbeResult.Ok("항상 사용 가능") },
            ),
            Feature.VEHICLE_STATE to listOf(
                Candidate("pleos.vehicle.state", "Vehicle SDK", Tier.PLEOS_SDK) { PleosVehicleHub.probeVehicleState(hub) },
                Candidate("aaos.car-property", "CarPropertyManager", Tier.PLATFORM) { ProbeResult.Pending("Vehicle SDK 실패 시 T13에서 구현") },
                Candidate("fallback.hidden", "표시 생략", Tier.FALLBACK) { ProbeResult.Ok("스탬프에서 차량 데이터 생략") },
            ),
            Feature.AMBIENT_LIGHT to listOf(
                Candidate("pleos.vehicle.light", "Vehicle SDK Light Set", Tier.PLEOS_SDK) { PleosVehicleHub.probeAmbientLight(hub) },
                Candidate("fallback.screen-border", "화면 테두리 색 시뮬레이션", Tier.FALLBACK) { ProbeResult.Ok("'앰비언트 라이트(시뮬레이션)' 표기") },
            ),
            Feature.LOCATION to listOf(
                Candidate("pleos.navihelper", "NaviHelper SDK", Tier.PLEOS_SDK) { NaviProbes.probeNaviHelper(ctx) },
                Candidate("pleos.fused-location", "FusedLocation SDK", Tier.PLEOS_SDK) { ProbeResult.Pending("NaviHelper 실패 시 T12에서 추가") },
                Candidate("android.location-manager", "Android LocationManager", Tier.PLATFORM) { NaviProbes.probeLocationManager(ctx) },
                Candidate("fallback.demo-route", "데모 경로 재생", Tier.FALLBACK) { ProbeResult.Ok("데모 경로 사용") },
            ),
            Feature.WAKE_WORD to listOf(
                Candidate(Ids.WAKE_PLEOS, "SpeechToText SDK 연속 인식 + '차즈' 감지", Tier.PLEOS_SDK) {
                    // TODO(pleos-api): 커스텀 호출어 등록 API 는 미확인 → 온디바이스 연속 인식 결과에서 '차즈'를 찾는다
                    VoiceProbes.probePleosStt(ctx)
                },
                Candidate(Ids.WAKE_VOSK, "Vosk 한국어(문법 제한)", Tier.FALLBACK) { ProbeResult.Pending("미포함 — Pleos 온디바이스 STT로 대체(용량)") },
                Candidate(Ids.WAKE_BUTTON, "음성 버튼", Tier.FALLBACK) { ProbeResult.Ok("버튼 탭 후 명령") },
            ),
            Feature.SPEECH_INPUT to listOf(
                Candidate(Ids.STT_PLEOS, "SpeechToText SDK", Tier.PLEOS_SDK) { VoiceProbes.probePleosStt(ctx) },
                Candidate(Ids.STT_ANDROID, "Android SpeechRecognizer", Tier.PLATFORM) { VoiceProbes.probeAndroidRecognizer(ctx) },
                Candidate(Ids.STT_BIZROUTER, "비즈라우터 전사", Tier.BIZROUTER) { bizrouter() },
                Candidate(Ids.STT_VOSK, "Vosk 자유 발화", Tier.FALLBACK) { ProbeResult.Pending("미포함 — 용량") },
            ),
            Feature.SPEECH_OUTPUT to listOf(
                Candidate(Ids.TTS_PLEOS, "TextToSpeech SDK", Tier.PLEOS_SDK) { VoiceProbes.probePleosTts(ctx) },
                Candidate(Ids.TTS_ANDROID, "Android TextToSpeech(한국어)", Tier.PLATFORM) { VoiceProbes.probeAndroidTts(ctx) },
                Candidate(Ids.TTS_BIZROUTER, "비즈라우터 TTS(캐시)", Tier.BIZROUTER) { bizrouter() },
                Candidate(Ids.TTS_CLIPS, "미리 녹음한 클립", Tier.FALLBACK) { assetsProbe(ctx, "tts", "녹음 클립") },
            ),
            Feature.CAMERA_EXTERIOR to listOf(
                Candidate(Ids.EXTERIOR_VEHICLE, "차량 전·후방 카메라(Camera2)", Tier.PLATFORM) {
                    val f = cameras.byRole(CameraRole.FRONT).filter { it.roleSource.startsWith("AUTOMOTIVE") }
                    val r = cameras.byRole(CameraRole.REAR).filter { it.roleSource.startsWith("AUTOMOTIVE") }
                    if (f.isEmpty() && r.isEmpty()) ProbeResult.Fail("AUTOMOTIVE_LOCATION 전·후방 카메라 없음 (전체 ${cameras.cameras.size}대)")
                    else ProbeResult.Ok("전방 ${f.size}, 후방 ${r.size}")
                },
                Candidate(Ids.EXTERIOR_RECORDED, "사전 녹화 주행 영상(시뮬레이션)", Tier.FALLBACK) { assetsProbe(ctx, "exterior-mock", "목 영상") },
            ),
            Feature.CAMERA_INTERIOR to listOf(
                Candidate(Ids.INTERIOR_VEHICLE, "차량 실내 카메라", Tier.PLATFORM) {
                    val i = cameras.byRole(CameraRole.INTERIOR).filter { it.roleSource.startsWith("AUTOMOTIVE") }
                    if (i.isEmpty()) ProbeResult.Fail("AUTOMOTIVE_LOCATION 실내 카메라 없음") else ProbeResult.Ok("${i.size}대")
                },
                Candidate("phone.companion", "폰 컴패니언 카메라(P5)", Tier.FALLBACK) { ProbeResult.Pending("T11 페어링 후 사용") },
                Candidate(Ids.INTERIOR_WEBCAM, "에뮬레이터 웹캠", Tier.PLATFORM) {
                    val w = cameras.cameras.filter { !it.roleSource.startsWith("AUTOMOTIVE") }
                    if (w.isEmpty()) ProbeResult.Fail("일반 카메라 없음")
                    else ProbeResult.Ok(w.joinToString { "#${it.id} ${it.lensFacing} ${it.maxSize}" })
                },
                Candidate("bizrouter.virtual-interior", "AI 가상 실내 컷(표기 필수)", Tier.BIZROUTER) { bizrouter() },
            ),
            Feature.SURROUND to listOf(
                Candidate("vehicle.camera.side", "차량 측면 카메라", Tier.PLATFORM) {
                    val s = cameras.byRole(CameraRole.LEFT) + cameras.byRole(CameraRole.RIGHT)
                    if (s.isEmpty()) ProbeResult.Fail("측면 카메라 없음") else ProbeResult.Ok("${s.size}대")
                },
                Candidate("bizrouter.outpaint", "전후방 합성 + 아웃페인팅", Tier.BIZROUTER) { ProbeResult.Pending("T16") },
                Candidate("bizrouter.image-gen", "비즈라우터 이미지 생성", Tier.BIZROUTER) { bizrouter() },
            ),
            Feature.PERSON_SEGMENTER to listOf(
                Candidate("mediapipe.segmenter", "MediaPipe Image Segmenter", Tier.PLATFORM) { ProbeResult.Pending("T08에서 포함") },
                Candidate("bizrouter.image-edit", "비즈라우터 이미지 편집", Tier.BIZROUTER) { bizrouter() },
            ),
            Feature.SPOTS to listOf(
                Candidate("overpass.tourapi", "Overpass + TourAPI", Tier.PLATFORM) { ProbeResult.Pending("T12") },
                Candidate("bizrouter.text", "비즈라우터 텍스트(명소 추천)", Tier.BIZROUTER) { ProbeResult.Pending("T12") },
                Candidate("fallback.demo-spots", "데모 스팟 목록", Tier.FALLBACK) { ProbeResult.Ok("제주 데모 스팟") },
            ),
            Feature.WEATHER to listOf(
                Candidate("open-meteo", "Open-Meteo", Tier.PLATFORM) { httpProbe("https://api.open-meteo.com/v1/forecast?latitude=33.5&longitude=126.5&daily=sunset&timezone=auto") },
                Candidate("fallback.clock", "시각으로 추정", Tier.FALLBACK) { ProbeResult.Ok("시각 기반") },
            ),
            Feature.MEDIA_COMPOSER to listOf(
                Candidate("media3.transformer", "Media3 Transformer", Tier.PLATFORM) { ProbeResult.Pending("T14에서 포함") },
            ),
            Feature.GENAI to listOf(
                Candidate("bizrouter.genai", "비즈라우터 이미지·영상", Tier.BIZROUTER) { bizrouter() },
                Candidate("fallback.hide-filter", "필터 숨김", Tier.FALLBACK) { ProbeResult.Ok("AI 필터 숨김") },
            ),
            Feature.BGM to listOf(
                Candidate("inapp.cc0", "인앱 CC0 음원", Tier.FALLBACK) { assetsProbe(ctx, "bgm", "CC0 음원") },
            ),
        )
    }

    private fun assetsProbe(ctx: Context, dir: String, what: String): ProbeResult {
        val files = runCatching { ctx.assets.list(dir)?.toList() }.getOrNull().orEmpty()
            .filterNot { it.endsWith(".md") }
        return if (files.isEmpty()) ProbeResult.Pending("$what 미포함 (assets/$dir)") else ProbeResult.Ok("${files.size}개")
    }

    private suspend fun httpProbe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 3_000; readTimeout = 3_000 }
            try {
                if (c.responseCode == 200) ProbeResult.Ok("응답 200") else ProbeResult.Fail("HTTP ${c.responseCode}")
            } finally { c.disconnect() }
        } catch (e: Exception) {
            ProbeResult.Fail("네트워크 실패 (${e.javaClass.simpleName})")
        }
    }
}
