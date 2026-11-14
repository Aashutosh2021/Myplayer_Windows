package com.example.myplayer.ui.screens.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplayer.data.local.dao.PlayedSongHistoryDao
import com.example.myplayer.data.local.datastore.SettingsDataStore
import com.example.myplayer.data.local.entity.SongEntity
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.online.model.OnlineSong
import com.example.myplayer.data.recommendation.RecommendationCoordinator
import com.example.myplayer.data.recommendation.ai.OnDeviceMusicAiModel
import com.example.myplayer.data.recommendation.api.LastFmRecommendationSource
import com.example.myplayer.data.recommendation.cache.RecommendationCache
import com.example.myplayer.data.recommendation.model.RecommendationSong
import com.example.myplayer.data.recommendation.state.RecommendationUserState
import com.example.myplayer.data.recommendation.state.RecommendationUserStateDetector
import com.example.myplayer.data.repository.DownloadRepository
import com.example.myplayer.data.repository.MusicRepository
import com.example.myplayer.data.repository.RecentHistoryRepository
import com.example.myplayer.playback.MusicController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    val musicController: MusicController,
    private val settingsDataStore: SettingsDataStore,
    private val downloadRepository: DownloadRepository,
    private val recommendationCoordinator: RecommendationCoordinator,
    private val userStateDetector: RecommendationUserStateDetector,
    private val recentHistoryRepository: RecentHistoryRepository,
    private val playedSongHistoryDao: PlayedSongHistoryDao,
    private val innertubeApi: InnertubeApi,
    private val recommendationCache: RecommendationCache,
    private val lastFmRecommendationSource: LastFmRecommendationSource,
    private val aiModel: OnDeviceMusicAiModel,
    val spotifyManager: com.example.myplayer.data.spotify.SpotifyIntegrationManager,
    val youtubeManager: com.example.myplayer.data.youtube.YouTubeIntegrationManager
) : ViewModel() {

    private val _userState = MutableStateFlow<RecommendationUserState?>(null)
    val userState: StateFlow<RecommendationUserState?> = _userState.asStateFlow()

    val selectedCategory = MutableStateFlow("All")

    val isSpotifyLoggedIn = spotifyManager.isLoggedIn
    val spotifyUsername = spotifyManager.username
    val isSpotifySyncing = spotifyManager.isSyncing
    val isYouTubeLoggedIn = youtubeManager.isLoggedIn
    val youtubeUsername = youtubeManager.username
    val isYouTubeSyncing = youtubeManager.isSyncing
    private var aiTrainingJob: Job? = null

    private val _showTasteOnboarding = MutableStateFlow(!aiModel.hasCompletedTasteOnboarding())
    val showTasteOnboarding: StateFlow<Boolean> = _showTasteOnboarding.asStateFlow()

    private val _selectedLanguages = MutableStateFlow(aiModel.getSelectedLanguages())
    val selectedLanguages: StateFlow<Set<String>> = _selectedLanguages.asStateFlow()

    private val _selectedGenres = MutableStateFlow(aiModel.getSelectedGenres())
    val selectedGenres: StateFlow<Set<String>> = _selectedGenres.asStateFlow()

    private val _selectedArtists = MutableStateFlow(aiModel.getSelectedArtists())
    val selectedArtists: StateFlow<Set<String>> = _selectedArtists.asStateFlow()

    fun openTasteSelector() {
        _selectedLanguages.value = aiModel.getSelectedLanguages()
        _selectedGenres.value = aiModel.getSelectedGenres()
        _selectedArtists.value = aiModel.getSelectedArtists()
        _showTasteOnboarding.value = true
    }

    fun dismissTasteSelector() {
        _showTasteOnboarding.value = false
    }

    fun saveUserTastePreferences(
        languages: Set<String>,
        genres: Set<String>,
        artists: Set<String>
    ) {
        _selectedLanguages.value = languages
        _selectedGenres.value = genres
        _selectedArtists.value = artists
        _showTasteOnboarding.value = false
        viewModelScope.launch(Dispatchers.IO) {
            aiModel.saveUserTasteProfile(languages, genres, artists)
            _categoryRecommendations.value = emptyMap()
            // Refresh BOTH Home feed and World Trending Hotlist according to new taste!
            launch { refreshAiRecommendations() }
            launch { refreshTrendingMusic() }
        }
    }

    private val _categoryRecommendations = MutableStateFlow<Map<String, List<RecommendationSong>>>(emptyMap())

    private val _aiRecommendations = MutableStateFlow<List<RecommendationSong>>(emptyList())

    val recommendations: StateFlow<List<RecommendationSong>> = combine(
        selectedCategory,
        _aiRecommendations,
        _categoryRecommendations,
        recommendationCoordinator.queueState
    ) { category, aiList, catMap, liveQueue ->
        if (category == "All") {
            aiList.ifEmpty { liveQueue }
        } else {
            catMap[category] ?: emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoadingRecommendations = MutableStateFlow(true)
    val isLoadingRecommendations: StateFlow<Boolean> = _isLoadingRecommendations.asStateFlow()

    private val _hotlistSongs = MutableStateFlow<List<RecommendationSong>>(emptyList())
    val hotlistSongs: StateFlow<List<RecommendationSong>> = _hotlistSongs.asStateFlow()

    private val _isLoadingHotlist = MutableStateFlow(true)
    val isLoadingHotlist: StateFlow<Boolean> = _isLoadingHotlist.asStateFlow()

    val userName: StateFlow<String> = settingsDataStore.userName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val isDarkMode: StateFlow<Boolean> = settingsDataStore.isDarkMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleDarkMode() {
        viewModelScope.launch {
            settingsDataStore.setDarkMode(!isDarkMode.value)
        }
    }

    private val downloadedEntities: Flow<List<SongEntity>> = downloadRepository.getAllDownloads()
        .map { list ->
            list.map { d ->
                SongEntity(
                    id = d.id,
                    title = d.title,
                    artist = d.artist,
                    album = d.album.ifBlank { "Downloaded" },
                    duration = d.durationMs,
                    path = d.localPath,
                    albumArt = d.thumbnailUrl,
                    dateAdded = d.downloadedAt,
                    videoId = d.id
                )
            }
        }

    val recentSongs: StateFlow<List<SongEntity>> = downloadedEntities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostPlayed: StateFlow<List<SongEntity>> = repository.getMostPlayedSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteSongs: StateFlow<List<SongEntity>> = repository.getFavoriteSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSongs: StateFlow<List<SongEntity>> = downloadedEntities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val displayedDownloadedSongs: StateFlow<List<SongEntity>> = combine(
        selectedCategory,
        downloadedEntities
    ) { category, songs ->
        if (category == "All") {
            songs
        } else {
            val keywords = getGenreKeywords(category)
            songs.filter { song ->
                val text = "${song.title} ${song.artist} ${song.album}".lowercase()
                keywords.any { kw -> text.contains(kw) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentSong = musicController.currentSong
    val isPlaying = musicController.isPlaying

    init {
        // Auto-sync username from legacy prefs or myplayer_ui_prefs if not yet in dataStore
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsDataStore.userName.first()
            if (current.isBlank()) {
                val savedName = context.getSharedPreferences("myplayer_ui_prefs", Context.MODE_PRIVATE)
                    .getString("username", "") ?: ""
                if (savedName.isNotBlank()) {
                    settingsDataStore.setUserName(savedName)
                }
            }
        }

        // 1. Load initial Home recommendations & World Trending Hotlist in parallel
        viewModelScope.launch(Dispatchers.IO) {
            launch {
                try {
                    refreshAiRecommendations()
                    val topIds = _aiRecommendations.value.take(2).map { it.videoId }
                    if (topIds.isNotEmpty()) {
                        innertubeApi.prefetchVisibleStreams(topIds)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("HomeViewModel", "Init AI recommendations failed", e)
                    _isLoadingRecommendations.value = false
                }
            }
            launch {
                try {
                    refreshTrendingMusic()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("HomeViewModel", "Init trending music failed", e)
                    _isLoadingHotlist.value = false
                }
            }
        }

        // 2. Sync local library signals on IO thread after 2 seconds
        viewModelScope.launch(Dispatchers.IO) {
            delay(2_000L)
            combine(mostPlayed, favoriteSongs, downloadedEntities) { most, fav, down ->
                val favIds = fav.map { it.id }.toSet()
                val allSongsList = (fav + most + down).distinctBy { it.id }
                allSongsList to favIds
            }.collect { (localSignals, favIds) ->
                if (localSignals.isNotEmpty()) {
                    aiModel.syncLibrarySignals(localSignals, favIds)
                }
            }
        }

        // 3. Background Spotify & YouTube Music sync (delayed 4s so it NEVER slows down app startup)
        viewModelScope.launch(Dispatchers.IO) {
            delay(4_000L)
            var shouldRefresh = false
            if (spotifyManager.isLoggedIn.value) {
                runCatching { spotifyManager.syncSpotifyTasteIntoAi() }
                shouldRefresh = true
            }
            if (youtubeManager.isLoggedIn.value) {
                runCatching { youtubeManager.syncYouTubeTasteIntoAi() }
                shouldRefresh = true
            }
            if (shouldRefresh) {
                _categoryRecommendations.value = emptyMap()
                refreshAiRecommendations()
            }
        }

        // 4. Observe played song — WAIT 4 SECONDS after click before doing ANY AI or Spotify work!
        viewModelScope.launch {
            combine(
                musicController.currentSong,
                musicController.currentOnlineSong
            ) { local, online ->
                when {
                    online != null -> arrayOf(online.videoId, online.title, online.artist, online.durationMs.toString())
                    local != null -> arrayOf(local.videoId ?: local.id, local.title, local.artist, local.duration.toString())
                    else -> null
                }
            }.collect { played ->
                if (played != null && played[0].isNotBlank()) {
                    val playedId = played[0]
                    val playedTitle = played[1]
                    val playedArtist = played[2]
                    val playedDur = played[3].toLongOrNull() ?: 0L

                    // Cancel any previous background AI/Spotify job so 100% bandwidth goes to the clicked song!
                    aiTrainingJob?.cancel()
                    aiTrainingJob = viewModelScope.launch(Dispatchers.IO) {
                        // Let the clicked song resolve and start playing first with ZERO competition!
                        delay(4_000L)

                        aiModel.onSongTransition(
                            videoId = playedId,
                            title = playedTitle,
                            artist = playedArtist,
                            durationMs = playedDur
                        )

                        // Wait another 8s (total 12s of listening) before syncing with Spotify / YouTube Music
                        if (spotifyManager.isLoggedIn.value || youtubeManager.isLoggedIn.value) {
                            delay(8_000L)
                            val stillPlayingSame =
                                (musicController.currentOnlineSong.value?.title == playedTitle) ||
                                (musicController.currentSong.value?.title == playedTitle)

                            if (stillPlayingSame) {
                                var updatedRecos = false
                                if (spotifyManager.isLoggedIn.value) {
                                    val newSpotifyRecos = spotifyManager.reportPlayedSongAndFetchSpotifyRecommendations(
                                        title = playedTitle,
                                        artist = playedArtist,
                                        syncToSpotifyLibrary = true
                                    )
                                    if (newSpotifyRecos.isNotEmpty()) updatedRecos = true
                                }
                                if (youtubeManager.isLoggedIn.value) {
                                    val newYtRecos = youtubeManager.reportPlayedSongAndFetchYouTubeRecommendations(
                                        videoId = playedId,
                                        title = playedTitle,
                                        artist = playedArtist
                                    )
                                    if (newYtRecos.isNotEmpty()) updatedRecos = true
                                }
                                if (updatedRecos) {
                                    _categoryRecommendations.value = emptyMap()
                                    refreshAiRecommendations()
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Periodic auto-refresh every 15 minutes (not constantly)
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(15 * 60 * 1000L)
                    refreshAiRecommendations()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("HomeViewModel", "Periodic refresh failed", e)
                }
            }
        }
    }

    fun syncSpotifyTasteAndRefresh() {
        viewModelScope.launch {
            try {
                spotifyManager.syncSpotifyTasteIntoAi()
                refreshAiRecommendations()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("HomeViewModel", "syncSpotifyTasteAndRefresh failed", e)
            }
        }
    }

    fun syncYouTubeTasteAndRefresh() {
        viewModelScope.launch {
            try {
                youtubeManager.syncYouTubeTasteIntoAi()
                refreshAiRecommendations()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("HomeViewModel", "syncYouTubeTasteAndRefresh failed", e)
            }
        }
    }

    fun refreshTrendingMusic() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingHotlist.value = true
            try {
                val worldTrending = aiModel.generateWorldTrendingHotlist(limit = 50)
                if (worldTrending.isNotEmpty()) {
                    _hotlistSongs.value = worldTrending
                    // Prefetch top 2 Hotlist songs so tapping Play on Hotlist is 0ms instant!
                    innertubeApi.prefetchVisibleStreams(worldTrending.take(2).map { it.videoId })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("HomeViewModel", "Failed to refresh trending music", e)
            } finally {
                _isLoadingHotlist.value = false
            }
        }
    }

    private suspend fun refreshAiRecommendations() {
        _isLoadingRecommendations.value = true
        try {
            val list = aiModel.generateRecommendations(limit = 45)
            if (list.isNotEmpty()) {
                _aiRecommendations.value = list
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("HomeViewModel", "Failed to refresh AI recommendations", e)
        } finally {
            _isLoadingRecommendations.value = false
        }
    }

    fun selectCategory(category: String) {
        selectedCategory.value = category
        if (category == "All") return
        if (_categoryRecommendations.value.containsKey(category)) return

        viewModelScope.launch {
            _isLoadingRecommendations.value = true
            try {
                val results = aiModel.generateCategoryRecommendations(category, limit = 35)
                _categoryRecommendations.value = _categoryRecommendations.value + (category to results)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("HomeViewModel", "Failed to select category $category", e)
            } finally {
                _isLoadingRecommendations.value = false
            }
        }
    }

    private fun getGenreKeywords(category: String): List<String> = when (category.lowercase()) {
        "deep focus" -> listOf("focus", "ambient", "drone", "study", "lofi", "lo-fi", "instrumental", "calm", "binaural", "relax")
        "acoustic chill" -> listOf("acoustic", "guitar", "unplugged", "folk", "indie", "warm", "strings", "chill", "vocal")
        "late night" -> listOf("night", "synthwave", "electro", "retro", "midnight", "drive", "dark", "neon", "after hours")
        "discoveries" -> listOf("discover", "indie", "new", "fresh", "rising", "alt", "underground", "gems")
        "party" -> listOf("party", "dance", "club", "dj", "remix", "edm", "disco", "celebration", "fest", "bass", "electro", "house", "groove", "rave")
        "blues" -> listOf("blues", "soul", "jazz", "r&b", "rhythm", "acoustic", "guitar", "swing")
        "sad" -> listOf("sad", "slow", "cry", "broken", "heartbreak", "tear", "alone", "lonely", "dark", "pain", "melancholy", "grief")
        "hip hop" -> listOf("hip hop", "hiphop", "rap", "trap", "flow", "rhyme", "drank", "drill", "freestyle", "cypher", "mc")
        "chill" -> listOf("chill", "lofi", "lo-fi", "relax", "calm", "peace", "ambient", "meditation", "sleep", "soft", "breeze", "easy")
        "workout" -> listOf("workout", "gym", "fitness", "run", "running", "energy", "power", "motivation", "hard", "pump", "training", "beast")
        "pop" -> listOf("pop", "hit", "summer", "dance", "upbeat", "mainstream", "billboard", "chart", "star")
        else -> listOf(category.lowercase())
    }

    fun playCategory(category: String, onStartPlaying: () -> Unit = {}) {
        selectCategory(category)
        viewModelScope.launch {
            val cached = _categoryRecommendations.value[category]
            val songsToPlay = if (!cached.isNullOrEmpty()) {
                cached
            } else {
                try {
                    aiModel.generateCategoryRecommendations(category, limit = 35).also { fetched ->
                        if (fetched.isNotEmpty()) {
                            _categoryRecommendations.update { it + (category to fetched) }
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (songsToPlay.isNotEmpty()) {
                playRecommendedSongs(songsToPlay, 0)
                withContext(Dispatchers.Main) {
                    onStartPlaying()
                }
            }
        }
    }

    fun setUserName(name: String) {
        viewModelScope.launch {
            settingsDataStore.setUserName(name)
        }
    }

    fun playSong(songs: List<SongEntity>, startIndex: Int) {
        musicController.playSongs(songs, startIndex)
        val song = songs.getOrNull(startIndex) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.incrementPlayCount(song.id)
            repository.addRecentHistory(song.id)
        }
    }

    fun toggleFavorite(song: SongEntity, isCurrentlyFavorite: Boolean) {
        if (!isCurrentlyFavorite) {
            aiModel.onPositiveFeedback(song.videoId ?: song.id, song.title, song.artist, song.duration)
        }
        viewModelScope.launch { repository.toggleFavorite(com.example.myplayer.data.repository.PlayableSong.Local(song), isCurrentlyFavorite) }
    }

    fun playRecommendedSongs(songs: List<RecommendationSong>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val clampedIndex = startIndex.coerceIn(0, songs.size - 1)
        val onlineSongs = songs.map { song ->
            OnlineSong(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationMs = song.durationMs,
                durationText = "${song.durationMs / 60000}:${"%02d".format((song.durationMs % 60000) / 1000)}",
                streamUrl = "online://${song.videoId}"
            )
        }
        musicController.playOnlineSongs(onlineSongs, clampedIndex)
        val selected = songs.getOrNull(clampedIndex)
        if (selected != null) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.addRecentHistory(selected.videoId)
            }
        }
    }

    fun playRecommendedSong(song: RecommendationSong) {
        playRecommendedSongs(listOf(song), 0)
    }
}
