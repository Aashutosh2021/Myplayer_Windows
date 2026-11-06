package androidx.media3.common.audio

import androidx.annotation.CallSuper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Objects

/**
 * Desktop shim for androidx.media3.common.audio.AudioProcessor + BaseAudioProcessor
 * (modeled on media3 1.3.1, the version the Android app depends on).
 * Faithful to the members used by the ported playback/dsp processors; the newer
 * upstream surface (StreamMetadata, flush(StreamMetadata)) that landed after 1.3 is omitted.
 */
@UnstableApi
interface AudioProcessor {

    /** PCM audio format that may be handled by an audio processor. */
    class AudioFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int
    ) {
        constructor(format: Format) : this(format.sampleRate, format.channelCount, format.pcmEncoding)

        companion object {
            /** An [AudioFormat] instance representing an unset format (inactive processor). */
            val NOT_SET: AudioFormat = AudioFormat(Format.NO_VALUE, Format.NO_VALUE, Format.NO_VALUE)
            const val AUDIO_FORMAT_UNSET = Format.NO_VALUE
            const val AUDIO_FORMAT_NOT_SUPPORTED = 0
        }

        /** Bytes used to represent one audio frame, or [Format.NO_VALUE] if not linear PCM. */
        val bytesPerFrame: Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> channelCount * 2
            C.ENCODING_PCM_FLOAT -> channelCount * 4
            else -> Format.NO_VALUE
        }

        override fun toString(): String =
            "AudioFormat[sampleRate=" + sampleRate +
                ", channelCount=" + channelCount +
                ", encoding=" + encoding + "]"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioFormat) return false
            return sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                encoding == other.encoding
        }

        override fun hashCode(): Int = Objects.hash(sampleRate, channelCount, encoding)
    }

    /** Exception thrown when the given [AudioFormat] can not be handled. */
    class UnhandledAudioFormatException(val inputAudioFormat: AudioFormat) :
        Exception("Unhandled input format: $inputAudioFormat")

    companion object {
        /** An empty, direct [ByteBuffer]. */
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    /** Configures the processor for the given input format, returning the output format (or [AudioFormat.NOT_SET]). */
    fun configure(inputAudioFormat: AudioFormat): AudioFormat

    /** Returns whether the processor is configured and will process input buffers. */
    fun isActive(): Boolean

    /** Queues audio data between position and limit of [inputBuffer] for processing. */
    fun queueInput(inputBuffer: ByteBuffer)

    /** Queues an end of stream signal; no input may be queued until after the next [flush]. */
    fun queueEndOfStream()

    /** Returns a buffer containing processed output data between its position and limit. */
    fun getOutput(): ByteBuffer

    /** Returns whether this processor will return no more output until [flush] is called again. */
    fun isEnded(): Boolean

    /** Clears any buffered data and pending output, applying the last configured format. */
    fun flush()

    /** Resets the processor to its unconfigured state, releasing any resources. */
    fun reset()
}

/**
 * Base class for audio processors that keep an output buffer and an internal buffer that is
 * reused whenever input is queued. Subclasses should override [onConfigure] to return the
 * output audio format for the processor if it's active.
 */
@UnstableApi
abstract class BaseAudioProcessor : AudioProcessor {

    /** The current input audio format. */
    protected var inputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    /** The current output audio format. */
    protected var outputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    private var pendingInputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingOutputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    final override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingInputAudioFormat = inputAudioFormat
        pendingOutputAudioFormat = onConfigure(inputAudioFormat)
        return if (isActive()) pendingOutputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean =
        pendingOutputAudioFormat !== AudioProcessor.AudioFormat.NOT_SET

    final override fun queueEndOfStream() {
        inputEnded = true
        onQueueEndOfStream()
    }

    @CallSuper
    override fun getOutput(): ByteBuffer {
        val returnedOutputBuffer = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return returnedOutputBuffer
    }

    @CallSuper
    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    final override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inputAudioFormat = pendingInputAudioFormat
        outputAudioFormat = pendingOutputAudioFormat
        onFlush()
    }

    final override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        onReset()
    }

    /**
     * Replaces the current output buffer with a buffer of at least [size] bytes and returns it.
     * Callers should write to the returned buffer then [flip][ByteBuffer.flip] it so it can be
     * read via [getOutput].
     */
    protected fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
        return buffer
    }

    /** Returns whether the current output buffer has any data remaining. */
    protected fun hasPendingOutput(): Boolean = outputBuffer.hasRemaining()

    /** Called when the processor is configured for a new input format. */
    protected open fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        AudioProcessor.AudioFormat.NOT_SET

    /** Called when the end-of-stream is queued to the processor. */
    protected open fun onQueueEndOfStream() {
        // Do nothing.
    }

    /** Called when the processor is flushed, directly or as part of resetting. */
    protected open fun onFlush() {
        // Do nothing.
    }

    /** Called when the processor is reset. */
    protected open fun onReset() {
        // Do nothing.
    }
}
