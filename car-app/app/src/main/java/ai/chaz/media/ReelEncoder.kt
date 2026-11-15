package ai.chaz.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * ReelRenderer 를 MP4(H.264, 30fps)로. MediaCodec 입력 Surface 에 Canvas 로 그린다(Media3 의존성 없이).
 * Canvas 로는 프레임 시각을 줄 수 없어서, 인코더가 내보낸 순서대로 1/30초씩 시각을 다시 매긴다(B 프레임 없는 AVC 기준).
 * 기기 인코더가 1080×1920 을 못 하면 720×1280 으로 뽑는다(그림은 같은 비율로 줄여 그림).
 */
object ReelEncoder {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    /** 실제로 뽑은 크기. */
    data class Out(val width: Int, val height: Int, val frames: Int)

    fun encode(renderer: ReelRenderer, out: File, onProgress: (Float) -> Unit = {}): Out {
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
        val total = (renderer.durationMs * ReelSpec.FPS / 1000).toInt()
        val drain = Drain(codec, muxer)
        try {
            for (i in 0 until total) {
                draw(surface, w, h) { c -> renderer.draw(c, i * 1000L / ReelSpec.FPS) }
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
        return Out(w, h, drain.written)
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

    private class Drain(val codec: MediaCodec, val muxer: MediaMuxer) {
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
                        muxer.start(); started = true
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
}
