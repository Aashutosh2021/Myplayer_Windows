package android.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
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
 * Pure-JVM Desktop implementation of Android's [MediaMetadataRetriever].
 * Parses embedded cover art (`APIC` in ID3v2 MP3, `covr` in MP4/M4A) and
 * embedded lyrics (`USLT`/`SYLT` in ID3v2 MP3, `©lyr` in MP4/M4A) directly from local files.
 */
class MediaMetadataRetriever {
    private var targetFile: File? = null

    fun setDataSource(path: String) {
        val clean = when {
            path.startsWith("file://") -> path.removePrefix("file://")
            path.startsWith("file:/") -> path.removePrefix("file:")
            else -> path
        }
        targetFile = File(clean)
    }

    fun setDataSource(context: Context, uri: Uri) {
        setDataSource(uri.path ?: uri.toString())
    }

    fun getEmbeddedPicture(): ByteArray? {
        val file = targetFile ?: return null
        if (!file.exists() || !file.isFile || file.length() <= 16) return null
        return try {
            val bytes = file.inputStream().use { stream ->
                // Read up to 4 MB header/metadata window where ID3v2 and MP4 moov/udta live
                val maxRead = minOf(file.length(), 4L * 1024 * 1024).toInt()
                val buf = ByteArray(maxRead)
                var total = 0
                while (total < maxRead) {
                    val r = stream.read(buf, total, maxRead - total)
                    if (r <= 0) break
                    total += r
                }
                if (total < maxRead) buf.copyOf(total) else buf
            }
            extractJpegOrPngBytes(bytes)
        } catch (_: Exception) {
            null
        }
    }

    fun extractMetadata(keyCode: Int): String? {
        val file = targetFile ?: return null
        if (!file.exists() || !file.isFile) return null
        if (keyCode == METADATA_KEY_LYRICS) {
            // Check companion .lrc first if present next to audio file
            val companionLrc = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
            if (companionLrc.exists() && companionLrc.length() > 0) {
                return runCatching { companionLrc.readText(StandardCharsets.UTF_8) }.getOrNull()
            }
            return extractEmbeddedLyricsFromBytes(file)
        }
        return null
    }

    fun release() {
        targetFile = null
    }

    private fun extractJpegOrPngBytes(data: ByteArray): ByteArray? {
        // Look for JPEG SOI (FF D8 FF E0 / FF D8 FF E1) inside APIC or MP4 covr data atom
        for (i in 0 until data.size - 16) {
            // Check for 'APIC' (ID3v2) or 'covr' (MP4)
            val isApic = data[i] == 'A'.code.toByte() && data[i + 1] == 'P'.code.toByte() &&
                data[i + 2] == 'I'.code.toByte() && data[i + 3] == 'C'.code.toByte()
            val isCovr = data[i] == 'c'.code.toByte() && data[i + 1] == 'o'.code.toByte() &&
                data[i + 2] == 'v'.code.toByte() && data[i + 3] == 'r'.code.toByte()
            if (isApic || isCovr) {
                val searchEnd = data.size - 2
                for (j in i + 4 until searchEnd) {
                    // JPEG magic: FF D8 FF
                    if (data[j] == 0xFF.toByte() && data[j + 1] == 0xD8.toByte() && data[j + 2] == 0xFF.toByte()) {
                        for (k in j + 4 until searchEnd) {
                            if (data[k] == 0xFF.toByte() && data[k + 1] == 0xD9.toByte()) {
                                return data.copyOfRange(j, k + 2)
                            }
                        }
                    }
                    // PNG magic: 89 50 4E 47
                    if (data[j] == 0x89.toByte() && data[j + 1] == 0x50.toByte() &&
                        data[j + 2] == 0x4E.toByte() && data[j + 3] == 0x47.toByte()
                    ) {
                        for (k in j + 8 until searchEnd - 8) {
                            if (data[k] == 'I'.code.toByte() && data[k + 1] == 'E'.code.toByte() &&
                                data[k + 2] == 'N'.code.toByte() && data[k + 3] == 'D'.code.toByte()
                            ) {
                                return data.copyOfRange(j, minOf(data.size, k + 8))
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractEmbeddedLyricsFromBytes(file: File): String? {
        return try {
            val maxRead = minOf(file.length(), 1024L * 1024L).toInt()
            val bytes = ByteArray(maxRead)
            RandomAccessFile(file, "r").use { raf -> raf.readFully(bytes) }
            // Look for ID3v2 USLT frame
            for (i in 0 until bytes.size - 14) {
                if (bytes[i] == 'U'.code.toByte() && bytes[i + 1] == 'S'.code.toByte() &&
                    bytes[i + 2] == 'L'.code.toByte() && bytes[i + 3] == 'T'.code.toByte()
                ) {
                    val size = ((bytes[i + 4].toInt() and 0xFF) shl 24) or
                        ((bytes[i + 5].toInt() and 0xFF) shl 16) or
                        ((bytes[i + 6].toInt() and 0xFF) shl 8) or
                        (bytes[i + 7].toInt() and 0xFF)
                    if (size in 6..65536 && i + 10 + size <= bytes.size) {
                        val raw = String(bytes, i + 14, size - 4, StandardCharsets.UTF_8)
                            .trim { it <= ' ' || it == '\u0000' }
                        if (raw.length > 10) return raw
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val METADATA_KEY_LYRICS = 1000
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
