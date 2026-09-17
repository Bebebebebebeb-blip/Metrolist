package com.metrolist.music.audio

import android.content.Context
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Drop-in replacement for DefaultRenderersFactory that adds
 * PcmTapAudioProcessor into the audio sink's processor chain.
 *
 * Wherever Metrolist currently builds its ExoPlayer instance
 * (look for `ExoPlayer.Builder(` in the codebase - likely in the
 * playback service, e.g. MusicService.kt), swap:
 *
 *   ExoPlayer.Builder(context)
 *       .setRenderersFactory(DefaultRenderersFactory(context))
 *       ...
 *
 * for:
 *
 *   ExoPlayer.Builder(context)
 *       .setRenderersFactory(TapRenderersFactory(context))
 *       ...
 *
 * Everything else about playback stays exactly as it was - this
 * only adds a tee of the raw PCM to a local socket.
 */
class TapRenderersFactory(private val appContext: Context) : DefaultRenderersFactory(appContext) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val chain = AudioProcessorChain { _ ->
            arrayOf(PcmTapAudioProcessor(port = 9877))
        }
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(chain)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
