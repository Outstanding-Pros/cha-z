package ai.chaz.voice

import ai.chaz.R
import ai.chaz.data.CaptureConfig
import ai.chaz.data.CaptureRules
import android.content.Context

/** 안내 문구. 엔진은 이 인터페이스만 보고, 실제 문구는 res/values/strings_tts.xml 에 있다. */
interface TtsPhrases {
    fun captureStart(c: CaptureConfig): String
    /** 주행 중 시작: 포즈·시선 유도 없이 (기획서 '운전자 포즈 유도 금지'). */
    fun captureStartDriving(c: CaptureConfig): String
    fun captureAdjusted(c: CaptureConfig): String
    fun doneDriving(): String
    fun doneParked(): String
    /** n = 저장한 컷 수 ("4장 저장했어요…"). */
    fun doneDriving(n: Int): String = doneDriving()
    fun doneParked(n: Int): String = doneParked()
    fun stopped(): String
    /** 단발: 실내·전방·후방 한 장씩. */
    fun captureSingle(): String = "한 장 찍을게요"
}

class ResourceTtsPhrases(context: Context) : TtsPhrases, VoicePhrases {
    private val res = context.applicationContext.resources
    /** 3초 간격 기본(12초 4컷)이면 "4컷 찍을게요.", 아니면 시간까지. 주행·정차 같은 문구(포즈 지시 없음). */
    private fun start(c: CaptureConfig) =
        if (c.durationSec == c.count * 3) res.getString(R.string.tts_capture_start_short, c.count)
        else res.getString(R.string.tts_capture_start, CaptureRules.formatDuration(c.durationSec), c.count)
    override fun captureStart(c: CaptureConfig) = start(c)
    override fun captureStartDriving(c: CaptureConfig) = start(c)
    override fun captureAdjusted(c: CaptureConfig) = res.getString(R.string.tts_capture_adjusted, CaptureRules.formatDuration(c.durationSec), c.count)
    override fun doneDriving() = res.getString(R.string.tts_done_driving)
    override fun doneParked() = res.getString(R.string.tts_done_parked)
    override fun doneDriving(n: Int) = res.getString(R.string.tts_done_driving_n, n)
    override fun doneParked(n: Int) = res.getString(R.string.tts_done_parked_n, n)
    override fun retake() = res.getString(R.string.tts_retake)
    override fun bgmPlaying(title: String) = res.getString(R.string.tts_bgm_playing, title)
    override fun bgmStopped() = res.getString(R.string.tts_bgm_stopped)
    override fun handoffSending() = res.getString(R.string.tts_handoff_sending)
    override fun handoffNotPaired() = res.getString(R.string.tts_handoff_not_paired)
    override fun stopped() = res.getString(R.string.tts_stopped)
    override fun captureSingle() = res.getString(R.string.tts_capture_single)
    fun albumArrived(n: Int) = res.getString(R.string.tts_album_arrived, n)
    fun albumSent(n: Int) = res.getString(R.string.tts_album_sent, n)
    fun phoneBundle(n: Int) = res.getString(R.string.tts_phone_bundle, n)
    override fun help() = res.getString(R.string.tts_help)
    override fun unknown() = res.getString(R.string.tts_unknown)
    override fun bgmNotReady() = res.getString(R.string.tts_bgm_not_ready)
    override fun phoneNotPaired() = res.getString(R.string.tts_phone_not_paired)
    override fun lightWhileDriving() = res.getString(R.string.tts_light_driving)
    override fun lightNotReady() = res.getString(R.string.tts_light_not_ready)
    override fun sentToPhone() = res.getString(R.string.tts_sent_phone)
    override fun sendFailed() = res.getString(R.string.tts_send_failed)
    override fun noResult() = res.getString(R.string.tts_no_result)
    override fun handoffNotReady() = res.getString(R.string.tts_handoff_not_ready)
    override fun lightChanged(label: String) = res.getString(R.string.tts_light_changed, label)
    fun spotGuide(name: String?) =
        if (name.isNullOrBlank()) res.getString(R.string.tts_spot_generic)
        else res.getString(R.string.tts_spot_guide, name, ai.chaz.spot.SpotGuidePolicy.copula(name))
    fun sunsetGuide() = res.getString(R.string.tts_sunset_guide)
}

/** 테스트용 고정 문구. */
object PlainTtsPhrases : TtsPhrases, VoicePhrases {
    override fun captureStart(c: CaptureConfig) = "start ${c.durationSec}s ${c.count}"
    override fun captureStartDriving(c: CaptureConfig) = "start-driving ${c.durationSec}s ${c.count}"
    override fun captureAdjusted(c: CaptureConfig) = "adjusted ${c.durationSec}s ${c.count}"
    override fun doneDriving() = "done-driving"
    override fun doneParked() = "done-parked"
    override fun stopped() = "stopped"
    override fun help() = "help"
    override fun unknown() = "unknown"
    override fun bgmNotReady() = "bgm-not-ready"
    override fun phoneNotPaired() = "phone-not-paired"
    override fun lightWhileDriving() = "light-driving"
    override fun lightNotReady() = "light-not-ready"
    override fun sentToPhone() = "sent"
    override fun sendFailed() = "send-failed"
    override fun noResult() = "no-result"
    override fun handoffNotReady() = "handoff-not-ready"
    override fun lightChanged(label: String) = "light:$label"
}
