package com.example.myplayer.playback

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.DefaultAudioCallbackAdapter
import com.example.myplayer.myplayerfx.DesktopMyPlayerDspPipeline
import java.io.File
import java.net.URI
import java.net.URLDecoder
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.ln

object DesktopAudioBackend {
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerFactory: MediaPlayerFactory? = null
    var isVlcAvailable: Boolean = false
        private set

    /**
     * Desktop DSP warm path: VLC's callback aout (amem) pushes S16N PCM into
     * DesktopMyPlayerDspPipeline (master EQ -> 3D Spatial / Atmos -> Audio Lab spatial/reverb/loudness -> volume stage)
     * and the output is written to a Java Sound line. When this fails the player silently falls
     * back to VLC's native output (which also carries the native equalizer with headroom protection).
     */
    private const val CALLBACK_RATE = 48000
    private const val CALLBACK_CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2
    private const val BLOCK_ALIGN = CALLBACK_CHANNELS * BYTES_PER_SAMPLE // 4 bytes per stereo frame
    private var soundLine: SourceDataLine? = null
    private var currentRate: Float = 1f

    private val backendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // VLC's native band table (fixed frequencies, Hz): 60, 170, 310, 600, 1k, 3k, 6k, 12k, 14k, 16k.
    private val VLC_BAND_CENTERS = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
    private const val VLC_EQ_BAND_COUNT = 10
    private const val VLC_AMP_MIN_DB = -20f // libvlc equalizer band/preamp gain range in dB
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

    fun init() {
        try {
            // Ensure JNA passes UTF-8 strings to libVLC on Windows
            System.setProperty("jna.encoding", "UTF-8")
            val vlcDir = File("C:\\Program Files\\VideoLAN\\VLC")
            if (vlcDir.exists()) {
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
                // Mobile DSP chain (Audio Lab / Master EQ / 3D Spatial) needs raw PCM; switch VLC to its
                // callback output and drive a Java Sound line through the Kotlin DSP processors.
                enableCallbackAudio()
            } catch (t: Throwable) {
                println("[DesktopAudioBackend] DSP callback output unavailable: ${t.message}. VLC native output stays active.")
            }
        } catch (t: Throwable) {
            println("[DesktopAudioBackend] VLC native engine not available: ${t.message}. Using fallback playback.")
            isVlcAvailable = false
        }
    }

    /**
     * Resolves a Windows/Desktop local file path or `file:` URI into an existing [File] on disk,
     * properly decoding `%20` spaces and stripping leading `/C:/` slashes on Windows.
     */
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

        // 1. Direct File check
        candidates.add(File(trimmed))

        // 2. If it starts with file:, try URI constructor and URL-decoded path
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
            // 3. Also try URL-decoding and stripping leading /C:/ if present
            val decoded = runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrDefault(trimmed.replace("%20", " "))
            val winFixed = if (decoded.matches(Regex("^/[a-zA-Z]:[\\\\/].*"))) decoded.substring(1) else decoded
            candidates.add(File(winFixed))
        }

        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile && candidate.length() > 0L) {
                return candidate
            }
        }

        // 4. Fallback: check ~/.myplayer/files/Music by file name
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
        // 1. Check if pathOrUri resolves to a local / downloaded file on disk.
        // If so, serve it via DesktopChunkStreamProxy.wrapLocalFile(localFile) (`http://127.0.0.1:<port>/local?id=...`)
        // so libVLC plays it over loopback HTTP with 0 Windows path, `%20`, or Unicode encoding issues!
        val localFile = resolveLocalFile(pathOrUri)
        val cleanPath = if (localFile != null) {
            DesktopChunkStreamProxy.wrapLocalFile(localFile)
        } else {
            val rawCleanPath = when {
                pathOrUri.startsWith("file://") -> pathOrUri.removePrefix("file://")
                pathOrUri.startsWith("file:/") -> pathOrUri.removePrefix("file:")
                else -> pathOrUri
            }
            // Route online HTTP/HTTPS streams through our Spotify-style 9-Second Chunk Proxy
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
                // VLC resets rate per media; carry the Audio Lab speed over.
                if (abs(currentRate - 1f) > 0.001f) {
                    try { mediaPlayer?.controls()?.setRate(currentRate) } catch (_: Exception) {}
                }
                return
            } catch (e: Exception) {
                println("[DesktopAudioBackend] Error playing via VLC: ${e.message}")
            }
        }
        // Fallback simulation timer
        simulatedStartTime = System.currentTimeMillis() - startPositionMs
        isSimulatedPlaying = true
        onPlayingChangedCallback?.invoke(true)
    }

    /**
     * Switches VLC to its callback audio output: PCM streams through the Kotlin DSP chain.
     *
     * CRITICAL FIX FOR "FATA HUA SPEAKER" (AUDIO DISTORTION):
     * 1. In vlcj's `DefaultAudioCallbackAdapter(blockSize)`, the 3rd parameter to `onPlay` is
     *    `sampleCount` (number of stereo frames), while `samples` is allocated with
     *    `sampleCount * blockSize` bytes (`sampleCount * 4` bytes). Passing `sampleCount` as
     *    `byteCount` previously dropped 75% of every audio frame and misaligned odd byte counts!
     * 2. `line.open` buffer size is now properly divided by 1000 (`~150 ms` = 28,800 bytes aligned
     *    to 4-byte stereo frames) instead of 23 MB (120 seconds).
     */
    private fun enableCallbackAudio() {
        val player = requireNotNull(mediaPlayer)
        DesktopMyPlayerDspPipeline.install()
        val lineFormat = javax.sound.sampled.AudioFormat(
            CALLBACK_RATE.toFloat(), 16, CALLBACK_CHANNELS, true, false
        )
        val line = AudioSystem.getSourceDataLine(lineFormat)
        // ~150 ms hardware buffer aligned to 4-byte stereo frames: smooth glitch-free playback & snappy pause/seek
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
                    // `samples` length is `sampleCount * BLOCK_ALIGN` bytes; ensure strict 4-byte stereo frame alignment.
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
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.controls()?.pause()
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
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.controls()?.play()
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

    fun setVolume(volume: Float) {
        if (DesktopMyPlayerDspPipeline.isCallbackMode) {
            DesktopMyPlayerDspPipeline.setPlayerVolume(volume)
            return
        }
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                val vlcVolume = (volume * 100).toInt().coerceIn(0, 100)
                mediaPlayer?.audio()?.setVolume(vlcVolume)
            } catch (_: Exception) {}
        }
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
                // Automatic anti-clipping headroom when EQ bands are boosted
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
