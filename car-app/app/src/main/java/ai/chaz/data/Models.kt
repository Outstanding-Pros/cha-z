package ai.chaz.data

import ai.chaz.capture.CameraRole
import ai.chaz.capture.SourceKind

/** 촬영 설정. 간격 = durationSec / count. */
data class CaptureConfig(val durationSec: Int = 12, val count: Int = 4) {
    val intervalMs: Long get() = durationSec * 1000L / count
}

/** SPEC F1 '촬영 설정 제약'. 범위를 벗어나면 가장 가까운 값으로 맞추고 무엇을 바꿨는지 알려 준다. */
object CaptureRules {
    const val MIN_COUNT = 1
    const val MAX_COUNT = 20
    const val MIN_DURATION = 4
    const val MAX_DURATION = 120
    const val MIN_INTERVAL_MS = 2_000L
    const val COUNTDOWN_STEP_MS = 900L // assets/tts/cd_*.mp3 길이(약 0.9초)

    data class Normalized(val config: CaptureConfig, val adjusted: Boolean)

    fun normalize(requested: CaptureConfig): Normalized {
        var count = requested.count.coerceIn(MIN_COUNT, MAX_COUNT)
        var duration = requested.durationSec.coerceIn(MIN_DURATION, MAX_DURATION)
        if (duration * 1000L / count < MIN_INTERVAL_MS) {
            // 시간을 먼저 늘려 보고, 상한에 걸리면 컷 수를 줄인다
            duration = (count * 2).coerceIn(MIN_DURATION, MAX_DURATION)
            if (duration * 1000L / count < MIN_INTERVAL_MS) count = duration / 2
        }
        val c = CaptureConfig(duration, count)
        return Normalized(c, c != requested)
    }

    /** 간격 3초 미만이면 "둘, 하나"로 줄인다. */
    fun countdownWords(intervalMs: Long): Int = if (intervalMs >= 3_000) 3 else 2

    /** TTS용 시간 표현. 12 → "12초", 60 → "1분", 90 → "1분 30초". */
    fun formatDuration(sec: Int): String = when {
        sec < 60 -> "${sec}초"
        sec % 60 == 0 -> "${sec / 60}분"
        else -> "${sec / 60}분 ${sec % 60}초"
    }
}

enum class TriggerSource { WAKE_WORD, VOICE_BUTTON, SPOT_GUIDE, APP_LAUNCH, SHUTTER_BUTTON }

enum class SessionStatus { RAW, EDITED, DELIVERED }

data class Frame(val role: CameraRole, val path: String, val source: SourceKind)

/** 같은 시각의 실내·전방·후방(·측면) 프레임. tMs 는 녹화 시작(t=0) 기준. */
data class Cut(val sessionId: String, val index: Int, val tMs: Long, val frames: List<Frame>)

/** 영상 클립. 동시 녹화가 안 되는 환경이라 지금은 0.5초 간격 스냅샷 시퀀스 폴더(path)다 (SPEC F1). */
data class Clip(val sessionId: String, val role: CameraRole, val source: SourceKind, val path: String, val durationMs: Long, val frameCount: Int)

/**
 * 촬영 세션. 위치·스팟·맥락·BGM 은 T12~T13 에서 채운다.
 * TODO(T12): location, spotName, tripId / TODO(T13): CaptureContext, bgmTrackId
 */
data class Session(
    val id: String,
    val startedAt: Long,
    val config: CaptureConfig,
    val trigger: TriggerSource,
    val status: SessionStatus = SessionStatus.RAW,
)
