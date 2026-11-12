package com.example.myplayer.di

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.myplayer.data.backup.RedownloadQueueWorker
import com.example.myplayer.data.local.AppDatabase
import com.example.myplayer.data.local.DesktopAppDatabase
import com.example.myplayer.data.local.dao.*
import com.example.myplayer.data.local.datastore.SettingsDataStore
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.repository.*
import com.example.myplayer.myplayerfx.AudioFxService
import com.example.myplayer.playback.*
import com.example.myplayer.playback.dsp.SpatialAudioProcessor
import com.example.myplayer.playback.dsp.SpatialParams
import com.example.myplayer.security.NetworkSecurityManager
import com.example.myplayer.security.StringEncryptionManager
import com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object AppContainer {
    val viewModels = java.util.concurrent.ConcurrentHashMap<Class<*>, ViewModel>()
    private val singletons = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var masterAudioState = com.example.myplayer.data.preferences.AudioPlaybackState()
    @Volatile private var masterLegacyPreampDb: Float = 0f

    private fun applyMasterDsp() {
        val preampDb = if (masterAudioState.loudnessMode != com.example.myplayer.data.preferences.LoudnessMode.OFF) {
            0f
        } else {
            masterLegacyPreampDb + if (MyPlayerAudioFxDspBridge.eqEnabled) MyPlayerAudioFxDspBridge.eqPreampDb else 0f
        }
        val anyEq = masterAudioState.eqEnabled || MyPlayerAudioFxDspBridge.eqEnabled
        val gains = if (MyPlayerAudioFxDspBridge.eqEnabled) {
            val lab15 = MyPlayerAudioFxDspBridge.getInterpolated15BandGains()
            List(15) { i ->
                val base = if (masterAudioState.eqEnabled) masterAudioState.eqBandGains.getOrElse(i) { 0f } else 0f
                (base + lab15[i]).coerceIn(-12f, 12f)
            }
        } else {
            masterAudioState.eqBandGains
        }
        com.example.myplayer.playback.DesktopAudioBackend.setDspParams(anyEq, gains, preampDb)
    }

    fun initialize() {
        println("[AppContainer] Initializing desktop dependencies...")

        val appContext = Context()
        register(Context::class.java, appContext)

        val db = DesktopAppDatabase()
        register(AppDatabase::class.java, db)
        register(DesktopAppDatabase::class.java, db)

        register(SongDao::class.java, db.songDao())
        register(PlaylistDao::class.java, db.playlistDao())
        register(FavoriteDao::class.java, db.favoriteDao())
        register(FolderDao::class.java, db.folderDao())
        register(RecentHistoryDao::class.java, db.recentHistoryDao())
        register(DownloadedSongDao::class.java, db.downloadedSongDao())
        register(RecentSearchDao::class.java, db.recentSearchDao())
        register(CachedLyricsDao::class.java, db.cachedLyricsDao())
        register(CachedRecommendationDao::class.java, db.cachedRecommendationDao())
        register(PlayedSongHistoryDao::class.java, db.playedSongHistoryDao())

        val okHttpClient = NetworkModule.provideOkHttpClient(NetworkSecurityManager())
        register(OkHttpClient::class.java, okHttpClient)

        val settingsDataStore = SettingsDataStore(appContext)
        register(SettingsDataStore::class.java, settingsDataStore)

        com.example.myplayer.ui.theme.ThemeManager.onThemeChanged = { isDark ->
            scope.launch {
                settingsDataStore.setDarkMode(isDark)
            }
        }
        scope.launch {
            val savedDark = settingsDataStore.isDarkMode.firstOrNull()
            if (savedDark != null) {
                com.example.myplayer.ui.theme.ThemeManager.isDarkMode = savedDark
            }
        }

        val stringEncryptionManager = StringEncryptionManager()
        register(StringEncryptionManager::class.java, stringEncryptionManager)

        val innertubeApi = InnertubeApi(okHttpClient, stringEncryptionManager)
        register(InnertubeApi::class.java, innertubeApi)

        val mediaScanner = MediaScanner(appContext, db.songDao(), db.folderDao())
        register(MediaScanner::class.java, mediaScanner)

        val songRepository = SongRepository(db.songDao(), db.folderDao(), mediaScanner)
        register(SongRepository::class.java, songRepository)

        val playlistRepository = PlaylistRepository(db.playlistDao(), db.songDao(), db.downloadedSongDao())
        register(PlaylistRepository::class.java, playlistRepository)

        val favoriteRepository = FavoriteRepository(db.favoriteDao(), db.songDao())
        register(FavoriteRepository::class.java, favoriteRepository)

        val recentHistoryRepository = RecentHistoryRepository(db.recentHistoryDao())
        register(RecentHistoryRepository::class.java, recentHistoryRepository)

        val musicRepository = MusicRepository(songRepository, playlistRepository, favoriteRepository, recentHistoryRepository)
        register(MusicRepository::class.java, musicRepository)

        val downloadRepository = DownloadRepository(appContext, db.downloadedSongDao(), db.songDao(), db.cachedLyricsDao(), okHttpClient)
        register(DownloadRepository::class.java, downloadRepository)

        val losslessResolver = LosslessStreamResolver(okHttpClient)
        register(LosslessStreamResolver::class.java, losslessResolver)

        val audioSettingsRepository = com.example.myplayer.data.preferences.AudioSettingsRepository(appContext)
        register(com.example.myplayer.data.preferences.AudioSettingsRepository::class.java, audioSettingsRepository)

        val playbackSourceResolver = PlaybackSourceResolver(appContext, db.downloadedSongDao(), db.songDao(), innertubeApi, losslessResolver, audioSettingsRepository)
        register(PlaybackSourceResolver::class.java, playbackSourceResolver)

        val mediaItemFactory = MediaItemFactory()
        register(MediaItemFactory::class.java, mediaItemFactory)

        val playbackStateManager = PlaybackStateManager()
        register(PlaybackStateManager::class.java, playbackStateManager)

        val playbackRouter = PlaybackRouter(playbackSourceResolver, mediaItemFactory, playbackStateManager)
        register(PlaybackRouter::class.java, playbackRouter)

        val playbackCompletionGuard = PlaybackCompletionGuard()
        register(PlaybackCompletionGuard::class.java, playbackCompletionGuard)

        val sleepTimerManager = SleepTimerManager()
        register(SleepTimerManager::class.java, sleepTimerManager)

        val playbackErrorHandler = PlaybackErrorHandler(appContext, true)
        register(PlaybackErrorHandler::class.java, playbackErrorHandler)

        val playbackEventBus = PlaybackEventBus()
        register(PlaybackEventBus::class.java, playbackEventBus)

        val musicController = MusicController(
            context = appContext,
            musicRepository = musicRepository,
            settingsDataStore = settingsDataStore,
            playbackCompletionGuard = playbackCompletionGuard,
            sleepTimerManager = sleepTimerManager,
            playbackErrorHandler = playbackErrorHandler,
            playbackStateManager = playbackStateManager,
            playbackRouter = playbackRouter,
            playbackEventBus = playbackEventBus,
            downloadedSongDao = db.downloadedSongDao(),
            playedSongHistoryDao = db.playedSongHistoryDao(),
            playbackSourceResolver = playbackSourceResolver,
            mediaItemFactory = mediaItemFactory
        )
        register(MusicController::class.java, musicController)

        val recommendationLogger = com.example.myplayer.data.recommendation.logging.RecommendationLogger()
        register(com.example.myplayer.data.recommendation.logging.RecommendationLogger::class.java, recommendationLogger)

        val queuePolicy = com.example.myplayer.data.recommendation.queue.DefaultQueuePolicy()
        register(com.example.myplayer.data.recommendation.queue.DefaultQueuePolicy::class.java, queuePolicy)
        register(com.example.myplayer.data.recommendation.queue.RecommendationQueuePolicy::class.java, queuePolicy)

        val rankingStrategy = com.example.myplayer.data.recommendation.strategy.SmartRankingStrategy()
        register(com.example.myplayer.data.recommendation.strategy.SmartRankingStrategy::class.java, rankingStrategy)
        register(com.example.myplayer.data.recommendation.strategy.RecommendationStrategy::class.java, rankingStrategy)

        val recommendationCache = com.example.myplayer.data.recommendation.cache.RecommendationCache(recommendationLogger, db.cachedRecommendationDao())
        register(com.example.myplayer.data.recommendation.cache.RecommendationCache::class.java, recommendationCache)

        val aiModel = com.example.myplayer.data.recommendation.ai.OnDeviceMusicAiModel(appContext, innertubeApi)
        register(com.example.myplayer.data.recommendation.ai.OnDeviceMusicAiModel::class.java, aiModel)

        val lastFmSource = com.example.myplayer.data.recommendation.api.LastFmRecommendationSource(aiModel)
        register(com.example.myplayer.data.recommendation.api.LastFmRecommendationSource::class.java, lastFmSource)

        val youTubeSource = com.example.myplayer.data.recommendation.api.YouTubeRecommendationSource(innertubeApi)
        register(com.example.myplayer.data.recommendation.api.YouTubeRecommendationSource::class.java, youTubeSource)

        val recoHistory = com.example.myplayer.data.recommendation.queue.RecommendationHistory()
        register(com.example.myplayer.data.recommendation.queue.RecommendationHistory::class.java, recoHistory)

        val userStateDetector = com.example.myplayer.data.recommendation.state.RecommendationUserStateDetector(recentHistoryRepository, recoHistory, playbackStateManager)
        register(com.example.myplayer.data.recommendation.state.RecommendationUserStateDetector::class.java, userStateDetector)

        val sourceRouter = com.example.myplayer.data.recommendation.api.RecommendationSourceRouter(userStateDetector, lastFmSource, youTubeSource)
        register(com.example.myplayer.data.recommendation.api.RecommendationSourceRouter::class.java, sourceRouter)
        register(com.example.myplayer.data.recommendation.api.RecommendationSource::class.java, sourceRouter)

        val recoMetrics = com.example.myplayer.data.recommendation.logging.RecommendationMetrics()
        val recommendationEngine = com.example.myplayer.data.recommendation.engine.RecommendationEngine(
            sourceRouter,
            com.example.myplayer.data.recommendation.mapper.RecommendationMapper(),
            recommendationLogger,
            recoMetrics
        )
        register(com.example.myplayer.data.recommendation.engine.RecommendationEngine::class.java, recommendationEngine)

        val recommendationRepository = com.example.myplayer.data.recommendation.RecommendationRepository(recommendationEngine, recommendationCache)
        register(com.example.myplayer.data.recommendation.RecommendationRepository::class.java, recommendationRepository)

        val recommendationManager = com.example.myplayer.data.recommendation.RecommendationManager(recommendationRepository)
        register(com.example.myplayer.data.recommendation.RecommendationManager::class.java, recommendationManager)

        val queueMetrics = com.example.myplayer.data.recommendation.queue.QueueMetrics()
        val recentPlaybackWindow = com.example.myplayer.data.recommendation.queue.RecentPlaybackWindow()
        val queueManager = com.example.myplayer.data.recommendation.queue.RecommendationQueueManager(
            queue = com.example.myplayer.data.recommendation.queue.RecommendationQueue(),
            policy = queuePolicy,
            history = recoHistory,
            filter = com.example.myplayer.data.recommendation.queue.RecommendationFilter(recoHistory, recentPlaybackWindow, queueMetrics),
            repository = recommendationRepository,
            validator = com.example.myplayer.data.recommendation.engine.RecommendationValidator(recommendationLogger, recoMetrics),
            strategy = rankingStrategy,
            metrics = queueMetrics,
            recentPlaybackWindow = recentPlaybackWindow,
            health = com.example.myplayer.data.recommendation.queue.RecommendationQueueHealth(recommendationLogger),
            logger = recommendationLogger
        )
        register(com.example.myplayer.data.recommendation.queue.RecommendationQueueManager::class.java, queueManager)

        val playbackRepository = com.example.myplayer.data.recommendation.playback.RecommendationPlaybackRepository()
        register(com.example.myplayer.data.recommendation.playback.RecommendationPlaybackRepository::class.java, playbackRepository)

        val recommendationCoordinator = com.example.myplayer.data.recommendation.RecommendationCoordinator(
            context = appContext,
            playbackEventBus = playbackEventBus,
            settingsDataStore = settingsDataStore,
            recommendationManager = recommendationManager,
            queueManager = queueManager,
            playbackRepository = playbackRepository,
            logger = recommendationLogger,
            innertubeApi = innertubeApi
        )
        register(com.example.myplayer.data.recommendation.RecommendationCoordinator::class.java, recommendationCoordinator)

        val audioOutputDevice: com.example.myplayer.dualbud.audio.AudioOutputDevice = com.example.myplayer.dualbud.audio.AndroidAudioTrackOutput()
        register(com.example.myplayer.dualbud.audio.AudioOutputDevice::class.java, audioOutputDevice)

        val dualChannelPlaybackManager = com.example.myplayer.dualbud.DualChannelPlaybackManager(
            context = appContext,
            cacheDataSourceFactory = null,
            outputDevice = audioOutputDevice
        )
        register(com.example.myplayer.dualbud.DualChannelPlaybackManager::class.java, dualChannelPlaybackManager)

        val syncDiscovery = com.example.myplayer.sync.SyncDiscovery(appContext)
        register(com.example.myplayer.sync.SyncDiscovery::class.java, syncDiscovery)

        val syncTrackMatcher = com.example.myplayer.sync.SyncTrackMatcher(db.songDao(), db.downloadedSongDao(), appContext)
        register(com.example.myplayer.sync.SyncTrackMatcher::class.java, syncTrackMatcher)

        val syncPlayManager = com.example.myplayer.sync.SyncPlayManager(
            discovery = syncDiscovery,
            trackMatcher = syncTrackMatcher,
            musicController = musicController,
            innertubeApi = innertubeApi,
            downloadRepository = downloadRepository,
            downloadedSongDao = db.downloadedSongDao()
        )
        register(com.example.myplayer.sync.SyncPlayManager::class.java, syncPlayManager)

        // ===== Master audio, AudioFxService, 3D Spatial / Atmos, Crossfade & Speed wiring =====
        val audioPlaybackStore = com.example.myplayer.data.preferences.AudioPlaybackStore(appContext)
        register(com.example.myplayer.data.preferences.AudioPlaybackStore::class.java, audioPlaybackStore)

        // Explicitly register AudioFxService BEFORE DesktopAudioBackend.init() so all Audio Lab
        // DSP processors (Reverb, Spatial, Loudness, VolumeCurve) attach cleanly!
        val audioFxService = AudioFxService()
        register(AudioFxService::class.java, audioFxService)
        MyPlayerAudioFxDspBridge.attach(audioFxService)

        // Wire Audio Lab Speed & Pitch hook directly in AppContainer (since MusicService is not started on Desktop)
        MyPlayerAudioFxDspBridge.onUpdatePlaybackSpeedPitch = { speed, _ ->
            try {
                DesktopAudioBackend.setSpeed(speed)
            } catch (_: Throwable) {}
        }

        val centralDsp = com.example.myplayer.playback.dsp.MasterAudioDspProcessor()
        val centralLastWave = com.example.myplayer.playback.dsp.LastWaveDspAudioProcessor()
        val centralSpatial = SpatialAudioProcessor()
        register(com.example.myplayer.playback.dsp.MasterAudioDspProcessor::class.java, centralDsp)
        register(com.example.myplayer.playback.dsp.LastWaveDspAudioProcessor::class.java, centralLastWave)
        register(SpatialAudioProcessor::class.java, centralSpatial)
        MyPlayerAudioFxDspBridge.attach(audioFxService, centralDsp)

        MyPlayerAudioFxDspBridge.onUpdateCrossfadeConfig = { enabled, seconds ->
            scope.launch {
                runCatching { audioPlaybackStore.setCrossfade(enabled, seconds) }
            }
        }

        val audioFeatureManagers = com.example.myplayer.playback.AudioFeatureManagers(okHttpClient)
        register(com.example.myplayer.playback.AudioFeatureManagers::class.java, audioFeatureManagers)

        // Crossfade engine — checks BOTH AudioPlaybackStore AND Audio Lab (MyPlayerAudioFxDspBridge)
        try {
            audioFeatureManagers.attachCrossfadeMonitor(
                scope = scope,
                player = androidx.media3.session.MediaController.getShared(),
                isEnabled = { masterAudioState.crossfadeEnabled || MyPlayerAudioFxDspBridge.isAnyCrossfadeActive },
                crossfadeDurationSec = masterAudioState.crossfadeSeconds,
                crossfadeSecondsProvider = {
                    if (MyPlayerAudioFxDspBridge.isAnyCrossfadeActive) MyPlayerAudioFxDspBridge.crossfadeSeconds
                    else masterAudioState.crossfadeSeconds.toFloat()
                },
                onTriggerNextTrack = { musicController.skipToNext() }
            )
        } catch (e: Throwable) {
            println("[AppContainer] Crossfade monitor unavailable: ${e.message}")
        }

        scope.launch {
            audioPlaybackStore.stateFlow.collect { state ->
                masterAudioState = state
                centralDsp.updateState(state)
                centralSpatial.updateParams(
                    SpatialParams(
                        enabled = state.spatialAudioEnabled && !state.claritySpatialBypass,
                        mode = state.spatialMode,
                        speakerAngle = state.spatialSpeakerAngle,
                        amount = state.spatialAmount,
                        surround = state.dolbySurroundAtmos && !state.claritySpatialBypass
                    )
                )
                applyMasterDsp()
            }
        }
        scope.launch {
            audioSettingsRepository.configFlow.collect { config ->
                masterLegacyPreampDb = if (config.loudnessNormalize) {
                    (config.loudnessGainMb / 100f).coerceIn(-20f, 20f)
                } else 0f
                centralLastWave.updateDspConfig(
                    preset = config.clarityPreset,
                    dolby = config.trueDolbySpatial,
                    width = config.spatialWidth
                )
                applyMasterDsp()
            }
        }

        val spotifyIntegrationManager = com.example.myplayer.data.spotify.SpotifyIntegrationManager(appContext, aiModel, db.songDao(), db.playlistDao(), db.favoriteDao())
        register(com.example.myplayer.data.spotify.SpotifyIntegrationManager::class.java, spotifyIntegrationManager)

        val meldListenTogetherManager = com.example.myplayer.sync.MeldListenTogetherManager(musicController, syncTrackMatcher, innertubeApi, okHttpClient)
        register(com.example.myplayer.sync.MeldListenTogetherManager::class.java, meldListenTogetherManager)

        val preferencesManager = com.example.myplayer.data.local.prefs.PreferencesManager(appContext)
        register(com.example.myplayer.data.local.prefs.PreferencesManager::class.java, preferencesManager)

        val updateChecker = com.example.myplayer.data.update.UpdateChecker(appContext, okHttpClient)
        register(com.example.myplayer.data.update.UpdateChecker::class.java, updateChecker)

        val backupRestoreManager = com.example.myplayer.data.backup.BackupRestoreManager(
            context = appContext,
            db = db,
            settingsDataStore = settingsDataStore,
            preferencesManager = preferencesManager,
            downloadRepository = downloadRepository,
            innertubeApi = innertubeApi
        )
        register(com.example.myplayer.data.backup.BackupRestoreManager::class.java, backupRestoreManager)

        val onlineSearchRepository = com.example.myplayer.data.repository.OnlineSearchRepository(innertubeApi, db.recentSearchDao())
        register(com.example.myplayer.data.repository.OnlineSearchRepository::class.java, onlineSearchRepository)

        val lyricsRepository = com.example.myplayer.data.lyrics.LyricsRepository(appContext, okHttpClient, db.cachedLyricsDao())
        register(com.example.myplayer.data.lyrics.LyricsRepository::class.java, lyricsRepository)

        val hybridLibraryRepository = com.example.myplayer.data.repository.HybridLibraryRepository(db.downloadedSongDao(), db.songDao())
        register(com.example.myplayer.data.repository.HybridLibraryRepository::class.java, hybridLibraryRepository)

        RedownloadQueueWorker.resumeIfPending(appContext)

        try {
            com.example.myplayer.playback.DesktopAudioBackend.init()
        } catch (e: Throwable) {
            println("[AppContainer] Warning initializing audio: ${e.message}")
        }

        println("[AppContainer] Desktop dependencies initialized.")
    }

    fun cleanup() {
        println("[AppContainer] Cleaning up desktop dependencies...")
        try {
            resolveDependency(com.example.myplayer.dualbud.DualChannelPlaybackManager::class.java)?.let {
                (it as? com.example.myplayer.dualbud.DualChannelPlaybackManager)?.disableDualBudMode()
            }
        } catch (_: Throwable) {}
        try {
            DesktopAudioBackend.release()
        } catch (_: Throwable) {}
    }

    fun <T : Any> register(type: Class<T>, instance: T) {
        singletons[type] = instance
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(type: Class<T>): T? = resolveDependency(type) as? T

    fun <VM : ViewModel> getViewModel(modelClass: Class<VM>): VM {
        return viewModels.getOrPut(modelClass) {
            createViewModel(modelClass)
        } as VM
    }

    private fun <VM : ViewModel> createViewModel(modelClass: Class<VM>): VM {
        val constructor = modelClass.declaredConstructors.maxByOrNull { it.parameterCount }
            ?: throw IllegalStateException("No constructor found for ${modelClass.name}")
        constructor.isAccessible = true
        val params = constructor.genericParameterTypes.map { paramType ->
            resolveType(paramType)
        }.toTypedArray()
        try {
            return constructor.newInstance(*params) as VM
        } catch (e: Exception) {
            println("[AppContainer] Failed to construct ${modelClass.name} with params: ${params.joinToString()}")
            throw e
        }
    }

    private fun resolveType(genericType: Type): Any? {
        if (genericType is ParameterizedType) {
            val rawType = genericType.rawType as? Class<*>
            if (rawType != null && (rawType.name == "dagger.Lazy" || rawType.name == "javax.inject.Provider")) {
                val argType = genericType.actualTypeArguments.firstOrNull()
                val targetClass = argType as? Class<*> ?: Any::class.java
                return object : dagger.Lazy<Any?>, javax.inject.Provider<Any?> {
                    override fun get(): Any? = resolveDependency(targetClass)
                }
            }
        }
        val clazz = (genericType as? Class<*>) ?: (genericType as? ParameterizedType)?.rawType as? Class<*> ?: Any::class.java
        return resolveDependency(clazz)
    }

    fun resolveDependency(type: Class<*>): Any? {
        if (type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java) return false
        if (type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java) return 0
        if (type == java.lang.Long.TYPE || type == java.lang.Long::class.java) return 0L
        if (type == java.lang.Float.TYPE || type == java.lang.Float::class.java) return 0f
        if (type == java.lang.Double.TYPE || type == java.lang.Double::class.java) return 0.0
        if (type == java.lang.String::class.java) return ""

        singletons[type]?.let { return it }
        singletons.values.firstOrNull { type.isInstance(it) }?.let { return it }

        val constructors = type.declaredConstructors
        val candidates = constructors.sortedWith(
            compareByDescending<java.lang.reflect.Constructor<*>> { c ->
                c.isAnnotationPresent(javax.inject.Inject::class.java)
            }.thenByDescending { c ->
                val hasTestFlag = c.parameterTypes.any { it == java.lang.Boolean.TYPE }
                if (hasTestFlag) -1 else c.parameterCount
            }
        )

        for (constructor in candidates) {
            try {
                constructor.isAccessible = true
                val args = constructor.genericParameterTypes.map { resolveType(it) }.toTypedArray()
                val instance = constructor.newInstance(*args)
                singletons[type] = instance
                return instance
            } catch (_: Exception) {}
        }
        println("[AppContainer] Warning: could not instantiate ${type.name}")
        return null
    }
}