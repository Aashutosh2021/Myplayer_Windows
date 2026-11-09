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
import kotlin.math.sin

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
    @Volatile private var userTargetVolume: Float = 1f
    private var pauseFadeJob: Job? = null

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

    /**
     * Searches all standard Windows VLC installation paths (64-bit, 32-bit, LocalAppData, env vars)
     * so libVLC is discovered reliably on any Windows PC.
     */
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
            val player = factory.mediaPlayers().newMediaPlayer()
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer?) {
                    isPaused = false
                    backendScope.launch {
                        onCompletionCallback?.invoke()
                    }
                }

                override fun playing(mediaPlayer: MediaPlayer?) {
                    isPaused = false
                    backendScope.launch {
                        onPlayingChangedCallback?.invoke(true)
                    }
                }

                override fun paused(mediaPlayer: MediaPlayer?) {
                    isPaused = true
                    backendScope.launch {
                        onPlayingChangedCallback?.invoke(false)
                    }
                }

                override fun stopped(mediaPlayer: MediaPlayer?) {
                    isPaused = false
                    backendScope.launch {
                        onPlayingChangedCallback?.invoke(false)
                    }
                }

                override fun error(mediaPlayer: MediaPlayer?) {
                    isPaused = false
                    backendScope.launch {
                        onPlayingChangedCallback?.invoke(false)
                    }
                }
            })
            mediaPlayer = player
            isVlcAvailable = true
            println("[DesktopAudioBackend] VLC Media Engine initialized successfully.")
            try {
                enableCallbackAudio()
            } catch (t: Throwable) {
                println("[DesktopAudioBackend] DSP callback output unavailable: ${t.message}. VLC native output stays active.")
            }
        } catch (t: Throwable) {
            println("[DesktopAudioBackend] VLC native engine not available: ${t.message}. Using fallback playback.")
            isVlcAvailable = false
        }
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
        applyImmediateVolume(userTargetVolume)

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

    private fun enableCallbackAudio() {
        val player = requireNotNull(mediaPlayer)
        DesktopMyPlayerDspPipeline.install()
        val lineFormat = javax.sound.sampled.AudioFormat(
            CALLBACK_RATE.toFloat(), 16, CALLBACK_CHANNELS, true, false
        )
        val line = AudioSystem.getSourceDataLine(lineFormat)
        val bufferBytes = (((CALLBACK_RATE * BLOCK_ALIGN * 150) / 1000) / BLOCK_ALIGN) * BLOCK_ALIGN
        line.open(lineFormat, bufferBytes)
        line.start()
        soundLine = line
        val adapter = object : DefaultAudioCallbackAdapter(BLOCK_ALIGN) {
            override fun onPlay(
                mediaPlayer: MediaPlayer?,
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

                    val frame = DesktopMyPlayerDspPipeline.process(samples, alignedByteCount)
                    val writeLen = (frame.size / BLOCK_ALIGN) * BLOCK_ALIGN
                    if (writeLen > 0) {
                        val activeLine = soundLine
                        if (activeLine != null) {
                            if (!activeLine.isRunning) activeLine.start()
                            activeLine.write(frame, 0, writeLen)
                        }
                    }
                } catch (t: Throwable) {
                    println("[DesktopAudioBackend] DSP play failed: ${t.message}")
                }
            }

            override fun pause(mediaPlayer: MediaPlayer?, pts: Long) {
                try { soundLine?.stop() } catch (_: Throwable) {}
            }

            override fun resume(mediaPlayer: MediaPlayer?, pts: Long) {
                try { soundLine?.start() } catch (_: Throwable) {}
            }

            override fun flush(mediaPlayer: MediaPlayer?, pts: Long) {
                try { soundLine?.flush() } catch (_: Throwable) {}
            }

            override fun drain(mediaPlayer: MediaPlayer?) {
                try { soundLine?.drain() } catch (_: Throwable) {}
            }

            override fun setVolume(volume: Float, mute: Boolean) {
                if (mute) {
                    DesktopMyPlayerDspPipeline.setPlayerVolume(0f)
                } else if (volume in 0f..1.5f) {
                    DesktopMyPlayerDspPipeline.setPlayerVolume(volume.coerceIn(0f, 1f))
                }
            }
        }
        player.audio().callback("S16N", CALLBACK_RATE, CALLBACK_CHANNELS, adapter)
        DesktopMyPlayerDspPipeline.beginStream(CALLBACK_RATE, CALLBACK_CHANNELS)
        DesktopMyPlayerDspPipeline.markCallbackMode()
        println("[DesktopAudioBackend] DSP callback chain live (S16N ${CALLBACK_RATE}Hz x${CALLBACK_CHANNELS}, buffer=${bufferBytes}B).")
    }

    fun pause() {
        isPaused = true
        pauseFadeJob?.cancel()
        if (isVlcAvailable && mediaPlayer != null) {
            if (MyPlayerAudioFxDspBridge.pauseFadeEnabled) {
                val fadeMs = (MyPlayerAudioFxDspBridge.pauseFadeDurationSec * 180f).toLong().coerceIn(80L, 450L)
                val startVol = userTargetVolume.coerceIn(0.05f, 1f)
                pauseFadeJob = backendScope.launch {
                    val steps = 8
                    val stepDelay = (fadeMs / steps).coerceAtLeast(10L)
                    for (s in 1..steps) {
                        val p = s.toFloat() / steps.toFloat()
                        val env = cos(p * (PI / 2.0)).toFloat()
                        applyImmediateVolume(startVol * env)
                        delay(stepDelay)
                    }
                    try {
                        mediaPlayer?.controls()?.setPause(true)
                    } catch (_: Exception) {
                        try { mediaPlayer?.controls()?.pause() } catch (_: Exception) {}
                    }
                    applyImmediateVolume(startVol)
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
                val targetVol = userTargetVolume.coerceIn(0.05f, 1f)
                if (MyPlayerAudioFxDspBridge.pauseFadeEnabled) {
                    applyImmediateVolume(0.05f)
                }
                mediaPlayer?.controls()?.play()
                if (MyPlayerAudioFxDspBridge.pauseFadeEnabled) {
                    val fadeMs = (MyPlayerAudioFxDspBridge.pauseFadeDurationSec * 180f).toLong().coerceIn(80L, 450L)
                    pauseFadeJob = backendScope.launch {
                        val steps = 8
                        val stepDelay = (fadeMs / steps).coerceAtLeast(10L)
                        for (s in 1..steps) {
                            val p = s.toFloat() / steps.toFloat()
                            val env = sin(p * (PI / 2.0)).toFloat()
                            applyImmediateVolume(targetVol * env)
                            delay(stepDelay)
                        }
                        applyImmediateVolume(targetVol)
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

    private fun applyImmediateVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        if (DesktopMyPlayerDspPipeline.isCallbackMode) {
            DesktopMyPlayerDspPipeline.setPlayerVolume(clamped)
            return
        }
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                val vlcVolume = (clamped * 100).toInt().coerceIn(0, 100)
                mediaPlayer?.audio()?.setVolume(vlcVolume)
            } catch (_: Exception) {}
        }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        userTargetVolume = clamped
        applyImmediateVolume(clamped)
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