package com.example.myplayer.myplayerfx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.example.myplayer.di.AppContainer
import com.example.myplayer.myplayerfx.audio.LoudnessAudioProcessor
import com.example.myplayer.myplayerfx.audio.ReplayGainTags
import com.example.myplayer.myplayerfx.audio.SpatialAudioProcessor
import com.example.myplayer.myplayerfx.audio.ReverbAudioProcessor
import com.example.myplayer.playback.dsp.MasterAudioDspProcessor
import com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Desktop replacement for Android's ExoPlayer `DefaultAudioSink` DSP chain (di/PlayerModule.kt).
 *
 * Android builds its player with an audio sink whose processors are
 * [masterAudioDspProcessor, *myplayerFx] wrapped in MyPlayerVolumeSink. The desktop app plays
 * through VLC, so the same chain runs here over libvlc's callback audio output (amem): VLC hands
 * raw S16N PCM to [process] on its decoder thread, the processors run in the Android order
 * (master EQ/limiter -> Audio Lab spatial -> reverb -> loudness) and the MyPlayerVolumeSink volume
 * stage (exponential curve + ReplayGain) scales the samples before they reach Java Sound.
 *
 * Without this object every Audio Lab slider (reverb, spatial, loudness, "even out songs")
 * would change state but never the audible signal.
 *
 * shortcut: Android also runs playback\dsp\SpatialAudioProcessor (surround streams) in this chain;
 * it is not ported to desktop yet. Volume ramps/limiting beyond MyPlayerVolumeSink semantics and
 * ReplayGain tags (update(comments)) stay unimplemented — no tag parsing on the desktop decode path.
 */
object DesktopMyPlayerDspPipeline {

    /** True when VLC's audio callback output is live; falls back to VLC's own native output otherwise. */
    @get:Synchronized
    var isCallbackMode: Boolean = false
        private set

    /** Flips the volume/ equalizer paths once the callback output is fully wired. */
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

    @Volatile private var volumeGain = 1f
    @Volatile private var lastVolume = 1f

    /** Mirrors Android PlayerModule.provideExoPlayer: attach the bridge before any UI runs. */
    @Synchronized
    fun install() {
        install(
            AppContainer.resolveDependency(AudioFxService::class.java) as? AudioFxService,
            AppContainer.resolveDependency(MasterAudioDspProcessor::class.java) as? MasterAudioDspProcessor,
        )
    }

    /** install() overload so the DSP chain can also run outside AppContainer (smoke tests). */
    @Synchronized
    fun install(audioFx: AudioFxService?, master: MasterAudioDspProcessor?) {
        if (installed) return
        installed = true
        try {
            fx = audioFx
            chain = buildChain(audioFx, master)
            println("[DesktopMyPlayerDspPipeline] installed ${chain.size} DSP stage(s); audioFx=${audioFx != null}, master=${master != null}")
            if (audioFx != null) {
                // The state holder pushes Audio Lab params into this service live, so the
                // processors reading it per buffer act on the current UI values. Runs after
                // the chain exists so a UI-store hiccup can't leave the chain empty.
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
    ): List<AudioProcessor> = buildList {
        if (master != null) add(master)
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
                // Re-configure even the currently-active ones: flush() carries the pending format.
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
        for (processor in chain) {
            if (processor.isActive()) {
                processor.flush()
            }
        }
    }

    /**
     * Processes one interleaved S16N block (VLC native byte order) in place; returns the array
     * holding the processed samples (input when inactive stages pass it through untouched).
     */
    fun process(input: ByteArray, byteCount: Int): ByteArray {
        computeGain()
        var buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        buffer.put(input, 0, byteCount)
        buffer.flip()
        for (processor in chain) {
            if (!processor.isActive()) continue
            buffer.position(0)
            buffer.limit(byteCount)
            try {
                processor.queueInput(buffer)
                buffer = processor.getOutput()
            } catch (t: Throwable) {
                println("[DesktopMyPlayerDspPipeline] ${processor.javaClass.simpleName} threw: ${t.message}")
                continue
            }
            buffer.position(0)
        }
        val remaining = min(buffer.remaining(), byteCount)
        if (remaining <= 0) return input
        val out = ByteArray(remaining)
        buffer.get(out)
        // MyPlayerVolumeSink stage: the curve shapes the player's requested volume and ReplayGain
        // levels loud tracks down ("even out songs").
        if (volumeGain != 1f) applyGain(out, volumeGain)
        return out
    }

    /** Applies the MyPlayerVolumeSink math: curve.gain(requested) * replayGain factor. */
    @Synchronized
    fun setPlayerVolume(volume: Float) {
        if (lastVolume != volume) {
            lastVolume = volume
            computeGain()
        }
    }

    /**
     * Recomputed per buffer (a few float ops) because the AudioFxService values the gain folding
     * depends on (volumeCurve identity, ReplayGainParams, normalize) can all change live — Android's
     * MyPlayerVolumeSink re-applies on the next handleBuffer the same way.
     */
    private fun computeGain() {
        val audioFx = fx ?: return
        try {
            volumeGain = audioFx.volumeCurve.gain(lastVolume) *
                replayGain.linear(audioFx.replayGain, audioFx.normalize)
        } catch (_: Throwable) {
            volumeGain = 1f
        }
    }

    private fun applyGain(pcm: ByteArray, gain: Float) {
        for (i in 0 until pcm.size - 1 step 2) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = (scaled and 0xFF).toByte()
            pcm[i + 1] = (scaled shr 8).toByte()
        }
    }
}
