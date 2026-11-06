package com.example.myplayer.playback

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.myplayer.data.preferences.AudioPlaybackState
import com.example.myplayer.data.preferences.AudioPlaybackStore
import com.example.myplayer.data.preferences.AudioSettingsRepository
import com.example.myplayer.data.preferences.LoudnessMode
import com.example.myplayer.playback.dsp.LastWaveDspAudioProcessor
import com.example.myplayer.playback.dsp.MasterAudioDspProcessor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var playbackStateManager: PlaybackStateManager

    @Inject
    lateinit var playbackEventBus: PlaybackEventBus

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var currentAudioPlaybackState = AudioPlaybackState()

    // Master audio wiring (ported from Android). Desktop has no Hilt graph yet — Phase 3
    // wires AppContainer-provided instances, so every dependency is resolved defensively.
    private var audioPlaybackStore: AudioPlaybackStore? = null
    private var audioSettingsRepository: AudioSettingsRepository? = null
    private var audioFeatureManagers: AudioFeatureManagers? = null
    private var masterAudioDspProcessor: MasterAudioDspProcessor? = null
    private var lastWaveDspProcessor: LastWaveDspAudioProcessor? = null

    // Composed DSP inputs from the two settings sources (store wins over legacy config);
    // kept as volatiles so either collector can apply the full picture independently.
    @Volatile private var currentEqEnabled: Boolean = false
    @Volatile private var currentEqBandGains: List<Float>? = null
    @Volatile private var currentLegacyPreampDb: Float = 0f
    @Volatile private var currentStoreLoudnessActive: Boolean = false

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MyPlayer:MusicServiceWakeLock"
        ).apply { setReferenceCounted(false) }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(wifiLockMode, "MyPlayer:MusicServiceWifiLock").apply {
            setReferenceCounted(false)
        }

        val forwardingPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return if (command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) {
                    true
                } else {
                    super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() {
                if (player.mediaItemCount > 1 && player.hasNextMediaItem()) {
                    super.seekToNext()
                } else {
                    playbackEventBus.emit(PlaybackEvent.SkipToNextRequested)
                }
            }

            override fun seekToNextMediaItem() {
                seekToNext()
            }

            override fun seekToPrevious() {
                if (player.mediaItemCount > 1 && player.hasPreviousMediaItem()) {
                    super.seekToPrevious()
                } else {
                    playbackEventBus.emit(PlaybackEvent.SkipToPreviousRequested)
                }
            }

            override fun seekToPreviousMediaItem() {
                seekToPrevious()
            }
        }

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val sessionCommands = connectionResult.availableSessionCommands
                val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
                return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        emptyList(),
                        C.INDEX_UNSET,
                        C.TIME_UNSET
                    )
                )
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(callback)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                // We can broadcast song changes or update recent history here
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                updateServiceLocks(isPlaying || player.playWhenReady)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                val active = player.playWhenReady &&
                    (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)
                if (active) {
                    acquireLocks()
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                super.onRepeatModeChanged(repeatMode)
                getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
                    .edit().putInt("playback_repeat_mode", repeatMode).apply()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                super.onShuffleModeEnabledChanged(shuffleModeEnabled)
                getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
                    .edit().putBoolean("playback_shuffle_mode", shuffleModeEnabled).apply()
            }
        })

        initAudioFeatureManagers()
    }

    /**
     * Master Audio & Playback Feature Integration (ported from Android):
     * crossfade engine + 15-band EQ / loudness onto VLC's native equalizer + legacy
     * audio-settings (clarity/spatial) onto the dual-bud DSP chain.
     */
    private fun initAudioFeatureManagers() {
        // Resolve dependencies defensively — Android injects these with Hilt, but no DI
        // graph exists inside the desktop service yet (Phase 3 wires AppContainer).
        // The stores fall back to default flows and the DSP processors are constructible directly.
        try {
            audioPlaybackStore = AudioPlaybackStore()
            audioSettingsRepository = AudioSettingsRepository()
        } catch (e: Exception) {
            println("[MusicService] Audio settings stores unavailable (defaults in use): ${e.message}")
        }
        try {
            // Resolve the registered DSP singletons so these updates land in the audible
            // pipeline (DesktopMyPlayerDspPipeline chain) — the same instances the
            // AppContainer collectors feed, mirroring Android's shared Hilt singletons.
            masterAudioDspProcessor = com.example.myplayer.di.AppContainer.resolveDependency(MasterAudioDspProcessor::class.java) as? MasterAudioDspProcessor
            lastWaveDspProcessor = com.example.myplayer.di.AppContainer.resolveDependency(LastWaveDspAudioProcessor::class.java) as? LastWaveDspAudioProcessor
        } catch (e: Exception) {
            println("[MusicService] DSP processors unavailable: ${e.message}")
        }
        try {
            // shortcut: lyrics-download().lrc needs the shared OkHttpClient (Phase 3); null just makes that helper a no-op.
            audioFeatureManagers = AudioFeatureManagers()
        } catch (e: Exception) {
            println("[MusicService] Audio feature managers unavailable: ${e.message}")
        }

        // 1. Crossfade engine monitor — attached to the shared MediaController shim, not the
        // service ExoPlayer: the controller's volume/duration/position/seekToNext drive
        // DesktopAudioBackend (the audible VLC output), while the shim ExoPlayer's volume only
        // reaches the dual-bud JavaSound sink, which is null on the regular playback path.
        // shortcut: listeners (queue/transition events needed after seekToNext) live in MusicController; crossfade only needs the state it polls here.
        try {
            audioFeatureManagers?.attachCrossfadeMonitor(
                scope = serviceScope,
                player = MediaController.getShared(),
                isEnabled = { currentAudioPlaybackState.crossfadeEnabled },
                crossfadeDurationSec = currentAudioPlaybackState.crossfadeSeconds
            )
        } catch (e: Exception) {
            println("[MusicService] Crossfade monitor unavailable: ${e.message}")
        }

        // 2. Observe Master Audio & Playback Preferences (15-Band EQ, Loudness, System Effects)
        serviceScope.launch {
            audioPlaybackStore?.stateFlow?.collect { state ->
                currentAudioPlaybackState = state
                currentEqEnabled = state.eqEnabled
                currentEqBandGains = state.eqBandGains
                currentStoreLoudnessActive = state.loudnessMode != LoudnessMode.OFF
                masterAudioDspProcessor?.updateState(state)
                applyDspParams()
                // Android re-opens the audiofx control session per track (audioSessionId);
                // desktop toggleSystemAudioEffects is a no-op, so there is nothing to re-emit here.
                audioFeatureManagers?.toggleSystemAudioEffects(0, state.systemAudioEffects)
            }
        }

        // Audio Lab speed & pitch — Android sets player.playbackParameters; desktop routes it
        // into VLC's rate (pitch not supported by VLC).
        com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge.onUpdatePlaybackSpeedPitch = { speed, _ ->
            serviceScope.launch(Dispatchers.Main.immediate) {
                try {
                    DesktopAudioBackend.setSpeed(speed)
                } catch (e: Exception) {
                    println("[MusicService] Failed to set playback speed: ${e.message}")
                }
            }
        }

        // 3. Observe Legacy/Simple Audio Settings (Skip Silence, Clarity Presets, True-Dolby Spatial, Loudness Boost)
        serviceScope.launch {
            audioSettingsRepository?.configFlow?.collect { config ->
                // shortcut: the desktop Player shim has no skipSilenceEnabled knob (VLC silence-skip not ported); setting is accepted but inaudible.
                currentLegacyPreampDb = if (config.loudnessNormalize) {
                    (config.loudnessGainMb / 100f).coerceIn(-LOUDNESS_PREAMP_MAX_DB, LOUDNESS_PREAMP_MAX_DB)
                } else 0f
                lastWaveDspProcessor?.updateDspConfig(
                    preset = config.clarityPreset,
                    dolby = config.trueDolbySpatial,
                    width = config.spatialWidth
                )
                applyDspParams()
            }
        }
    }

    /** Composes both settings sources into one VLC DSP update: store EQ + store loudness
     * mode (0 dB preamp on desktop — no per-track ReplayGain tags yet), falling back to the
     * legacy loudness-boost config only when the store's loudness mode is OFF. */
    private fun applyDspParams() {
        val preampDb = if (currentStoreLoudnessActive) 0f else currentLegacyPreampDb
        DesktopAudioBackend.setDspParams(currentEqEnabled, currentEqBandGains, preampDb)
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == false) {
            // 4-hour safety timeout so locks never leak indefinitely
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
        }
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
        }
    }

    private fun updateServiceLocks(keepActive: Boolean) {
        if (keepActive) {
            acquireLocks()
        } else if (
            playbackStateManager.playbackMachineState.value != PlaybackMachineState.RESOLVING &&
            playbackStateManager.playbackMachineState.value != PlaybackMachineState.PREPARING &&
            playbackStateManager.playbackMachineState.value != PlaybackMachineState.RECOVERING
        ) {
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ) {
        val state = playbackStateManager.playbackMachineState.value
        val isResolvingOrRecovering = state == PlaybackMachineState.RESOLVING ||
            state == PlaybackMachineState.PREPARING ||
            state == PlaybackMachineState.RECOVERING
        val keepForeground = startInForegroundRequired || player.playWhenReady || isResolvingOrRecovering
        if (keepForeground) {
            acquireLocks()
        }
        try {
            super.onUpdateNotification(session, keepForeground)
        } catch (_: Exception) {
            // Suppress ForegroundServiceStartNotAllowedException on Android 12+ background transitions
        }
    }

    override fun onDestroy() {
        releaseLocks()
        com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge.onUpdatePlaybackSpeedPitch = null
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If the user swipes away the app from recent apps, we should stop the service if it's paused.
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
            releaseLocks()
            stopSelf()
        }
    }
}
