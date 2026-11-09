package ai.chaz.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** 음성 안내 어댑터. speak 는 말을 다 마칠 때 돌아온다. 실패하면 예외. */
interface SpeechOutput {
    val name: String
    /** 미리 준비(엔진 워밍업, 문구 사전 합성). 실패해도 예외를 던지지 않는다. */
    suspend fun prepare(phrases: List<String>) = Unit
    suspend fun speak(text: String)
    fun stop()
}

/** 테스트용. 말한 문장을 기록만 한다. */
class FakeSpeechOutput(private val onSpeak: (String) -> Unit = {}) : SpeechOutput {
    override val name = "fake"
    val spoken = mutableListOf<String>()
    var failNext = false
    override suspend fun speak(text: String) {
        if (failNext) { failNext = false; error("fake 실패") }
        spoken += text
        onSpeak(text)
    }
    override fun stop() = Unit
}

/** 안내·효과음용 오디오 포커스 (CLAUDE.md 규칙 7: TTS 는 GAIN_TRANSIENT_MAY_DUCK). */
class AudioFocus(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(ATTRS)
        .build()
    private var holders = 0

    @Synchronized fun acquire() {
        if (holders++ == 0) am?.requestAudioFocus(request)
    }

    @Synchronized fun release() {
        if (holders > 0 && --holders == 0) am?.abandonAudioFocusRequest(request)
    }

    companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
