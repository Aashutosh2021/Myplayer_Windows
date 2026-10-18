package com.example.myplayer.di

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.myplayer.data.local.AppDatabase
import com.example.myplayer.data.local.DesktopAppDatabase
import com.example.myplayer.data.local.dao.*
import com.example.myplayer.data.local.datastore.SettingsDataStore
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.repository.*
import com.example.myplayer.playback.*
import com.example.myplayer.security.NetworkSecurityManager
import com.example.myplayer.security.StringEncryptionManager
import okhttp3.OkHttpClient
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object AppContainer {
    val viewModels = mutableMapOf<Class<*>, ViewModel>()
    private val singletons = mutableMapOf<Class<*>, Any>()

    fun initialize() {
        println("[AppContainer] Initializing desktop dependencies...")
        try {
            DesktopAudioBackend.init()
        } catch (e: Throwable) {
            println("[AppContainer] Warning initializing audio: ${e.message}")
        }

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

        val okHttpClient = NetworkModule.provideOkHttpClient(NetworkSecurityManager())
        register(OkHttpClient::class.java, okHttpClient)

        val settingsDataStore = SettingsDataStore(appContext)
        register(SettingsDataStore::class.java, settingsDataStore)

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

        val downloadRepository = DownloadRepository(appContext, db.downloadedSongDao(), db.songDao(), db.cachedLyricsDao())
        register(DownloadRepository::class.java, downloadRepository)

        val losslessResolver = LosslessStreamResolver(okHttpClient)
        register(LosslessStreamResolver::class.java, losslessResolver)

        val playbackSourceResolver = PlaybackSourceResolver(appContext, db.downloadedSongDao(), db.songDao(), innertubeApi, losslessResolver)
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

        val lastFmConfig = com.example.myplayer.data.recommendation.lastfm.LastFmConfig(stringEncryptionManager)
        val lastFmCache = com.example.myplayer.data.recommendation.lastfm.LastFmCache()
        val lastFmChartSource = com.example.myplayer.data.recommendation.lastfm.LastFmChartSource(okHttpClient, lastFmConfig, lastFmCache)
        val lastFmTrackMatcher = com.example.myplayer.data.recommendation.lastfm.LastFmTrackMatcher(innertubeApi)
        val lastFmSource = com.example.myplayer.data.recommendation.api.LastFmRecommendationSource(lastFmChartSource, lastFmTrackMatcher, innertubeApi)
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

        println("[AppContainer] Desktop dependencies initialized.")
    }

    fun cleanup() {
        println("[AppContainer] Cleaning up desktop dependencies...")
        try {
            DesktopAudioBackend.release()
        } catch (_: Throwable) {}
    }

    fun <T : Any> register(type: Class<T>, instance: T) {
        singletons[type] = instance
    }

    @Suppress("UNCHECKED_CAST")
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
        return constructor.newInstance(*params) as VM
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
