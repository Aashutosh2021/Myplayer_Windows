package android.media

import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

class AudioAttributes {
    class Builder {
        fun setContentType(contentType: Int): Builder = this
        fun setUsage(usage: Int): Builder = this
        fun build(): AudioAttributes = AudioAttributes()
    }

    companion object {
        const val CONTENT_TYPE_MUSIC = 2
        const val USAGE_MEDIA = 1
    }
}

class AudioFocusRequest {
    class Builder(val focusGain: Int) {
        fun setAudioAttributes(attributes: AudioAttributes): Builder = this
        fun setOnAudioFocusChangeListener(listener: AudioManager.OnAudioFocusChangeListener, handler: Any? = null): Builder = this
        fun build(): AudioFocusRequest = AudioFocusRequest()
    }
}

class AudioManager {
    fun interface OnAudioFocusChangeListener {
        fun onAudioFocusChange(focusChange: Int)
    }

    fun requestAudioFocus(request: AudioFocusRequest): Int = AUDIOFOCUS_REQUEST_GRANTED
    fun requestAudioFocus(listener: OnAudioFocusChangeListener?, streamType: Int, durationHint: Int): Int = AUDIOFOCUS_REQUEST_GRANTED
    fun abandonAudioFocusRequest(request: AudioFocusRequest): Int = AUDIOFOCUS_REQUEST_GRANTED
    fun abandonAudioFocus(listener: OnAudioFocusChangeListener?): Int = AUDIOFOCUS_REQUEST_GRANTED

    companion object {
        const val STREAM_MUSIC = 3
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
        const val AUDIOFOCUS_GAIN = 1
        const val AUDIOFOCUS_LOSS = -1
        const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
    }
}

class AudioFormat {
    var encoding: Int = ENCODING_PCM_16BIT
        private set
    var sampleRate: Int = 48000
        private set
    var channelMask: Int = CHANNEL_OUT_STEREO
        private set

    class Builder {
        private val format = AudioFormat()
        fun setEncoding(encoding: Int): Builder {
            format.encoding = encoding
            return this
        }
        fun setSampleRate(sampleRate: Int): Builder {
            format.sampleRate = sampleRate
            return this
        }
        fun setChannelMask(channelMask: Int): Builder {
            format.channelMask = channelMask
            return this
        }
        fun build(): AudioFormat = format
    }

    companion object {
        const val ENCODING_PCM_16BIT = 2
        const val CHANNEL_OUT_STEREO = 12
        const val CHANNEL_OUT_MONO = 4
    }
}

/**
 * Desktop implementation of Android's [AudioTrack] backed by JavaSound [SourceDataLine].
 * Accepts interleaved 16-bit signed PCM stereo samples from [com.example.myplayer.dualbud.audio.DualChannelMixer]
 * and streams them directly to the Windows / Desktop audio hardware.
 */
class AudioTrack private constructor(
    private val sampleRate: Int,
    private val channels: Int,
    private val bufferSizeBytes: Int
) {
    private var sourceLine: SourceDataLine? = null
    private val writtenFrames = AtomicLong(0L)
    private var reusableByteBuffer = ByteArray(bufferSizeBytes.coerceAtLeast(8192))

    @Volatile private var isReleased: Boolean = false

    init {
        openJavaSoundLine()
    }

    private fun openJavaSoundLine() {
        try {
            val jvmFormat = javax.sound.sampled.AudioFormat(
                sampleRate.toFloat(),
                16,
                channels,
                true,  // signed PCM
                false  // little-endian
            )
            val info = DataLine.Info(SourceDataLine::class.java, jvmFormat)
            if (AudioSystem.isLineSupported(info)) {
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(jvmFormat, bufferSizeBytes.coerceAtLeast(16384))
                sourceLine = line
            }
        } catch (e: Throwable) {
            // Headless environment or no audio output device connected; fallback to virtual frame tracking
            sourceLine = null
        }
    }

    class Builder {
        private var audioFormat: AudioFormat = AudioFormat()
        private var bufferSizeInBytes: Int = 16384

        fun setAudioAttributes(attrs: Any?): Builder = this
        fun setAudioFormat(format: Any?): Builder {
            if (format is AudioFormat) {
                audioFormat = format
            }
            return this
        }
        fun setBufferSizeInBytes(size: Int): Builder {
            bufferSizeInBytes = size
            return this
        }
        fun setTransferMode(mode: Int): Builder = this
        fun build(): AudioTrack {
            val ch = if (audioFormat.channelMask == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
            return AudioTrack(
                sampleRate = audioFormat.sampleRate.coerceAtLeast(8000),
                channels = ch,
                bufferSizeBytes = bufferSizeInBytes.coerceAtLeast(8192)
            )
        }
    }

    @Synchronized
    fun play() {
        if (isReleased) return
        try {
            sourceLine?.start()
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun pause() {
        if (isReleased) return
        try {
            sourceLine?.stop()
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun flush() {
        if (isReleased) return
        try {
            sourceLine?.flush()
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun stop() {
        if (isReleased) return
        try {
            sourceLine?.stop()
            sourceLine?.flush()
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun release() {
        if (isReleased) return
        isReleased = true
        try {
            sourceLine?.stop()
            sourceLine?.flush()
            sourceLine?.close()
        } catch (_: Throwable) {
        } finally {
            sourceLine = null
        }
    }

    @Synchronized
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, writeMode: Int = 0): Int {
        if (sizeInBytes <= 0 || isReleased) return 0
        val line = sourceLine
        if (line != null && line.isOpen) {
            try {
                val written = line.write(audioData, offsetInBytes, sizeInBytes)
                writtenFrames.addAndGet((written / (channels * 2)).toLong())
                return written
            } catch (_: Throwable) {}
        }
        writtenFrames.addAndGet((sizeInBytes / (channels * 2)).toLong())
        return sizeInBytes
    }

    @Synchronized
    fun write(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, writeMode: Int = 0): Int {
        if (sizeInShorts <= 0 || isReleased) return 0
        val line = sourceLine
        if (line != null && line.isOpen) {
            try {
                val byteCount = sizeInShorts * 2
                if (reusableByteBuffer.size < byteCount) {
                    reusableByteBuffer = ByteArray(byteCount)
                }
                var byteIdx = 0
                val end = (offsetInShorts + sizeInShorts).coerceAtMost(audioData.size)
                for (i in offsetInShorts until end) {
                    val sample = audioData[i].toInt()
                    reusableByteBuffer[byteIdx++] = (sample and 0xFF).toByte()
                    reusableByteBuffer[byteIdx++] = ((sample shr 8) and 0xFF).toByte()
                }
                val bytesWritten = line.write(reusableByteBuffer, 0, byteIdx)
                val shortsWritten = bytesWritten / 2
                writtenFrames.addAndGet((shortsWritten / channels).toLong())
                return shortsWritten
            } catch (_: Throwable) {}
        }
        writtenFrames.addAndGet((sizeInShorts / channels).toLong())
        return sizeInShorts
    }

    val playbackHeadPosition: Int
        get() {
            val line = sourceLine
            if (line != null && line.isOpen) {
                return line.longFramePosition.toInt()
            }
            return writtenFrames.get().toInt()
        }

    companion object {
        const val WRITE_BLOCKING = 0
        const val MODE_STREAM = 1

        @JvmStatic
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int {
            val channels = if (channelConfig == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
            // ~50ms minimum stereo buffer
            return (sampleRateInHz / 20) * channels * 2
        }
    }
}
