package ai.chaz.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.SoundPool
import android.media.ToneGenerator
import android.util.Log

/**
 * 시각이 정확해야 하는 짧은 소리: 카운트다운("셋, 둘, 하나")과 셔터음.
 * 실시간 TTS 는 지연이 들쭉날쭉해서 미리 만든 클립(assets/tts/cd_*.mp3)을 SoundPool 로 바로 재생한다.
 */
interface CuePlayer {
    /** n = 3, 2, 1 */
    fun countdown(n: Int)
    fun shutter()
    /** 호출어 감지·음성 버튼: 짧은 효과음 후 명령을 듣는다. */
    fun listening() = Unit
    fun release() = Unit
}

class FakeCuePlayer(private val log: MutableList<String> = mutableListOf()) : CuePlayer {
    val events: List<String> get() = log
    override fun countdown(n: Int) { log += "cd$n" }
    override fun shutter() { log += "shutter" }
    override fun listening() { log += "listening" }
}

class SoundPoolCuePlayer(context: Context) : CuePlayer {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        ).build()
    private val ids = mutableMapOf<Int, Int>()
    private val shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }

    init {
        for (n in 1..3) {
            runCatching { context.assets.openFd("tts/cd_$n.mp3").use { ids[n] = pool.load(it, 1) } }
                .onFailure { Log.w("ChazCue", "카운트다운 클립 없음(cd_$n) → 비프음으로 대체") }
        }
    }

    override fun countdown(n: Int) {
        val id = ids[n]
        if (id != null) pool.play(id, 1f, 1f, 1, 0, 1f)
        else tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    override fun shutter() = shutterSound.play(MediaActionSound.SHUTTER_CLICK)

    override fun listening() { tone.startTone(ToneGenerator.TONE_PROP_ACK, 120) }

    override fun release() {
        pool.release()
        shutterSound.release()
    }
}
