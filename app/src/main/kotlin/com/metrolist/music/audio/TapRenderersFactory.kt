package com.metrolist.music.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class TapRenderersFactory(private val appContext: Context) : DefaultRenderersFactory(appContext) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val chain = DefaultAudioSink.DefaultAudioProcessorChain(PcmTapAudioProcessor(port = 9877))
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(chain)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
