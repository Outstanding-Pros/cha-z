package ai.chaz.capture

import ai.chaz.data.CaptureRules
import ai.chaz.data.Clip
import ai.chaz.data.Cut
import ai.chaz.data.Session
import ai.chaz.voice.CuePlayer
import ai.chaz.voice.SpeechOutput
import ai.chaz.voice.TtsPhrases
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SPEC F1 기본 흐름 (12초 4컷):
 * 1. 시작 안내 TTS → 2. 끝나면 t=0, 클립 녹화 시작 → 3. 각 컷 직전 "셋, 둘, 하나" → 셔터음 → 모든 소스 동시 캡처
 * 4. t = 총 시간 + 0.5초에 녹화 종료 → 5. 종료 안내 (주행 중/정차 중 문구가 다름)
 * Capturing 중에는 주행 여부와 상관없이 촬영을 이어간다.
 */
class CaptureSessionEngine(
    private val speech: SpeechOutput,
    private val cues: CuePlayer,
    private val store: SessionStore,
    private val phrases: TtsPhrases,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    enum class Phase { ANNOUNCING, RECORDING, FINISHING }

    data class Progress(val phase: Phase, val elapsedMs: Long, val shotsTaken: Int, val total: Int, val countdown: Int?)

    data class Result(val session: Session, val cuts: List<Cut>, val clips: List<Clip>, val stopped: Boolean)

    @Volatile private var loop: Job? = null
    @Volatile private var stopRequested = false

    /** 진행 중 세션을 멈춘다. 이미 찍은 컷은 저장된다. */
    fun stop() {
        stopRequested = true
        loop?.cancel()
        speech.stop()
    }

    suspend fun run(
        session: Session,
        sources: List<CameraSource>,
        adjusted: Boolean,
        isDriving: () -> Boolean,
        onProgress: (Progress) -> Unit = {},
        onShot: (Int) -> Unit = {},
    ): Result = coroutineScope {
        val cfg = session.config
        val interval = cfg.intervalMs
        val cuts = mutableListOf<Cut>()
        val clipFrames = sources.associate { it.role to 0 }.toMutableMap()
        var countdown: Int? = null
        stopRequested = false

        store.begin(session)
        sources.forEach { it.start() }

        onProgress(Progress(Phase.ANNOUNCING, 0, 0, cfg.count, null))
        speakSafely(
            when {
                !cfg.video && cfg.count == 1 -> phrases.captureSingle()
                adjusted -> phrases.captureAdjusted(cfg)
                isDriving() -> phrases.captureStartDriving(cfg) // 주행 중: '웃어 주세요' 같은 포즈 유도 없이
                else -> phrases.captureStart(cfg)
            },
        )

        val t0 = now()
        suspend fun until(t: Long) { val d = t - now(); if (d > 0) delay(d) }

        val job = launch {
            if (stopRequested) return@launch // 시작 안내 중에 멈춘 경우
            // 클립: 0.5초 간격 스냅샷 시퀀스 (에뮬레이터는 동시 녹화가 안 됨 — SPEC F1)
            val clipJob = if (!cfg.video) null else launch {
                var seq = 0
                while (isActive) {
                    for (s in sources) if (store.saveClipFrame(session, s, seq)) clipFrames[s.role] = clipFrames.getValue(s.role) + 1
                    seq++
                    until(t0 + seq * CLIP_FRAME_MS)
                }
            }
            val tickJob = launch {
                while (isActive) {
                    onProgress(Progress(Phase.RECORDING, now() - t0, cuts.size, cfg.count, countdown))
                    delay(TICK_MS)
                }
            }
            val words = CaptureRules.countdownWords(interval)
            for (i in 1..cfg.count) {
                val cutAt = t0 + i * interval
                // 원격 소스(폰)에 셔터 시각을 미리 알린다 — 네트워크 지연이 있어도 그 순간에 찍히게 (T11)
                sources.forEach { it.scheduleShot(i, cutAt - now()) }
                // "하나"(컷 2초 전) → "둘"(1초 전) → 셔터 효과음. 포즈는 지시하지 않는다.
                for (k in 1..words) {
                    until(cutAt - (words - k + 1) * CaptureRules.COUNTDOWN_STEP_MS)
                    countdown = k
                    cues.countdown(k)
                }
                until(cutAt)
                countdown = null
                cues.shutter()
                cuts += store.saveCut(session, i, now() - t0, sources)
                onShot(i)
            }
            if (cfg.video) until(t0 + cfg.durationSec * 1000L + 500)
            clipJob?.cancel()
            tickJob.cancel()
        }
        loop = job
        job.join()
        loop = null
        val stopped = stopRequested || job.isCancelled

        withContext(NonCancellable) {
            onProgress(Progress(Phase.FINISHING, now() - t0, cuts.size, cfg.count, null))
            val durationMs = now() - t0
            val clips = if (!cfg.video) emptyList() else sources.map { s ->
                Clip(session.id, s.role, s.kind, store.clipPath(session, s.role), durationMs, clipFrames.getValue(s.role))
            }
            store.finish(session, cuts, clips, stopped)
            Log.i(TAG, "세션 ${session.id}: 컷 ${cuts.size}/${cfg.count}, 클립 " +
                clips.joinToString { "${it.role}=${it.frameCount}" } + if (stopped) " (중지)" else "")
            speakSafely(
                when {
                    stopped -> phrases.stopped()
                    isDriving() -> phrases.doneDriving(cuts.size)
                    else -> phrases.doneParked(cuts.size)
                },
            )
            Result(session, cuts.toList(), clips, stopped)
        }
    }

    private suspend fun speakSafely(text: String) {
        try {
            speech.speak(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "안내 실패(촬영은 계속): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ChazCapture"
        const val CLIP_FRAME_MS = 500L
        const val TICK_MS = 100L
    }
}
