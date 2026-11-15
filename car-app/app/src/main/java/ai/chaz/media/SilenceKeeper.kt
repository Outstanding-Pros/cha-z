package ai.chaz.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 개발용(시연 녹화): 소리 없는 0 샘플을 계속 재생해 오디오 출력 스트림이 끊기지 않게 한다.
 * 에뮬레이터를 wav 오디오 드라이버로 돌리면 조용한 구간을 파일에 쓰지 않아 영상과 시간이 어긋나기 때문.
 * 오디오 포커스는 요청하지 않는다(다른 소리·덕킹에 영향 없음). `--es silence on|off`.
 */
object SilenceKeeper {
    private var track: AudioTrack? = null

    @Synchronized fun set(on: Boolean) {
        if (on == (track != null)) return
        if (!on) { track?.let { runCatching { it.stop() }; it.release() }; track = null; return }
        val n = 48_000
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build().apply { write(ShortArray(n), 0, n); setLoopPoints(0, n, -1); play() }
    }
}
