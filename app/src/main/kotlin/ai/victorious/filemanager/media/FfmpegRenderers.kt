package ai.victorious.filemanager.media

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Builds a [DefaultRenderersFactory] that turns on Media3 extension audio renderers
 * (FFmpeg) **when** the optional `decoder_ffmpeg` module is on the classpath.
 *
 * LGPL compliance: we only enable the extension path; natives must be built with
 * LGPL-safe decoders and dynamic linking — see docs/FFMPEG_MEDIA.md.
 */
@UnstableApi
object FfmpegRenderers {
    /** True when FfmpegAudioRenderer class is present (module + natives packaged). */
    fun available(): Boolean = runCatching {
        Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
    }.isSuccess

    fun factory(context: Context, preferExtension: Boolean = false): DefaultRenderersFactory {
        val mode = when {
            !available() -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            preferExtension -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        return DefaultRenderersFactory(context).setExtensionRendererMode(mode)
    }
}
