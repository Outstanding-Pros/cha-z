package ai.chaz.voice

import ai.chaz.R
import ai.chaz.data.CaptureConfig
import ai.chaz.data.CaptureRules
import android.content.Context

/** 안내 문구. 엔진은 이 인터페이스만 보고, 실제 문구는 res/values/strings_tts.xml 에 있다. */
interface TtsPhrases {
    fun captureStart(c: CaptureConfig): String
    fun captureAdjusted(c: CaptureConfig): String
    fun doneDriving(): String
    fun doneParked(): String
    fun stopped(): String
}

class ResourceTtsPhrases(context: Context) : TtsPhrases {
    private val res = context.applicationContext.resources
    override fun captureStart(c: CaptureConfig) = res.getString(R.string.tts_capture_start, CaptureRules.formatDuration(c.durationSec), c.count)
    override fun captureAdjusted(c: CaptureConfig) = res.getString(R.string.tts_capture_adjusted, CaptureRules.formatDuration(c.durationSec), c.count)
    override fun doneDriving() = res.getString(R.string.tts_done_driving)
    override fun doneParked() = res.getString(R.string.tts_done_parked)
    override fun stopped() = res.getString(R.string.tts_stopped)
}

/** 테스트용 고정 문구. */
object PlainTtsPhrases : TtsPhrases {
    override fun captureStart(c: CaptureConfig) = "start ${c.durationSec}s ${c.count}"
    override fun captureAdjusted(c: CaptureConfig) = "adjusted ${c.durationSec}s ${c.count}"
    override fun doneDriving() = "done-driving"
    override fun doneParked() = "done-parked"
    override fun stopped() = "stopped"
}
