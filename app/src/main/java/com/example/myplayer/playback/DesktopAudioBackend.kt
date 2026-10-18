package com.example.myplayer.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

object DesktopAudioBackend {
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerFactory: MediaPlayerFactory? = null
    var isVlcAvailable: Boolean = false
        private set

    private val backendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            val vlcDir = File("C:\\Program Files\\VideoLAN\\VLC")
            if (vlcDir.exists()) {
                System.setProperty("jna.library.path", vlcDir.absolutePath)
            }
            NativeDiscovery().discover()
            val factory = MediaPlayerFactory("--no-video", "--quiet")
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
        } catch (t: Throwable) {
            println("[DesktopAudioBackend] VLC native engine not available: ${t.message}. Using fallback playback.")
            isVlcAvailable = false
        }
    }

    fun play(pathOrUri: String, startPositionMs: Long = 0L) {
        val cleanPath = when {
            pathOrUri.startsWith("file://") -> pathOrUri.removePrefix("file://")
            pathOrUri.startsWith("file:/") -> pathOrUri.removePrefix("file:")
            else -> pathOrUri
        }
        if (cleanPath == currentPath && isPaused && startPositionMs <= 0L) {
            resume()
            return
        }
        currentPath = cleanPath
        isPaused = false
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.media()?.play(cleanPath)
                if (startPositionMs > 0L) {
                    mediaPlayer?.controls()?.setTime(startPositionMs)
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
        return 180_000L // 3 minutes default duration if unknown
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

    fun setOnPlayingChanged(callback: (Boolean) -> Unit) {
        onPlayingChangedCallback = callback
    }

    fun release() {
        try {
            val player = mediaPlayer
            val factory = mediaPlayerFactory
            mediaPlayer = null
            mediaPlayerFactory = null
            backendScope.launch(Dispatchers.IO) {
                try {
                    player?.controls()?.stop()
                    player?.release()
                } catch (_: Throwable) {}
                try {
                    factory?.release()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
