package com.example.myplayer.myplayerfx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.example.myplayer.di.AppContainer
import com.example.myplayer.myplayerfx.audio.LoudnessAudioProcessor
import com.example.myplayer.myplayerfx.audio.MyPlayerAudioTap
import com.example.myplayer.myplayerfx.audio.ReplayGainTags
import com.example.myplayer.myplayerfx.audio.ReverbAudioProcessor
import com.example.myplayer.myplayerfx.audio.SpatialAudioProcessor
import com.example.myplayer.playback.dsp.MasterAudioDspProcessor
import com.example.myplayer.playback.dsp.SpatialAudioProcessor as SurroundSpatialAudioProcessor
import com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Desktop replacement for Android's ExoPlayer `DefaultAudioSink` DSP chain (`di/PlayerModule.kt`).
 *
 * FIXED FOR WINDOWS DESKTOP:
 * 1. Feeds [MyPlayerAudioTap] on every PCM buffer so all 60fps Audio Lab live visualizers
 *    (`MyPlayerLoudnessPicture`, `MyPlayerSpatialPicture`, `MyPlayerAtmosPicture`, `MyPlayerReverbPicture`)
 *    animate in real time with the music!
 * 2. Implements a 110 Hz sub-bass Mud-Guard high-pass filter during Apple Music AutoMix / Crossfade
 *    transitions (`crossfadeBassCutFactor`) so overlapping basslines never boom or clip.
 * 3. Runs [MasterAudioDspProcessor] -> [SurroundSpatialAudioProcessor] -> [SpatialAudioProcessor] ->
 *    [ReverbAudioProcessor] -> [LoudnessAudioProcessor] -> Exponential Volume + Soft-Clip Limiter.
 */
object DesktopMyPlayerDspPipeline {

    /** True when VLC's audio callback output is live; falls back to VLC's own native output otherwise. */
    @get:Synchronized
    var isCallbackMode: Boolean = false
        private set

    @Synchronized
    fun markCallbackMode() {
        isCallbackMode = true
    }

    @Volatile private var installed = false
    @Volatile private var fx: AudioFxService? = null
    @Volatile private var chain: List<AudioProcessor> = emptyList()
    private var configApplied = false
    private var configuredSampleRate = 48000
    private var configuredChannels = 2
    private var replayGain = ReplayGainTags()

    private var stagingBuffer: ByteBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())
    private val dspProcessLock = Any()

    @Volatile private var volumeGain = 1f
    @Volatile private var lastVolume = 1f

    // Sub-bass mud-guard state during crossfade transitions (0f = normal, 1f = full 110 Hz HPF cut)
    @Volatile var crossfadeBassCutFactor: Float = 0f
    private var hpfPrevInL = 0.0
    private var hpfPrevOutL = 0.0
    private var hpfPrevInR = 0.0
    private var hpfPrevOutR = 0.0

    @Synchronized
    fun install() {
        install(
            AppContainer.resolveDependency(AudioFxService::class.java) as? AudioFxService,
            AppContainer.resolveDependency(MasterAudioDspProcessor::class.java) as? MasterAudioDspProcessor,
            AppContainer.resolveDependency(SurroundSpatialAudioProcessor::class.java) as? SurroundSpatialAudioProcessor
        )
    }

    @Synchronized
    fun install(
        audioFx: AudioFxService?,
        master: MasterAudioDspProcessor?,
        surroundSpatial: SurroundSpatialAudioProcessor? = null
    ) {
        if (installed && chain.isNotEmpty()) return
        installed = true
        try {
            val resolvedFx = audioFx ?: AudioFxService().also {
                AppContainer.register(AudioFxService::class.java, it)
            }
            fx = resolvedFx
            chain = buildChain(resolvedFx, master, surroundSpatial)
            println("[DesktopMyPlayerDspPipeline] installed ${chain.size} DSP stage(s); audioFx=true, master=${master != null}, spatial=${surroundSpatial != null}")
            MyPlayerAudioFxDspBridge.attach(resolvedFx)
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
        val fxService = audioFx ?: return@buildList
        if (surroundSpatial != null) { add(surroundSpatial) } else { add(SpatialAudioProcessor(fxService)) }
        add(ReverbAudioProcessor(fxService))
        add(LoudnessAudioProcessor(fxService))
    }

    @Synchronized
    fun beginStream(sampleRate: Int, channels: Int) {
        if (!installed || chain.isEmpty()) install()
        val formatChanged = configuredSampleRate != sampleRate || configuredChannels != channels
        val format = AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
        for (processor in chain) {
            if (formatChanged || !configApplied || !processor.isActive()) {
                processor.flush()
                processor.configure(format)
                processor.flush()
            }
        }
        configuredSampleRate = if (sampleRate > 0) sampleRate else 48000
        configuredChannels = if (channels > 0) channels else 2
        configApplied = true
        computeGain()
    }

    @Synchronized
    fun onTrackChange() {
        synchronized(dspProcessLock) {
            hpfPrevInL = 0.0
            hpfPrevOutL = 0.0
            hpfPrevInR = 0.0
            hpfPrevOutR = 0.0
            for (processor in chain) {
                if (processor.isActive()) {
                    processor.flush()
                }
            }
        }
    }

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
                        buffer.position(0)
                    }
                } catch (t: Throwable) {
                    println("[DesktopMyPlayerDspPipeline] ${processor.javaClass.simpleName} threw: ${t.message}")
                    buffer.position(0)
                    continue
                }
            }

            // Always feed MyPlayerAudioTap so Audio Lab 60fps visualizers respond to live music
            if (MyPlayerAudioTap.wanted && buffer.remaining() > 0) {
                try {
                    MyPlayerAudioTap.feed(buffer, configuredChannels.coerceAtLeast(2), configuredSampleRate.coerceAtLeast(48000))
                } catch (_: Throwable) {}
            }

            val remaining = (buffer.remaining() / 4) * 4
            if (remaining <= 0) {
                val fallback = input.copyOfRange(0, safeBytes)
                applyPostStageAndLimiter(fallback, volumeGain, crossfadeBassCutFactor)
                return fallback
            }
            val out = ByteArray(remaining)
            buffer.get(out, 0, remaining)
            applyPostStageAndLimiter(out, volumeGain, crossfadeBassCutFactor)
            return out
        }
    }

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
            volumeGain = rawGain.coerceIn(0f, 1.15f)
        } catch (_: Throwable) {
            volumeGain = lastVolume
        }
    }

    private fun applyPostStageAndLimiter(pcm: ByteArray, gain: Float, bassCutAmount: Float) {
        val g = gain.toDouble()
        val cut = bassCutAmount.coerceIn(0f, 0.85f).toDouble()
        if (abs(g - 1.0) < 0.0005 && cut < 0.01) return

        // 1-pole 110 Hz high-pass filter coefficient for crossfade Mud-Guard
        val sr = configuredSampleRate.coerceAtLeast(44100).toDouble()
        val rc = 1.0 / (2.0 * PI * 110.0)
        val dt = 1.0 / sr
        val alpha = rc / (rc + dt)

        var i = 0
        while (i <= pcm.size - 4) {
            val sL = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val sR = ((pcm[i + 2].toInt() and 0xFF) or (pcm[i + 3].toInt() shl 8)).toShort().toInt()

            var inL = sL / 32768.0
            var inR = sR / 32768.0

            if (cut >= 0.01) {
                val hpfL = alpha * (hpfPrevOutL + inL - hpfPrevInL)
                val hpfR = alpha * (hpfPrevOutR + inR - hpfPrevInR)
                hpfPrevInL = inL
                hpfPrevOutL = hpfL
                hpfPrevInR = inR
                hpfPrevOutR = hpfR
                inL = inL * (1.0 - cut) + hpfL * cut
                inR = inR * (1.0 - cut) + hpfR * cut
            }

            val outL = softClipSample(inL * g)
            val outR = softClipSample(inR * g)

            pcm[i] = (outL and 0xFF).toByte()
            pcm[i + 1] = (outL shr 8).toByte()
            pcm[i + 2] = (outR and 0xFF).toByte()
            pcm[i + 3] = (outR shr 8).toByte()
            i += 4
        }
    }

    private fun softClipSample(normalizedInput: Double): Int {
        var normalized = normalizedInput
        if (normalized > 0.92 || normalized < -0.92) {
            val sign = if (normalized >= 0.0) 1.0 else -1.0
            val absVal = abs(normalized)
            normalized = sign * (0.92 + 0.075 * tanh((absVal - 0.92) / 0.075))
        }
        return (normalized * 32767.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
}