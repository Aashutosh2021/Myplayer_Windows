package androidx.media3.exoplayer.audio

import androidx.media3.common.*
import java.nio.ByteBuffer

interface AudioSink {
    interface Listener

    fun setListener(listener: Listener) {}
    fun supportsFormat(format: Format): Boolean = true
    fun getFormatSupport(format: Format): Int = SINK_FORMAT_SUPPORTED_DIRECTLY
    fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
    fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {}
    fun play() {}
    fun handleBuffer(
        buffer: ByteBuffer,
        bufferPresentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean = true
    fun playToEndOfStream() {}
    fun isEnded(): Boolean = false
    fun hasPendingData(): Boolean = false
    fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters()
    fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    fun getAudioAttributes(): AudioAttributes? = null
    fun setAudioSessionId(audioSessionId: Int) {}
    fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    fun enableTunnelingV21() {}
    fun disableTunneling() {}
    fun setVolume(volume: Float) {}
    fun pause() {}
    fun flush() {}
    fun reset() {}
    fun release() {}
    fun handleDiscontinuity() {}
    fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    fun getSkipSilenceEnabled(): Boolean = false
    fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) {}
    fun setClock(clock: androidx.media3.common.util.Clock) {}
    fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {}

    companion object {
        const val SINK_FORMAT_SUPPORTED_DIRECTLY = 2
        const val SINK_FORMAT_UNSUPPORTED = 0
    }
}
