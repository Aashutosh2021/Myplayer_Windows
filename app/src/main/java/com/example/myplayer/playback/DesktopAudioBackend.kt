package com.example.myplayer.playback

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

object DesktopAudioBackend {
    private var mediaPlayer: MediaPlayer? = null
    var isVlcAvailable: Boolean = false
        private set

    private var onCompletionCallback: (() -> Unit)? = null
    private var onPlayingChangedCallback: ((Boolean) -> Unit)? = null
    private var currentPath: String? = null
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
            val player = factory.mediaPlayers().newMediaPlayer()
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer?) {
                    onCompletionCallback?.invoke()
                }

                override fun playing(mediaPlayer: MediaPlayer?) {
                    onPlayingChangedCallback?.invoke(true)
                }

                override fun paused(mediaPlayer: MediaPlayer?) {
                    onPlayingChangedCallback?.invoke(false)
                }

                override fun stopped(mediaPlayer: MediaPlayer?) {
                    onPlayingChangedCallback?.invoke(false)
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
        currentPath = pathOrUri
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                val cleanPath = when {
                    pathOrUri.startsWith("file://") -> pathOrUri.removePrefix("file://")
                    pathOrUri.startsWith("file:/") -> pathOrUri.removePrefix("file:")
                    else -> pathOrUri
                }
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
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.controls()?.stop()
            } catch (_: Exception) {}
        }
        isSimulatedPlaying = false
        onPlayingChangedCallback?.invoke(false)
    }

    fun seekTo(positionMs: Long) {
        if (isVlcAvailable && mediaPlayer != null) {
            try {
                mediaPlayer?.controls()?.setTime(positionMs)
                return
            } catch (_: Exception) {}
        }
        simulatedStartTime = System.currentTimeMillis() - positionMs
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
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
