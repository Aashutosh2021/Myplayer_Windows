package com.example.myplayer.playback

import android.content.Context
import android.os.Environment
import com.example.myplayer.myplayerfx.DesktopMyPlayerDspPipeline
import com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.DefaultAudioCallbackAdapter
import java.io.File
import java.net.URI
import java.net.URLDecoder
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

object DesktopAudioBackend {
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerFactory: MediaPlayerFactory? = null
    var isVlcAvailable: Boolean = false
        private set

    private const val CALLBACK_RATE = 48000
    private const val CALLBACK_CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2
    private const val BLOCK_ALIGN = CALLBACK_CHANNELS * BYTES_PER_SAMPLE
    private var soundLine: SourceDataLine? = null
    private var currentRate: Float = 1f

    // Separate user volume slider (0..1) from crossfade/pause-fade envelope (0..1)
    @Volatile private var userTargetVolume: Float = 1f
    @Volatile private var crossfadeEnvelope: Float = 1f
    @Volatile var isCrossfading: Boolean = false
        private set

    private var pauseFadeJob: Job? = null

    // Secondary Deck for True Apple-Style Overlapping Crossfade
    private var outgoingMediaPlayer: MediaPlayer? = null
    private var outgoingSoundLine: SourceDataLine? = null
    @Volatile private var outgoingEnvelope: Float = 0f
    private var outgoingFadeJob: Job? = null
    private var manualCrossfadeRampInJob: Job? = null

    private val backendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // VLC's native band table (fixed frequencies, Hz): 60, 170, 310, 600, 1k, 3k, 6k, 12k, 14k, 16k.
    private val VLC_BAND_CENTERS = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
    private const val VLC_EQ_BAND_COUNT = 10
    private const val VLC_AMP_MIN_DB = -20f
    private const val VLC_AMP_MAX_DB = 20f
    private val dspLock = Any()
    @Volatile private var lastDspSignature: String? = null

    private var onCompletionCallback: (() -> Unit)? = null
    private var onPlayingChangedCallback: ((Boolean) -> Unit)? = null
    var currentPath: String? = null
        private set
    var isPaused: Boolean = false
        private set
    private var simulatedStartTime: Long = 0L
    private var simulatedPauseTime: Long = 0L
    private var isSimulatedPlaying: Boolean = false

    fun discoverWindowsVlcPath(): File? {
        val candidates = buildList {
            System.getenv("VLC_PLUGIN_PATH")?.let { add(File(it).parentFile ?: File(it)) }
            System.getenv("ProgramFiles")?.let { add(File(it, "VideoLAN\\VLC")) }
            System.getenv("ProgramFiles(x86)")?.let { add(File(it, "VideoLAN\\VLC")) }
            System.getenv("LOCALAPPDATA")?.let { add(File(it, "Programs\\VideoLAN\\VLC")) }
            add(File("C:\\Program Files\\VideoLAN\\VLC"))
            add(File("C:\\Program Files (x86)\\VideoLAN\\VLC"))
        }
        return candidates.firstOrNull { dir ->
            dir.exists() && dir.isDirectory && File(dir, "libvlc.dll").exists()
        }
    }

    fun init() {
        try {
            System.setProperty("jna.encoding", "UTF-8")
            discoverWindowsVlcPath()?.let { vlcDir ->
                System.setProperty("jna.library.path", vlcDir.absolutePath)
            }
            NativeDiscovery().discover()
            val factory = MediaPlayerFactory(
                "--no-video",
                "--quiet",
                "--network-caching=1500",
                "--file-caching=1000",
                "--audio-resampler=soxr"
            )
            mediaPlayerFactory = factory
            DesktopMyPlayerDspPipeline.install()
            val primaryDeck = createDeckPlayer(factory)
            mediaPlayer = primaryDeck.first
            soundLine = primaryDeck.second
            isVlcAvailable = true
            println("[DesktopAudioBackend] VLC Media Engine initialized successfully (Dual-Deck Crossfade ready).")
        } catch (t: Throwable) {
            println("[DesktopAudioBackend] VLC native engine not available: ${t.message}. Using fallback playback.")
            isVlcAvailable = false
        }
    }

    private fun createDeckPlayer(factory: MediaPlayerFactory): Pair<MediaPlayer, SourceDataLine?> {
        val player = factory.mediaPlayers().newMediaPlayer()
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mp: MediaPlayer?) {
                if (mp !== mediaPlayer) return
                isPaused = false
                backendScope.launch {
                    onCompletionCallback?.invoke()
                }
            }

            override fun playing(mp: MediaPlayer?) {
                if (mp !== mediaPlayer) return
                isPaused = false
                backendScope.launch {
                    onPlayingChangedCallback?.invoke(true)
                }
            }

            override fun paused(mp: MediaPlayer?) {
                if (mp !== mediaPlayer) return
                isPaused = true
                backendScope.launch {
                    onPlayingChangedCallback?.invoke(false)
                }
            }

            override fun stopped(mp: MediaPlayer?) {
                if (mp !== mediaPlayer) return
                isPaused = false
                backendScope.launch {
                    onPlayingChangedCallback?.invoke(false)
                }
            }

            override fun error(mp: MediaPlayer?) {
                if (mp !== mediaPlayer) return
                isPaused = false
                backendScope.launch {
                    onPlayingChangedCallback?.invoke(false)
                }
            }
        })

        var deckLine: SourceDataLine? = null
        try {
            val lineFormat = javax.sound.sampled.AudioFormat(
                CALLBACK_RATE.toFloat(), 16, CALLBACK_CHANNELS, true, false
            )
            val line = AudioSystem.getSourceDataLine(lineFormat)
            val bufferBytes = (((CALLBACK_RATE * BLOCK_ALIGN * 150) / 1000) / BLOCK_ALIGN) * BLOCK_ALIGN
            line.open(lineFormat, bufferBytes)
            line.start()
            deckLine = line

            // Per-deck high-pass state for outgoing crossfade mud-guard
            var outHpfInL = 0.0
            var outHpfOutL = 0.0
            var outHpfInR = 0.0
            var outHpfOutR = 0.0

            val adapter = object : DefaultAudioCallbackAdapter(BLOCK_ALIGN) {
                override fun onPlay(
                    mp: MediaPlayer?,
                    samples: ByteArray,
                    sampleCount: Int,
                    pts: Long
                ) {
                    try {
                        val rawBytes = if (sampleCount > 0) {
                            minOf(samples.size, sampleCount * BLOCK_ALIGN)
                        } else {
                            samples.size
                        }
                        val alignedByteCount = (rawBytes / BLOCK_ALIGN) * BLOCK_ALIGN
                        if (alignedByteCount <= 0) return

                        val activeLine = deckLine ?: return
                        if (!activeLine.isRunning) activeLine.start()

                        if (mp === mediaPlayer) {
                            val frame = DesktopMyPlayerDspPipeline.process(samples, alignedByteCount)
                            val writeLen = (frame.size / BLOCK_ALIGN) * BLOCK_ALIGN
                            if (writeLen > 0) {
                                activeLine.write(frame, 0, writeLen)
                            }
                        } else if (mp === outgoingMediaPlayer) {
                            // Outgoing crossfade deck: apply equal-power cosine envelope + 110Hz sub-bass mud-guard
                            val gain = (userTargetVolume * outgoingEnvelope).coerceIn(0f, 1f).toDouble()
                            if (gain <= 0.001) return
                            val frame = samples.copyOfRange(0, alignedByteCount)
                            val rc = 1.0 / (2.0 * PI * 110.0)
                            val dt = 1.0 / CALLBACK_RATE.toDouble()
                            val alpha = rc / (rc + dt)
                            val cut = 0.55
                            var i = 0
                            while (i <= frame.size - 4) {
                                val sL = ((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort().toInt()
                                val sR = ((frame[i + 2].toInt() and 0xFF) or (frame[i + 3].toInt() shl 8)).toShort().toInt()
                                var inL = sL / 32768.0
                                var inR = sR / 32768.0
                                val hpfL = alpha * (outHpfOutL + inL - outHpfInL)
                                val hpfR = alpha * (outHpfOutR + inR - outHpfInR)
                                outHpfInL = inL; outHpfOutL = hpfL
                                outHpfInR = inR; outHpfOutR = hpfR
                                inL = inL * (1.0 - cut) + hpfL * cut
                                inR = inR * (1.0 - cut) + hpfR * cut
                                val outL = (tanh(inL * gain) * 32767.0).roundToInt().coerceIn(-32768, 32767)
                                val outR = (tanh(inR * gain) * 32767.0).roundToInt().coerceIn(-32768, 32767)
                                frame[i] = (outL and 0xFF).toByte()
                                frame[i + 1] = (outL shr 8).toByte()
                                frame[i + 2] = (outR and 0xFF).toByte()
                                frame[i + 3] = (outR shr 8).toByte()
                                i += 4
                            }
                            activeLine.write(frame, 0, frame.size)
                        }
                    } catch (t: Throwable) {
                        println("[DesktopAudioBackend] DSP play failed: ${t.message}")
                    }
                }

                override fun pause(mp: MediaPlayer?, pts: Long) {
                    try { deckLine?.stop() } catch (_: Throwable) {}
                }

                override fun resume(mp: MediaPlayer?, pts: Long) {
                    try { deckLine?.start() } catch (_: Throwable) {}
                }

                override fun flush(mp: MediaPlayer?, pts: Long) {
                    try { deckLine?.flush() } catch (_: Throwable) {}
                }

                override fun drain(mp: MediaPlayer?) {
                    try { deckLine?.drain() } catch (_: Throwable) {}
                }

                override fun setVolume(volume: Float, mute: Boolean) {
                    if (mute) {
                        DesktopMyPlayerDspPipeline.setPlayerVolume(0f)
                    } else {
                        applyEffectiveVolume()
                    }
                }
            }
            player.audio().callback("S16N", CALLBACK_RATE, CALLBACK_CHANNELS, adapter)
            DesktopMyPlayerDspPipeline.beginStream(CALLBACK_RATE, CALLBACK_CHANNELS)
            DesktopMyPlayerDspPipeline.markCallbackMode()
        } catch (t: Throwable) {
            println("[DesktopAudioBackend] Callback audio fallback on deck: ${t.message}")
        }
        return player to deckLine
    }

    /**
     * Hands off the currently playing VLC deck to [outgoingMediaPlayer] so the outgoing track
     * continues playing its tail and fading out smoothly with an Apple Music equal-power cosine curve
     * while the new track loads and fades in on the new primary [mediaPlayer].
     */
    @Synchronized
    fun beginCrossfadeHandoff(fadeDurationMs: Long = (MyPlayerAudioFxDspBridge.crossfadeSeconds * 1000f).toLong()): Boolean {
        val factory = mediaPlayerFactory ?: return false
        val currentMp = mediaPlayer ?: return false
        if (!isVlcAvailable || !isPlaying()) return false
        if (outgoingMediaPlayer != null && isCrossfading) return true

        stopOutgoingDeckImmediate()

        return try {
            val currentLine = soundLine
            outgoingMediaPlayer = currentMp
            outgoingSoundLine = currentLine
            outgoingEnvelope = crossfadeEnvelope.coerceIn(0.15f, 1.0f)

            val newDeck = createDeckPlayer(factory)
            mediaPlayer = newDeck.first
            soundLine = newDeck.second
            lastDspSignature = null

            isCrossfading = true
            crossfadeEnvelope = 0.04f
            applyEffectiveVolume()

            val totalFadeMs = fadeDurationMs.coerceIn(1000L, 12_000L)
            val startEnv = outgoingEnvelope
            outgoingFadeJob = backendScope.launch {
                val steps = 36
                val stepDelay = (totalFadeMs / steps).coerceAtLeast(30L)
                for (step in 1..steps) {
                    if (!isActive) break
                    val progress = step.toFloat() / steps.toFloat()
                    outgoingEnvelope = (startEnv * cos(progress * (PI / 2.0)).toFloat()).coerceIn(0f, 1f)
                    if (!DesktopMyPlayerDspPipeline.isCallbackMode) {
                        try {
                            val vlcVol = (userTargetVolume * outgoingEnvelope * 100f).roundToInt().coerceIn(0, 100)
                            outgoingMediaPlayer?.audio()?.setVolume(vlcVol)
                        } catch (_: Throwable) {}
                    }
                    delay(stepDelay)
                }
                outgoingEnvelope = 0f
                stopOutgoingDeckImmediate()
            }
            true
        } catch (t: Throwable) {
            println("[DesktopAudioBackend] Crossfade handoff fallback: ${t.message}")
            false
        }
    }

    private fun stopOutgoingDeckImmediate() {
        outgoingFadeJob?.cancel()
        outgoingFadeJob = null
        val oldMp = outgoingMediaPlayer
        val oldLine = outgoingSoundLine
        outgoingMediaPlayer = null
        outgoingSoundLine = null
        outgoingEnvelope = 0f
        if (oldMp != null || oldLine != null) {
            backendScope.launch(Dispatchers.IO) {
                try { oldMp?.controls()?.stop() } catch (_: Throwable) {}
                try { oldMp?.release() } catch (_: Throwable) {}
                try { oldLine?.stop(); oldLine?.close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Called by Session.kt during track switches (`seekToNext`, `setMediaItem`, `setMediaItems`).
     * When Crossfade is enabled and a track is playing, hands off the outgoing track to the
     * secondary deck instead of abruptly cutting audio!
     */
    fun prepareForTrackSwitch() {
        if (MyPlayerAudioFxDspBridge.isAnyCrossfadeActive && isPlaying()) {
            val fadeMs = (MyPlayerAudioFxDspBridge.crossfadeSeconds.coerceIn(1f, 12f) * 1000f).toLong()
            val handedOff = beginCrossfadeHandoff(fadeMs)
            if (handedOff) {
                // Start a smooth sine ramp-in as soon as the new track starts playing
                manualCrossfadeRampInJob?.cancel()
                manualCrossfadeRampInJob = backendScope.launch {
                    var waited = 0L
                    while (isActive && !isPlaying() && waited < 3500L) {
                        delay(50L)
                        waited += 50L
                    }
                    val steps = 28
                    val stepDelay = (fadeMs / steps).coerceIn(35L, 180L)
                    for (step in 1..steps) {
                        if (!isActive || !isCrossfading) break
                        val p = step.toFloat() / steps.toFloat()
                        crossfadeEnvelope = sin(p * (PI / 2.0)).toFloat().coerceIn(0.04f, 1.0f)
                        DesktopMyPlayerDspPipeline.crossfadeBassCutFactor = (sin(p * PI).toFloat() * 0.65f).coerceIn(0f, 0.75f)
                        applyEffectiveVolume()
                        delay(stepDelay)
                    }
                    DesktopMyPlayerDspPipeline.crossfadeBassCutFactor = 0f
                    crossfadeEnvelope = 1.0f
                    isCrossfading = false
                    applyEffectiveVolume()
                }
                return
            }
        }
        stopActiveDeckOnly()
    }

    private fun stopActiveDeckOnly() {
        pauseFadeJob?.cancel()
        isPaused = false
        currentPath = null
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.controls()?.stop()
            } catch (_: Exception) {}
        }
        isSimulatedPlaying = false
    }

    fun resolveLocalFile(pathOrUri: String?): File? {
        if (pathOrUri.isNullOrBlank()) return null
        val trimmed = pathOrUri.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("online://", ignoreCase = true)
        ) {
            return null
        }

        val candidates = mutableListOf<File>()
        candidates.add(File(trimmed))

        if (trimmed.startsWith("file:", ignoreCase = true)) {
            runCatching { File(URI(trimmed)) }.getOrNull()?.let { candidates.add(it) }
            val stripped = trimmed
                .removePrefix("file:///")
                .removePrefix("file://")
                .removePrefix("file:/")
                .removePrefix("file:")
            val decoded = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped.replace("%20", " "))
            val winFixed = if (decoded.matches(Regex("^/[a-zA-Z]:[\\\\/].*"))) decoded.substring(1) else decoded
            candidates.add(File(winFixed))
        } else {
            val decoded = runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrDefault(trimmed.replace("%20", " "))
            val winFixed = if (decoded.matches(Regex("^/[a-zA-Z]:[\\\\/].*"))) decoded.substring(1) else decoded
            candidates.add(File(winFixed))
        }

        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile && candidate.length() > 0L) {
                return candidate
            }
        }

        val fileName = candidates.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        if (fileName != null) {
            val musicDir = Context.DEFAULT.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: Context.DEFAULT.filesDir
            val inMusicDir = File(musicDir, fileName)
            if (inMusicDir.exists() && inMusicDir.isFile && inMusicDir.length() > 0L) {
                return inMusicDir
            }
        }

        return null
    }

    fun play(pathOrUri: String, startPositionMs: Long = 0L) {
        pauseFadeJob?.cancel()
        if (!isCrossfading) {
            crossfadeEnvelope = 1.0f
        }
        applyEffectiveVolume()

        val localFile = resolveLocalFile(pathOrUri)
        val cleanPath = if (localFile != null) {
            DesktopChunkStreamProxy.wrapLocalFile(localFile)
        } else {
            val rawCleanPath = when {
                pathOrUri.startsWith("file://") -> pathOrUri.removePrefix("file://")
                pathOrUri.startsWith("file:/") -> pathOrUri.removePrefix("file:")
                else -> pathOrUri
            }
            DesktopChunkStreamProxy.wrapUrlIfNeeded(rawCleanPath)
        }

        if (cleanPath == currentPath && isPaused && startPositionMs <= 0L) {
            resume()
            return
        }
        DesktopMyPlayerDspPipeline.onTrackChange()
        currentPath = cleanPath
        isPaused = false
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.media()?.play(cleanPath)
                if (startPositionMs > 0L) {
                    mediaPlayer?.controls()?.setTime(startPositionMs)
                }
                if (abs(currentRate - 1f) > 0.001f) {
                    try { mediaPlayer?.controls()?.setRate(currentRate) } catch (_: Exception) {}
                }
                return
            } catch (e: Exception) {
                println("[DesktopAudioBackend] Error playing via VLC: ${e.message}")
            }
        }
        simulatedStartTime = System.currentTimeMillis() - startPositionMs
        isSimulatedPlaying = true
        onPlayingChangedCallback?.invoke(true)
    }

    fun pause() {
        isPaused = true
        pauseFadeJob?.cancel()
        manualCrossfadeRampInJob?.cancel()
        isCrossfading = false
        DesktopMyPlayerDspPipeline.crossfadeBassCutFactor = 0f
        stopOutgoingDeckImmediate()
        if (isVlcAvailable && mediaPlayer != null) {
            if (MyPlayerAudioFxDspBridge.pauseFadeEnabled) {
                val fadeMs = (MyPlayerAudioFxDspBridge.pauseFadeDurationSec * 180f).toLong().coerceIn(80L, 450L)
                pauseFadeJob = backendScope.launch {
                    val steps = 8
                    val stepDelay = (fadeMs / steps).coerceAtLeast(10L)
                    for (s in 1..steps) {
                        val p = s.toFloat() / steps.toFloat()
                        crossfadeEnvelope = cos(p * (PI / 2.0)).toFloat().coerceIn(0f, 1f)
                        applyEffectiveVolume()
                        delay(stepDelay)
                    }
                    try {
                        mediaPlayer?.controls()?.setPause(true)
                    } catch (_: Exception) {
                        try { mediaPlayer?.controls()?.pause() } catch (_: Exception) {}
                    }
                    crossfadeEnvelope = 1f
                    applyEffectiveVolume()
                }
                return
            }
            try {
                mediaPlayer?.controls()?.setPause(true)
                return
            } catch (e: Exception) {
                println("[DesktopAudioBackend] Error pausing VLC: ${e.message}")
            }
        }
        if (isSimulatedPlaying) {
            simulatedPauseTime = System.currentTimeMillis()
            isSimulatedPlaying = false
            onPlayingChangedCallback?.invoke(false)
        }
    }

    fun resume() {
        isPaused = false
        pauseFadeJob?.cancel()
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                if (MyPlayerAudioFxDspBridge.pauseFadeEnabled) {
                    crossfadeEnvelope = 0.05f
                    applyEffectiveVolume()
                }
                mediaPlayer?.controls()?.play()
                if (MyPlayerAudioFxDspBridge.pauseFadeEnabled) {
                    val fadeMs = (MyPlayerAudioFxDspBridge.pauseFadeDurationSec * 180f).toLong().coerceIn(80L, 450L)
                    pauseFadeJob = backendScope.launch {
                        val steps = 8
                        val stepDelay = (fadeMs / steps).coerceAtLeast(10L)
                        for (s in 1..steps) {
                            val p = s.toFloat() / steps.toFloat()
                            crossfadeEnvelope = sin(p * (PI / 2.0)).toFloat().coerceIn(0.05f, 1f)
                            applyEffectiveVolume()
                            delay(stepDelay)
                        }
                        crossfadeEnvelope = 1f
                        applyEffectiveVolume()
                    }
                }
                return
            } catch (e: Exception) {
                println("[DesktopAudioBackend] Error resuming VLC: ${e.message}")
            }
        }
        if (!isSimulatedPlaying) {
            val pausedDuration = System.currentTimeMillis() - simulatedPauseTime
            simulatedStartTime += pausedDuration
            isSimulatedPlaying = true
            onPlayingChangedCallback?.invoke(true)
        }
    }

    fun stop() {
        pauseFadeJob?.cancel()
        manualCrossfadeRampInJob?.cancel()
        isCrossfading = false
        crossfadeEnvelope = 1f
        DesktopMyPlayerDspPipeline.crossfadeBassCutFactor = 0f
        stopOutgoingDeckImmediate()
        isPaused = false
        currentPath = null
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.controls()?.stop()
            } catch (_: Exception) {}
        }
        isSimulatedPlaying = false
        onPlayingChangedCallback?.invoke(false)
    }

    fun seekTo(positionMs: Long) {
        val validPos = positionMs.coerceAtLeast(0L)
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.controls()?.setTime(validPos)
                return
            } catch (_: Exception) {}
        }
        if (isSimulatedPlaying) {
            simulatedStartTime = System.currentTimeMillis() - validPos
        } else {
            simulatedPauseTime = System.currentTimeMillis()
            simulatedStartTime = simulatedPauseTime - validPos
        }
    }

    private fun applyEffectiveVolume() {
        val effective = (userTargetVolume * crossfadeEnvelope).coerceIn(0f, 1f)
        if (DesktopMyPlayerDspPipeline.isCallbackMode) {
            DesktopMyPlayerDspPipeline.setPlayerVolume(effective)
            return
        }
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                val vlcVolume = (effective * 100).toInt().coerceIn(0, 100)
                mediaPlayer?.audio()?.setVolume(vlcVolume)
            } catch (_: Exception) {}
        }
    }

    /** Called by the user's volume slider in NowPlayingScreen / MainViewModel. */
    fun setVolume(volume: Float) {
        userTargetVolume = volume.coerceIn(0f, 1f)
        applyEffectiveVolume()
    }

    /** Called by AudioFeatureManagers / MediaController for crossfade volume enveloping. */
    fun setCrossfadeEnvelope(envelope: Float) {
        val clamped = envelope.coerceIn(0f, 1f)
        crossfadeEnvelope = clamped
        isCrossfading = clamped < 0.99f
        applyEffectiveVolume()
    }

    fun getPosition(): Long {
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                val time = mediaPlayer?.status()?.time() ?: -1L
                if (time >= 0L) return time
            } catch (_: Exception) {}
        }
        return if (isSimulatedPlaying) {
            System.currentTimeMillis() - simulatedStartTime
        } else {
            simulatedPauseTime - simulatedStartTime
        }.coerceAtLeast(0L)
    }

    fun getDuration(): Long {
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                val length = mediaPlayer?.status()?.length() ?: -1L
                if (length > 0L) return length
            } catch (_: Exception) {}
        }
        return 180_000L
    }

    fun isPlaying(): Boolean {
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                return mediaPlayer?.status()?.isPlaying ?: false
            } catch (_: Exception) {}
        }
        return isSimulatedPlaying
    }

    fun setOnCompletion(callback: () -> Unit) {
        onCompletionCallback = callback
    }

    fun setSpeed(speed: Float) {
        currentRate = speed.coerceIn(0.25f, 4f)
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.controls()?.setRate(currentRate)
            } catch (_: Exception) {}
        }
    }

    fun setDspParams(eqEnabled: Boolean, eqBandGains: List<Float>?, loudnessPreampDb: Float) {
        if (DesktopMyPlayerDspPipeline.isCallbackMode) return
        if (isVlcAvailable && mediaPlayer == null) return
        val signature = buildString {
            append("eq=").append(eqEnabled)
            append(";gains=").append(eqBandGains?.joinToString(",") { "%.3f".format(it) }.orEmpty())
            append(";preamp=").append("%.3f".format(loudnessPreampDb))
        }
        synchronized(dspLock) {
            if (signature == lastDspSignature) return
            try {
                if (!isVlcAvailable) {
                    lastDspSignature = signature
                    return
                }
                val target = mediaPlayer ?: return
                if (!eqEnabled && loudnessPreampDb == 0f) {
                    lastDspSignature = signature
                    target.audio().setEqualizer(null)
                    return
                }
                val equalizer = Equalizer(VLC_EQ_BAND_COUNT)
                val amps = foldIsoGainsToVlcBins(eqBandGains.takeIf { eqEnabled })
                val maxBoost = amps.maxOrNull()?.coerceAtLeast(0f) ?: 0f
                val safePreamp = (loudnessPreampDb - maxBoost * 0.55f).coerceIn(VLC_AMP_MIN_DB, 6f)
                equalizer.setPreamp(safePreamp)
                for (bin in 0 until VLC_EQ_BAND_COUNT) {
                    equalizer.setAmp(bin, amps[bin].coerceIn(VLC_AMP_MIN_DB, VLC_AMP_MAX_DB))
                }
                target.audio().setEqualizer(equalizer)
                lastDspSignature = signature
            } catch (t: Throwable) {
                println("[DesktopAudioBackend] setDspParams failed: ${t.message}")
            }
        }
    }

    private fun foldIsoGainsToVlcBins(gainsDb: List<Float>?): FloatArray {
        val bins = FloatArray(VLC_EQ_BAND_COUNT)
        val counts = IntArray(VLC_EQ_BAND_COUNT)
        val isoCenters = com.example.myplayer.playback.dsp.MasterAudioDspProcessor.BAND_FREQUENCIES
        for (isoIndex in isoCenters.indices) {
            val gain = gainsDb?.getOrNull(isoIndex)?.takeIf { it.isFinite() } ?: continue
            val isoHz = isoCenters[isoIndex]
            var bestBin = 0
            var bestDistance = Double.MAX_VALUE
            for (bin in 0 until VLC_EQ_BAND_COUNT) {
                val distance = abs(ln(isoHz.toDouble()) - ln(VLC_BAND_CENTERS[bin].toDouble()))
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestBin = bin
                }
            }
            bins[bestBin] += gain
            counts[bestBin]++
        }
        for (bin in bins.indices) {
            if (counts[bin] > 0) bins[bin] /= counts[bin]
        }
        return bins
    }

    fun setOnPlayingChanged(callback: (Boolean) -> Unit) {
        onPlayingChangedCallback = callback
    }

    fun release() {
        try {
            pauseFadeJob?.cancel()
            manualCrossfadeRampInJob?.cancel()
            stopOutgoingDeckImmediate()
            val player = mediaPlayer
            val factory = mediaPlayerFactory
            val line = soundLine
            mediaPlayer = null
            mediaPlayerFactory = null
            soundLine = null
            backendScope.launch(Dispatchers.IO) {
                try {
                    player?.controls()?.stop()
                    player?.release()
                } catch (_: Throwable) {}
                try {
                    factory?.release()
                } catch (_: Throwable) {}
                try {
                    line?.stop()
                    line?.close()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}