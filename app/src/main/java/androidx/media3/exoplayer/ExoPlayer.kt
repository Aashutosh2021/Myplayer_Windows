package androidx.media3.exoplayer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.Player

open class ExoPlayer : Player {
    class Builder(val context: Context, val renderersFactory: Any? = null) {
        fun setAudioAttributes(attrs: AudioAttributes, handleAudioFocus: Boolean = false): Builder = this
        fun setHandleAudioBecomingNoisy(handle: Boolean): Builder = this
        fun setWakeMode(wakeMode: Int): Builder = this
        fun setLoadControl(loadControl: Any): Builder = this
        fun setMediaSourceFactory(factory: Any): Builder = this
        fun setRenderersFactory(factory: Any): Builder = this
        fun build(): ExoPlayer = ExoPlayer()
    }
}

open class DefaultRenderersFactory(val context: Context) {
    open fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): androidx.media3.exoplayer.audio.AudioSink? = null
}

class DefaultLoadControl {
    class Builder {
        fun setBufferDurationsMs(
            minBufferMs: Int,
            maxBufferMs: Int,
            bufferForPlaybackMs: Int,
            bufferForPlaybackAfterRebufferMs: Int
        ): Builder = this
        fun setBackBuffer(backBufferDurationMs: Int, retainBackBufferFromKeyframe: Boolean): Builder = this
        fun build(): DefaultLoadControl = DefaultLoadControl()
    }
}
