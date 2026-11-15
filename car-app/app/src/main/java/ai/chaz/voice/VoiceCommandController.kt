package ai.chaz.voice

import ai.chaz.data.CaptureConfig
import ai.chaz.data.TriggerSource
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 음성 명령이 실제로 하는 일. CaptureController 를 감싼 어댑터 (테스트에서 가짜로 바꾼다). */
interface CommandSink {
    val capturing: Boolean
    val lastConfig: CaptureConfig
    /** '차즈'만 말했을 때 쓰는 설정 = 대기 화면에서 고른 촬영 방식 (단발·동영상). */
    val defaultConfig: CaptureConfig get() = CaptureConfig()
    val driving: Boolean
    fun startCapture(trigger: TriggerSource, config: CaptureConfig)
    fun stopCapture()
    val phonePaired: Boolean
    /** 마지막 완성본(없으면 기본 레이아웃으로 만들어)을 폰으로 보낸다. */
    suspend fun sendToPhone(): SendOutcome
    /** 정차 중 조명 바꾸기. 바꾼 프리셋 이름, 못 바꾸면 null. */
    suspend fun changeLight(preset: String?): String?

    // ---- 스타일 기획서 '촬영 중 음성 명령' (다음 찰칵 직후 처리) ----
    /** 다음 셔터(또는 촬영 끝)까지 기다린다. */
    suspend fun awaitNextShot() {}
    /** 촬영이 끝날 때까지 기다린다. */
    suspend fun awaitCaptureEnd() {}
    /** BGM 재생. next = 지금 곡의 다음 곡. 재생한 곡 이름, 못 틀면 null. */
    suspend fun playBgm(mood: String?, next: Boolean): String? = null
    val bgmPlaying: Boolean get() = false
    fun stopBgm() {}
    /** 촬영 중 "휴대폰 편집": 이 촬영이 끝나면 폰으로 보내도록 표시. 폰이 연결돼 있으면 true. */
    fun handoffAfterCapture(): Boolean = false
    /** 방금 찍은 촬영(세션 원본)을 폰으로. */
    suspend fun handoffLatest(): SendOutcome = SendOutcome.NO_RESULT
}

enum class SendOutcome { SENT, NO_RESULT, FAILED }

/** 명령 응답 문구 (res/values/strings_tts.xml). */
interface VoicePhrases {
    fun help(): String
    fun unknown(): String
    fun bgmNotReady(): String
    fun phoneNotPaired(): String
    fun lightWhileDriving(): String
    fun lightNotReady(): String
    fun sentToPhone(): String
    fun sendFailed(): String
    fun noResult(): String
    fun handoffNotReady(): String
    fun lightChanged(label: String): String
    fun retake(): String = "다시 찍을게요."
    fun bgmPlaying(title: String): String = "$title 틀어 드릴게요."
    fun bgmStopped(): String = "노래를 껐어요."
    fun handoffSending(): String = "휴대폰으로 보낼게요."
    fun handoffNotPaired(): String = "정차 후 화면에서 휴대폰 편집을 눌러 주세요."
}

/**
 * 호출어 감지 → 명령 인식 → 인텐트 실행 (SPEC '음성 인터페이스').
 * - 호출어: 연속 인식(wakeInput, Pleos 온디바이스 STT)에서 "차즈"를 찾는다. 한 문장에 명령이 같이 있으면 바로 실행.
 * - "차즈"만 들리면 효과음 → 최대 5초 명령을 듣고, 1.5초 안에 말이 없으면 기본 촬영.
 * - 마지막 세션 종료 후 3초는 호출어를 무시한다. 촬영 중에는 STOP 만 받는다.
 * - 규칙 파서가 모르면 LLM(2초 제한) → 그래도 모르면 "다시 말씀해 주세요."
 */
class VoiceCommandController(
    private val scope: CoroutineScope,
    private val sink: CommandSink,
    private val speech: SpeechOutput,
    private val input: SpeechInput,
    private val wakeInput: () -> SpeechInput?,
    private val cues: CuePlayer,
    private val phrases: VoicePhrases,
    private val llm: suspend (String) -> Intent,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val _listening = MutableStateFlow(false)
    /** 마이크가 열려 있는지 — 화면에 마이크 표시 (SPEC). */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _lastHeard = MutableStateFlow<String?>(null)
    val lastHeard: StateFlow<String?> = _lastHeard.asStateFlow()

    private val micLock = Mutex()
    @Volatile private var paused = false
    @Volatile var ignoreWakeUntil = 0L
    /** 스팟 가이드 직후 15초: 이 안에 '차즈'면 T3(스팟 가이드 수락)으로 기록. */
    @Volatile var spotWindowUntil = 0L
    private fun wakeTrigger() = if (now() < spotWindowUntil) TriggerSource.SPOT_GUIDE else TriggerSource.WAKE_WORD
    private var wakeJob: Job? = null

    val wakeRunning: Boolean get() = wakeJob?.isActive == true

    /** 앱이 화면에 있을 때만 돈다 (백그라운드 호출어 B2 는 미검증). */
    fun startWakeLoop() {
        if (wakeRunning) return
        val mic = wakeInput() ?: return
        wakeJob = scope.launch {
            _listening.value = true
            try {
                while (isActive) {
                    val text = micLock.withLock {
                        if (paused) null
                        else try {
                            mic.listen(maxMs = 10_000, noSpeechMs = 10_000)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "호출어 인식 오류: ${e.message}")
                            delay(2_000)
                            null
                        }
                    }
                    if (text != null) onHeard(text)
                    else if (paused) delay(200)
                }
            } finally {
                _listening.value = false
            }
        }
    }

    fun stopWakeLoop() {
        wakeJob?.cancel()
        wakeJob = null
        wakeInput()?.cancel()
    }

    /** T2: 음성 버튼 1회 탭 후 명령 (호출어 없이). */
    fun voiceButton() {
        scope.launch {
            paused = true
            wakeInput()?.cancel()
            try {
                val text = micLock.withLock {
                    _listening.value = true
                    cues.listening()
                    try { input.listen(maxMs = 5_000, noSpeechMs = 4_000) } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "명령 인식 실패: ${e.message}"); null
                    } finally {
                        _listening.value = wakeRunning
                    }
                }
                if (text == null) speakSafely(phrases.unknown())
                else handleText(text, requireWake = false)
            } finally {
                paused = false
            }
        }
    }

    /** 연속 인식에서 한 문장이 들렸을 때. */
    suspend fun onHeard(text: String) {
        val p = IntentParser.parse(text)
        if (!p.wake) return
        if (now() < ignoreWakeUntil) { Log.i(TAG, "세션 종료 직후 3초 — 호출어 무시"); return }
        _lastHeard.value = text
        if (p.intent == Intent.CaptureDefault && p.command.isEmpty() && !sink.capturing) {
            // "차즈"만: 효과음 → 명령 대기. 1.5초 안에 말이 없으면 기본 촬영
            cues.listening()
            val follow = try { input.listen(maxMs = 5_000, noSpeechMs = 1_500) } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (follow == null) execute(Intent.CaptureDefault, wakeTrigger())
            else handleText(follow, requireWake = false)
            return
        }
        execute(resolve(p), wakeTrigger())
    }

    /** 텍스트로 명령 주입 (음성 버튼 결과, 디버그 패널, adb). */
    suspend fun handleText(text: String, requireWake: Boolean) {
        val p = IntentParser.parse(text)
        _lastHeard.value = text
        if (requireWake && !p.wake) return
        execute(resolve(p), if (p.wake) wakeTrigger() else TriggerSource.VOICE_BUTTON)
    }

    private suspend fun resolve(p: Parsed): Intent =
        if (p.intent != Intent.Unknown || p.command.isEmpty()) p.intent
        else llm(p.command).also { Log.i(TAG, "LLM 폴백 → $it") }

    suspend fun execute(intent: Intent, trigger: TriggerSource) {
        Log.i(TAG, "인텐트 $intent")
        if (sink.capturing) {
            duringCapture(intent)
            return
        }
        when (intent) {
            Intent.CaptureDefault -> sink.startCapture(trigger, sink.defaultConfig)
            is Intent.CaptureCustom -> sink.startCapture(trigger, fill(intent))
            Intent.Retake -> { speakSafely(phrases.retake()); sink.startCapture(trigger, sink.lastConfig) }
            Intent.Stop -> Unit
            is Intent.BgmPlay -> speakSafely(
                sink.playBgm(intent.mood, next = intent.mood == null && sink.bgmPlaying)?.let { phrases.bgmPlaying(it) } ?: phrases.bgmNotReady(),
            )
            Intent.BgmStop -> { sink.stopBgm(); speakSafely(phrases.bgmStopped()) }
            Intent.SendPhone -> speakSafely(
                if (!sink.phonePaired) phrases.phoneNotPaired()
                else when (sink.sendToPhone()) {
                    SendOutcome.SENT -> phrases.sentToPhone()
                    SendOutcome.NO_RESULT -> phrases.noResult()
                    SendOutcome.FAILED -> phrases.sendFailed()
                },
            )
            Intent.HandoffPhone -> speakSafely(
                if (!sink.phonePaired) phrases.handoffNotPaired()
                else when (sink.handoffLatest()) {
                    SendOutcome.SENT -> phrases.handoffSending()
                    SendOutcome.NO_RESULT -> phrases.noResult()
                    SendOutcome.FAILED -> phrases.sendFailed()
                },
            )
            is Intent.LightMood -> speakSafely(
                if (sink.driving) phrases.lightWhileDriving()
                else sink.changeLight(intent.preset)?.let { phrases.lightChanged(it) } ?: phrases.lightNotReady(),
            )
            Intent.Help -> speakSafely(phrases.help())
            Intent.Unknown -> speakSafely(phrases.unknown())
        }
    }

    /**
     * 촬영 중 명령 (스타일 기획서): 카운트다운 리듬을 깨지 않게 다음 찰칵 직후 처리하고, 말 대신 짧은 응답음.
     * 노래 바꿔줘 = 다음 BGM / 휴대폰 편집 = 끝나면 폰으로 / 그만 = 찍은 컷까지 저장 / 다시 = 멈추고 같은 설정으로 재촬영.
     */
    private suspend fun duringCapture(intent: Intent) {
        when (intent) {
            Intent.Stop -> { sink.awaitNextShot(); sink.stopCapture() }
            Intent.Retake -> {
                sink.awaitNextShot(); sink.stopCapture(); sink.awaitCaptureEnd()
                speakSafely(phrases.retake())
                sink.startCapture(TriggerSource.VOICE_BUTTON, sink.lastConfig)
            }
            is Intent.BgmPlay -> {
                sink.awaitNextShot()
                if (sink.playBgm(intent.mood, next = intent.mood == null && sink.bgmPlaying) != null) cues.listening()
            }
            Intent.BgmStop -> { sink.awaitNextShot(); sink.stopBgm(); cues.listening() }
            Intent.HandoffPhone, Intent.SendPhone -> {
                sink.awaitNextShot()
                // 응답은 촬영이 끝난 뒤 (카운트다운과 겹치지 않게) — 연결 안 됐으면 그때 안내
                val paired = sink.handoffAfterCapture()
                cues.listening()
                if (!paired) { sink.awaitCaptureEnd(); speakSafely(phrases.handoffNotPaired()) }
            }
            else -> Unit // 촬영 중 다른 명령은 무시
        }
    }

    private suspend fun speakSafely(text: String) {
        try { speech.speak(text) } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "응답 안내 실패: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ChazVoice"
        const val DEFAULT_INTERVAL_SEC = 3

        /** 시간만 말하면 3초 간격으로 컷 수를, 장수만 말하면 3초 간격으로 시간을 채운다. */
        fun fill(c: Intent.CaptureCustom): CaptureConfig {
            val d = c.durationSec ?: ((c.count ?: 4) * DEFAULT_INTERVAL_SEC)
            val n = c.count ?: (d / DEFAULT_INTERVAL_SEC).coerceAtLeast(1)
            return CaptureConfig(d, n)
        }
    }
}
