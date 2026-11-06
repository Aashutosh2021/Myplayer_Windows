package com.example.myplayer.myplayerfx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.example.myplayer.di.AppContainer
import com.example.myplayer.myplayerfx.audio.LoudnessAudioProcessor
import com.example.myplayer.myplayerfx.audio.ReplayGainTags
import com.example.myplayer.myplayerfx.audio.SpatialAudioProcessor
import com.example.myplayer.myplayerfx.audio.ReverbAudioProcessor
import com.example.myplayer.playback.dsp.MasterAudioDspProcessor
import com.example.myplayer.playback.dsp.SpatialAudioProcessor as SurroundSpatialAudioProcessor
import com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Desktop replacement for Android's ExoPlayer `DefaultAudioSink` DSP chain (`di/PlayerModule.kt`).
 *
 * Runs the full Android DSP pipeline over libvlc's callback audio output (`amem`):
 * 1. [MasterAudioDspProcessor] (15-Band ISO Parametric EQ, Studio Master Clarity, Sub-Bass, Soft-Clip Limiter)
 * 2. [SurroundSpatialAudioProcessor] (Dolby Atmos 5.1/7.1 Binaural Virtualizer, Brown & Duda 3D Virtual Speakers, BS2B Crossfeed)
 * 3. [SpatialAudioProcessor] (Audio Lab 3D Soundstage)
 * 4. [ReverbAudioProcessor] (Audio Lab Freeverb Room Reverb)
 * 5. [LoudnessAudioProcessor] (Audio Lab Loudness Boost)
 * 6. Exponential Volume Curve + ReplayGain normalization stage with soft-knee anti-clipping limiter
 */
object DesktopMyPlayerDspPipeline {

    /** True when VLC's audio callback output is live; falls back to VLC's own native output otherwise. */
    @get:Synchronized
    var isCallbackMode: Boolean = false
        private set

    /** Flips the volume/equalizer paths once the callback output is fully wired. */
    @Synchronized
    fun markCallbackMode() {
        isCallbackMode = true
    }

    @Volatile private var installed = false
    @Volatile private var fx: AudioFxService? = null
    @Volatile private var chain: List<AudioProcessor> = emptyList()
    private var configApplied = false
    private var configuredSampleRate = 0
    private var configuredChannels = 0
    private var replayGain = ReplayGainTags()

    // Reusable direct buffer to eliminate per-callback GC churn & direct memory fragmentation
    private var stagingBuffer: ByteBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())
    private val dspProcessLock = Any()

    @Volatile private var volumeGain = 1f
    @Volatile private var lastVolume = 1f

    /** Mirrors Android PlayerModule.provideExoPlayer: attach the bridge before any UI runs. */
    @Synchronized
    fun install() {
        install(
            AppContainer.resolveDependency(AudioFxService::class.java) as? AudioFxService,
            AppContainer.resolveDependency(MasterAudioDspProcessor::class.java) as? MasterAudioDspProcessor,
            AppContainer.resolveDependency(SurroundSpatialAudioProcessor::class.java) as? SurroundSpatialAudioProcessor
        )
    }

    /** install() overload so the DSP chain can also run outside AppContainer (smoke tests). */
    @Synchronized
    fun install(
        audioFx: AudioFxService?,
        master: MasterAudioDspProcessor?,
        surroundSpatial: SurroundSpatialAudioProcessor? = null
    ) {
        if (installed) return
        installed = true
        try {
            fx = audioFx
            chain = buildChain(audioFx, master, surroundSpatial)
            println("[DesktopMyPlayerDspPipeline] installed ${chain.size} DSP stage(s); audioFx=${audioFx != null}, master=${master != null}, spatial=${surroundSpatial != null}")
            if (audioFx != null) {
                MyPlayerAudioFxDspBridge.attach(audioFx)
            }
        } catch (t: Throwable) {
            println("[DesktopMyPlayerDspPipeline] install failed: ${t.message}")
            if (chain.isEmpty()) chain = emptyList()
        }
    }

    private fun buildChain(
        audioFx: AudioFxService?,
        master: MasterAudioDspProcessor?,
        surroundSpatial: SurroundSpatialAudioProcessor?
    ): List<AudioProcessor> = buildList {
        if (master != null) add(master)
        if (surroundSpatial != null) add(surroundSpatial)
        val fx = audioFx ?: return@buildList
        add(SpatialAudioProcessor(fx))
        add(ReverbAudioProcessor(fx))
        add(LoudnessAudioProcessor(fx))
    }

    /** Declares the shared PCM format and re-configures every processor for a new stream. */
    @Synchronized
    fun beginStream(sampleRate: Int, channels: Int) {
        if (!installed) install()
        val formatChanged = configuredSampleRate != sampleRate || configuredChannels != channels
        val format = AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
        for (processor in chain) {
            if (formatChanged || !configApplied || !processor.isActive()) {
                processor.flush()
                processor.configure(format)
                processor.flush()
            }
        }
        configuredSampleRate = sampleRate
        configuredChannels = channels
        configApplied = true
        computeGain()
    }

    /** Track change: drop the processors' buffered state so reverb tails don't bleed across songs. */
    @Synchronized
    fun onTrackChange() {
        synchronized(dspProcessLock) {
            for (processor in chain) {
                if (processor.isActive()) {
                    processor.flush()
                }
            }
        }
    }

    /**
     * Processes one interleaved S16N block (VLC native byte order) and returns a frame-aligned
     * PCM byte array ready for JavaSound `SourceDataLine.write`.
     */
    fun process(input: ByteArray, byteCount: Int): ByteArray {
        val safeBytes = (minOf(input.size, byteCount) / 4) * 4
        if (safeBytes <= 0) return ByteArray(0)

        computeGain()
        synchronized(dspProcessLock) {
            if (stagingBuffer.capacity() < safeBytes) {
                stagingBuffer = ByteBuffer.allocateDirect(safeBytes * 2).order(ByteOrder.nativeOrder())
            }
            stagingBuffer.clear()
            stagingBuffer.put(input, 0, safeBytes)
            stagingBuffer.flip()

            var buffer: ByteBuffer = stagingBuffer
            for (processor in chain) {
                if (!processor.isActive()) continue
                val currentRemaining = buffer.remaining()
                if (currentRemaining <= 0) break
                try {
                    processor.queueInput(buffer)
                    val outBuf = processor.getOutput()
                    if (outBuf.remaining() > 0) {
                        outBuf.order(ByteOrder.nativeOrder())
                        buffer = outBuf
                    } else {
                        // Processor did not emit output; rewind previous buffer so audio never drops out
                        buffer.position(0)
                    }
                } catch (t: Throwable) {
                    println("[DesktopMyPlayerDspPipeline] ${processor.javaClass.simpleName} threw: ${t.message}")
                    buffer.position(0)
                    continue
                }
            }

            val remaining = (buffer.remaining() / 4) * 4
            if (remaining <= 0) {
                val fallback = input.copyOfRange(0, safeBytes)
                if (volumeGain != 1f) applyGainWithSoftClip(fallback, volumeGain)
                return fallback
            }
            val out = ByteArray(remaining)
            buffer.get(out, 0, remaining)
            if (volumeGain != 1f) applyGainWithSoftClip(out, volumeGain)
            return out
        }
    }

    /** Applies the MyPlayerVolumeSink math: curve.gain(requested) * replayGain factor. */
    @Synchronized
    fun setPlayerVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        if (lastVolume != clamped) {
            lastVolume = clamped
            computeGain()
        }
    }

    private fun computeGain() {
        val audioFx = fx
        if (audioFx == null) {
            volumeGain = lastVolume
            return
        }
        try {
            val rawGain = audioFx.volumeCurve.gain(lastVolume) *
                replayGain.linear(audioFx.replayGain, audioFx.normalize)
            // Prevent overdrive above unity unless ReplayGain specifically requests a small safe lift
            volumeGain = rawGain.coerceIn(0f, 1.15f)
        } catch (_: Throwable) {
            volumeGain = lastVolume
        }
    }

    private fun applyGainWithSoftClip(pcm: ByteArray, gain: Float) {
        val g = gain.toDouble()
        for (i in 0 until pcm.size - 1 step 2) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            var normalized = (sample / 32768.0) * g
            if (normalized > 0.92 || normalized < -0.92) {
                val sign = if (normalized >= 0.0) 1.0 else -1.0
                val absVal = kotlin.math.abs(normalized)
                normalized = sign * (0.92 + 0.075 * tanh((absVal - 0.92) / 0.075))
            }
            val scaled = (normalized * 32767.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = (scaled and 0xFF).toByte()
            pcm[i + 1] = (scaled shr 8).toByte()
        }
    }
}
