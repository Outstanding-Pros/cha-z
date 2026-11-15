package ai.chaz.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 지금 나오는 곡. */
data class BgmTrack(val mood: BgmMood, val variant: Int) {
    val title: String get() = "${mood.label} ${variant + 1}번 곡"
}

/**
 * 차 안 BGM (F4 1차). 음원 파일 없이 BgmSynth 로 8마디를 합성해 AudioTrack 으로 반복 재생 → 차~즈 자체 음원.
 * 오디오 포커스 AUDIOFOCUS_GAIN (규칙 7). TTS·효과음(GAIN_TRANSIENT_MAY_DUCK)이 나오는 동안 작게, 다른 앱이 가져가면 멈춘다.
 */
class BgmPlayer(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var track: AudioTrack? = null
    private val _now = MutableStateFlow<BgmTrack?>(null)
    val now: StateFlow<BgmTrack?> = _now.asStateFlow()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track?.setVolume(0.25f)
                AudioManager.AUDIOFOCUS_GAIN -> track?.setVolume(VOLUME)
                AudioManager.AUDIOFOCUS_LOSS -> stop()
            }
        }.build()

    /** mood 의 곡 재생. next = 지금 곡의 같은 분위기 다음 곡. 재생한 곡, 포커스를 못 얻으면 null. */
    suspend fun play(mood: BgmMood?, next: Boolean): BgmTrack? {
        val cur = _now.value
        val t = when {
            next && cur != null -> BgmTrack(cur.mood, (cur.variant + 1) % BgmSynth.VARIANTS)
            else -> BgmTrack(mood ?: cur?.mood ?: BgmMood.BRIGHT, 0)
        }
        val pcm = withContext(Dispatchers.Default) { BgmSynth.render(t.mood, BgmSynth.loopMs(t.mood), t.variant, loop = true) }
        release()
        if (am?.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { Log.w(TAG, "BGM 포커스 거절"); return null }
        val tr = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(BgmSynth.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build().apply {
                    write(pcm, 0, pcm.size)
                    setLoopPoints(0, pcm.size, -1)
                    setVolume(VOLUME)
                    play()
                }
        }.onFailure { Log.w(TAG, "BGM 재생 실패: ${it.javaClass.simpleName}") }.getOrNull() ?: run { am?.abandonAudioFocusRequest(focus); return null }
        track = tr
        _now.value = t
        Log.i(TAG, "BGM ${t.title}")
        return t
    }

    fun stop() {
        release()
        am?.abandonAudioFocusRequest(focus)
        _now.value = null
    }

    private fun release() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    companion object {
        private const val TAG = "ChazBgm"
        private const val VOLUME = 0.6f

        /** 음성 명령의 분위기 말 → 곡 분위기. */
        fun moodOf(word: String?): BgmMood? = when (word) {
            "신나는", "드라이브" -> BgmMood.BRIGHT
            "잔잔한" -> BgmMood.NIGHT
            "감성" -> BgmMood.OCEAN
            else -> null
        }
    }
}
