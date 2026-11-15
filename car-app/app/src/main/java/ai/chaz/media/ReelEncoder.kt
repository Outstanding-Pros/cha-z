package ai.chaz.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ReelScene 을 MP4(H.264 30fps + AAC 128kbps)로. MediaCodec 입력 Surface 에 Canvas 로 그린다(Media3 의존성 없이).
 * Canvas 로는 프레임 시각을 줄 수 없어서, 인코더가 내보낸 순서대로 1/30초씩 시각을 다시 매긴다(B 프레임 없는 AVC 기준).
 * 기기 인코더가 1080×1920 을 못 하면 720×1280 으로 뽑는다(그림은 같은 비율로 줄여 그림).
 * 소리는 BgmSynth 로 합성한 자체 BGM 을 먼저 AAC 로 인코딩해 두었다가 같이 넣는다.
 */
object ReelEncoder {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    data class Out(val width: Int, val height: Int, val frames: Int, val audio: Boolean)

    fun encode(scene: ReelScene, out: File, withAudio: Boolean = true, onProgress: (Float) -> Unit = {}): Out {
        val audio = if (withAudio) runCatching { Aac.encode(BgmSynth.render(scene.mood, scene.durationMs), BgmSynth.RATE) }
            .onFailure { android.util.Log.w("ChazReel", "BGM 인코딩 실패 → 소리 없이: ${it.javaClass.simpleName}") }.getOrNull() else null
        val codec = MediaCodec.createEncoderByType(MIME)
        val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
        val (w, h) = listOf(ReelSpec.W to ReelSpec.H, 720 to 1280).firstOrNull { (w, h) -> caps?.isSizeSupported(w, h) == true } ?: (720 to 1280)
        val fmt = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w >= 1080) 8_000_000 else 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, ReelSpec.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        out.parentFile?.mkdirs()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val total = (scene.durationMs * ReelSpec.FPS / 1000).toInt()
        val drain = Drain(codec, muxer, audio)
        try {
            for (i in 0 until total) {
                draw(surface, w, h) { c -> scene.draw(c, i * 1000L / ReelSpec.FPS) }
                drain.run(eos = false)
                onProgress((i + 1).toFloat() / total)
            }
            codec.signalEndOfInputStream()
            drain.run(eos = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            surface.release()
            if (drain.started) runCatching { muxer.stop() }
            muxer.release()
        }
        if (drain.written == 0) { out.delete(); error("영상 프레임을 만들지 못했어요") }
        return Out(w, h, drain.written, audio != null)
    }

    private inline fun draw(surface: Surface, w: Int, h: Int, block: (android.graphics.Canvas) -> Unit) {
        val c = runCatching { surface.lockHardwareCanvas() }.getOrElse { surface.lockCanvas(null) }
        try {
            c.save()
            c.scale(w.toFloat() / ReelSpec.W, h.toFloat() / ReelSpec.H)
            block(c)
            c.restore()
        } finally {
            surface.unlockCanvasAndPost(c)
        }
    }

    private class Drain(val codec: MediaCodec, val muxer: MediaMuxer, val audio: Aac.Track?) {
        var track = -1
        var started = false
        var written = 0
        private val info = MediaCodec.BufferInfo()

        fun run(eos: Boolean) {
            var idle = 0
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!eos) return
                        if (++idle > 300) return // 3초 넘게 안 나오면 포기
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        val at = audio?.let { muxer.addTrack(it.format) }
                        muxer.start(); started = true
                        if (audio != null && at != null) {
                            val ai = MediaCodec.BufferInfo()
                            audio.samples.forEach { s ->
                                ai.set(0, s.data.size, s.ptsUs, s.flags)
                                muxer.writeSampleData(at, ByteBuffer.wrap(s.data), ai)
                            }
                        }
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && started) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            info.presentationTimeUs = written * 1_000_000L / ReelSpec.FPS
                            muxer.writeSampleData(track, buf, info)
                            written++
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    /** PCM(모노 16bit) → AAC-LC 샘플 목록. */
    private object Aac {
        class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
        class Track(val format: MediaFormat, val samples: List<Sample>)

        fun encode(pcm: ShortArray, rate: Int): Track {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val fmt = MediaFormat.createAudioFormat(mime, rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(mime)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val out = mutableListOf<Sample>()
            var outFmt: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var eosSent = false
            try {
                var guard = 0
                while (guard++ < 200_000) {
                    if (!eosSent) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            buf.clear(); buf.order(ByteOrder.LITTLE_ENDIAN)
                            val n = minOf(buf.capacity() / 2, 2048, pcm.size - pos)
                            val pts = pos * 1_000_000L / rate
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true
                            } else {
                                buf.asShortBuffer().put(pcm, pos, n)
                                codec.queueInputBuffer(i, 0, n * 2, pts, 0); pos += n
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFmt = codec.outputFormat
                    else if (o >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val b = codec.getOutputBuffer(o)!!
                            b.position(info.offset); b.limit(info.offset + info.size)
                            out += Sample(ByteArray(info.size).also { b.get(it) }, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return Track(outFmt ?: error("AAC 형식 없음"), out)
        }
    }
}
