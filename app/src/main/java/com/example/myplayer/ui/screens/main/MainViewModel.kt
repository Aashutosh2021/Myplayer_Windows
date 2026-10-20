package com.example.myplayer.ui.screens.main

import androidx.lifecycle.ViewModel
import com.example.myplayer.playback.MusicController
import com.example.myplayer.playback.PlaybackSourceContext
import com.example.myplayer.ui.screens.nowplaying.SideTrackItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

import androidx.lifecycle.viewModelScope
import android.util.Log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.myplayer.data.local.entity.PlaylistEntity
import com.example.myplayer.data.local.entity.SongEntity
import com.example.myplayer.data.local.entity.DownloadedSongEntity
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.online.model.OnlineSong
import com.example.myplayer.data.repository.DownloadRepository
import com.example.myplayer.data.repository.MusicRepository
import com.example.myplayer.data.repository.PlayableSong
import com.example.myplayer.data.repository.PlaylistRepository

@HiltViewModel
class MainViewModel @Inject constructor(
    val musicController: MusicController,
    private val musicRepository: MusicRepository,
    private val downloadRepository: DownloadRepository,
    private val playlistRepository: PlaylistRepository,
    private val innertubeApi: InnertubeApi
) : ViewModel() {

    private val _recommendedTracks = MutableStateFlow<List<OnlineSong>>(emptyList())
    private var lastRecommendedSeedId: String? = null

    init {
        viewModelScope.launch {
            try {
                downloadRepository.performIntegrityCheck()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Integrity check failed on startup", e)
            }
        }

        // Automatically fetch up-next recommendations when a single track plays in RECOMMENDED context
        viewModelScope.launch {
            combine(
                musicController.currentSong,
                musicController.currentOnlineSong,
                musicController.playbackSourceContext
            ) { local, online, context ->
                Triple(local, online, context)
            }.collect { (local, online, context) ->
                if (context == PlaybackSourceContext.RECOMMENDED) {
                    val currentId = online?.videoId
                        ?: local?.videoId?.takeIf { it.isNotBlank() }
                        ?: local?.path?.takeIf { it.startsWith("online://") }?.removePrefix("online://")
                        ?: local?.id?.takeIf { it.matches(Regex("^[a-zA-Z0-9_-]{11}$")) }

                    if (!currentId.isNullOrBlank() && currentId != lastRecommendedSeedId) {
                        lastRecommendedSeedId = currentId
                        fetchRecommendationsFor(currentId)
                    }
                }
            }
        }
    }

    private fun fetchRecommendationsFor(videoId: String) {
        viewModelScope.launch {
            try {
                val cleanId = videoId.removePrefix("online://").trim()
                if (cleanId.isNotBlank()) {
                    val recs = innertubeApi.getUpNext(cleanId)
                    if (recs.isNotEmpty()) {
                        _recommendedTracks.value = recs
                    }
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "Failed to fetch recommendations for $videoId", e)
            }
        }
    }

    val currentSong = musicController.currentSong
    val currentOnlineSong = musicController.currentOnlineSong
    val isPlaying = musicController.isPlaying
    val currentPosition = musicController.currentPosition
    val currentDuration = musicController.currentDuration
    val sleepTimerRemainingSeconds = musicController.sleepTimerRemainingSeconds
    val isShuffleOn = musicController.isShuffleOn
    val repeatMode = musicController.repeatMode
    val currentAudioQuality = musicController.currentAudioQuality
    val playbackQueue = musicController.playbackQueue
    val playbackError = musicController.playbackError
    fun clearPlaybackError() = musicController.clearPlaybackError()

    fun playSong(song: com.example.myplayer.data.repository.PlayableSong) = musicController.playSong(song)
    fun playPlaylist(songs: List<com.example.myplayer.data.repository.PlayableSong>, startIndex: Int) = musicController.playPlaylist(songs, startIndex)
    fun playQueueIndex(index: Int) = musicController.playQueueIndex(index)
    fun playPause() = musicController.playPause()
    fun skipToNext() = musicController.skipToNext()
    fun skipToPrevious() = musicController.skipToPrevious()
    fun seekTo(positionMs: Long) = musicController.seekTo(positionMs)
    fun startSleepTimer(minutes: Int) = musicController.startSleepTimer(minutes)
    fun cancelSleepTimer() = musicController.cancelSleepTimer()
    fun toggleShuffle() = musicController.setShuffleEnabled(!musicController.isShuffleOn.value)
    fun cycleRepeatMode() = musicController.cycleRepeatMode()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isFavorite: StateFlow<Boolean> = combine(currentSong, currentOnlineSong) { local, online ->
        local?.id ?: online?.videoId
    }.flatMapLatest { songId ->
        if (songId != null) {
            musicRepository.isFavorite(songId)
        } else {
            flowOf(false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleFavorite() {
        val local = currentSong.value
        val online = currentOnlineSong.value
        val playable = local?.let { PlayableSong.Local(it) }
            ?: online?.let { PlayableSong.Online(it.videoId, it.title, it.artist, it.durationMs, it.thumbnailUrl) }
            ?: return
            
        val currentlyFavorite = isFavorite.value
        viewModelScope.launch {
            musicRepository.toggleFavorite(playable, currentlyFavorite)
        }
    }

    // ── Downloads ───────────────────────────────────────────────────────────
    val downloadedIds: StateFlow<Set<String>> = downloadRepository.getAllDownloads()
        .map { downloads -> downloads.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // videoId currently being resolved/queued for download
    private val _resolvingDownloadId = MutableStateFlow<String?>(null)

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()
    fun clearDownloadError() { _downloadError.value = null }

    /**
     * Resolves the downloadable metadata for the currently playing track regardless
     * of whether it was initiated as a direct OnlineSong or mapped as a SongEntity.
     */
    fun getDownloadableTrack(
        online: OnlineSong?,
        local: SongEntity?,
        downloadedSet: Set<String>
    ): OnlineSong? {
        if (online != null && online.videoId.isNotBlank()) {
            return online
        }
        if (local != null) {
            val videoId = local.videoId?.takeIf { it.isNotBlank() }
                ?: if (local.path.startsWith("online://")) {
                    local.path.removePrefix("online://").takeIf { it.isNotBlank() }
                } else null
                ?: if (downloadedSet.contains(local.id)) {
                    local.id
                } else null
                ?: if (local.id.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                    local.id
                } else null

            if (videoId != null) {
                val streamUrl = if (local.path.startsWith("http://") || local.path.startsWith("https://")) {
                    local.path
                } else null
                return OnlineSong(
                    videoId = videoId,
                    title = local.title,
                    artist = local.artist,
                    thumbnailUrl = local.albumArt ?: "",
                    durationMs = local.duration,
                    streamUrl = streamUrl
                )
            }
        }
        return null
    }

    /** True if any song is currently loaded (canDownload flag for UI). */
    val isCurrentSongOnline: StateFlow<Boolean> = combine(currentOnlineSong, currentSong, downloadedIds) { online, local, _ ->
        online != null || local != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True if the currently playing online song is already downloaded. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCurrentSongDownloaded: StateFlow<Boolean> =
        combine(currentOnlineSong, currentSong, downloadedIds) { online, local, ids ->
            val track = getDownloadableTrack(online, local, ids)
            track != null && ids.contains(track.videoId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True while the current online song's download is being resolved or is in progress. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCurrentSongDownloading: StateFlow<Boolean> =
        combine(currentOnlineSong, currentSong, downloadRepository.downloadProgress, _resolvingDownloadId) { online, local, progress, resolving ->
            val track = getDownloadableTrack(online, local, downloadedIds.value)
            val id = track?.videoId ?: ""
            id.isNotEmpty() && (resolving == id || progress.containsKey(id))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Resolves the stream URL for the currently playing online song and queues a download.
     */
    fun downloadCurrentSong() {
        val currentLocal = currentSong.value
        val currentOnline = currentOnlineSong.value
        val song = getDownloadableTrack(currentOnline, currentLocal, downloadedIds.value)

        if (song == null) {
            // Track is a pure local file already stored on device storage
            if (currentLocal != null) {
                Log.i("MainViewModel", "[DOWNLOAD] Song '${currentLocal.title}' is pure local storage file")
                _downloadError.value = "This song is already available on your device storage"
            } else {
                Log.w("MainViewModel", "[DOWNLOAD] No downloadable track in Now Playing")
            }
            return
        }

        val videoId = song.videoId
        Log.i("MainViewModel", "[DOWNLOAD] Click received for '${song.title}' ($videoId)")

        // Case 3 — Already downloaded
        if (downloadedIds.value.contains(videoId)) {
            Log.i("MainViewModel", "[DOWNLOAD] Song '$videoId' already downloaded, skipping")
            _downloadError.value = "Already downloaded and available offline"
            return
        }

        // Case 2 — Already downloading (prevent duplicate jobs)
        if (_resolvingDownloadId.value == videoId || downloadRepository.isDownloading(videoId)) {
            Log.i("MainViewModel", "[DOWNLOAD] Song '$videoId' is already downloading, skipping duplicate")
            return
        }

        viewModelScope.launch {
            if (downloadRepository.isDownloaded(videoId)) return@launch

            _resolvingDownloadId.value = videoId
            Log.i("MainViewModel", "[DOWNLOAD] Starting download resolution for: ${song.title} ($videoId)")

            try {
                var streamUrl = song.streamUrl?.takeIf { it.isNotBlank() }
                if (streamUrl == null) {
                    streamUrl = innertubeApi.getCachedStreamUrl(videoId)
                }
                if (streamUrl == null) {
                    Log.i("MainViewModel", "[DOWNLOAD] Resolving stream URL via InnertubeApi for: $videoId")
                    streamUrl = innertubeApi.getStreamUrl(videoId)
                } else {
                    Log.i("MainViewModel", "[DOWNLOAD] Reusing active session stream URL for: $videoId")
                }

                if (streamUrl.isNullOrBlank()) {
                    // Case 4 — Missing URL
                    val msg = "Download failed: no valid media URL"
                    Log.e("MainViewModel", "[DOWNLOAD] $msg for '${song.title}' ($videoId)")
                    _downloadError.value = msg
                    return@launch
                }

                Log.i("MainViewModel", "[DOWNLOAD] Enqueuing download in DownloadRepository for '${song.title}'")
                val enqueued = downloadRepository.startDownload(song.copy(streamUrl = streamUrl))
                if (!enqueued) {
                    val msg = "Download failed: could not schedule download"
                    Log.e("MainViewModel", "[DOWNLOAD] $msg for '${song.title}' ($videoId)")
                    _downloadError.value = msg
                } else {
                    Log.i("MainViewModel", "[DOWNLOAD] Successfully enqueued download for '${song.title}'")
                }
            } catch (e: com.example.myplayer.data.online.YouTubeStreamBlockedException) {
                // Case 5 — Download failure: do not remain stuck in downloading state
                val msg = e.message ?: "Download failed: YouTube stream blocked"
                Log.e("MainViewModel", "[DOWNLOAD] $msg for '${song.title}' ($videoId)", e)
                _downloadError.value = msg
            } catch (e: Exception) {
                val msg = "Download failed: ${e.message ?: "Unknown error"}"
                Log.e("MainViewModel", "[DOWNLOAD] $msg for '${song.title}' ($videoId)", e)
                _downloadError.value = msg
            } finally {
                _resolvingDownloadId.value = null
            }
        }
    }

    fun removeCurrentSongDownload() {
        val song = getDownloadableTrack(currentOnlineSong.value, currentSong.value, downloadedIds.value) ?: return
        Log.i("MainViewModel", "[DOWNLOAD] Removing download for '${song.title}' (${song.videoId})")
        viewModelScope.launch {
            downloadRepository.deleteDownloadById(song.videoId)
        }
    }

    // ── Context-Aware Side Track List (Recommendations / Downloads / Playlists) ──
    val playbackSourceContext: StateFlow<PlaybackSourceContext> = musicController.playbackSourceContext
    val contextTitle: StateFlow<String> = musicController.contextTitle

    fun setSideTrackContext(context: PlaybackSourceContext) {
        musicController.setPlaybackSourceContext(context)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sideTracks: StateFlow<List<SideTrackItem>> = combine(
        musicController.playbackSourceContext,
        _recommendedTracks,
        downloadRepository.getAllDownloads(),
        musicController.playbackQueue,
        musicController.currentOnlineSong
    ) { context, recs, downloads, queue, currentOnline ->
        val currentLocal = musicController.currentSong.value
        val activeId = currentOnline?.videoId
            ?: currentLocal?.videoId?.takeIf { it.isNotBlank() }
            ?: currentLocal?.id
            ?: ""

        when (context) {
            PlaybackSourceContext.DOWNLOADS -> {
                downloads.map { d ->
                    SideTrackItem(
                        id = d.id,
                        title = d.title,
                        artist = d.artist,
                        albumArt = d.thumbnailUrl,
                        durationMs = d.durationMs,
                        isPlaying = (d.id == activeId),
                        originalItem = d
                    )
                }
            }
            PlaybackSourceContext.PLAYLIST, PlaybackSourceContext.QUEUE -> {
                queue.map { s ->
                    val sId = s.videoId?.takeIf { it.isNotBlank() } ?: s.id
                    SideTrackItem(
                        id = sId,
                        title = s.title,
                        artist = s.artist,
                        albumArt = s.albumArt,
                        durationMs = s.duration,
                        isPlaying = (s.id == activeId || s.videoId == activeId),
                        originalItem = s
                    )
                }
            }
            PlaybackSourceContext.RECOMMENDED -> {
                val list = mutableListOf<SideTrackItem>()
                val activeTitle = currentLocal?.title ?: currentOnline?.title
                val activeArtist = currentLocal?.artist ?: currentOnline?.artist
                val activeArt = currentLocal?.albumArt ?: currentOnline?.thumbnailUrl
                val activeDuration = currentLocal?.duration ?: currentOnline?.durationMs ?: 0L

                // Highlight currently playing track at top
                if (!activeTitle.isNullOrBlank() && activeId.isNotBlank()) {
                    list.add(
                        SideTrackItem(
                            id = activeId,
                            title = activeTitle,
                            artist = activeArtist ?: "Unknown",
                            albumArt = activeArt,
                            durationMs = activeDuration,
                            isPlaying = true,
                            originalItem = currentOnline ?: currentLocal
                        )
                    )
                }

                // Append up-next recommendations
                recs.forEach { r ->
                    if (r.videoId != activeId) {
                        list.add(
                            SideTrackItem(
                                id = r.videoId,
                                title = r.title,
                                artist = r.artist,
                                albumArt = r.thumbnailUrl,
                                durationMs = r.durationMs,
                                isPlaying = false,
                                originalItem = r
                            )
                        )
                    }
                }
                list
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playSideTrack(item: SideTrackItem) {
        when (val orig = item.originalItem) {
            is OnlineSong -> {
                musicController.setPlaybackSourceContext(PlaybackSourceContext.RECOMMENDED, "Recommended")
                musicController.playOnlineSong(orig)
            }
            is DownloadedSongEntity -> {
                musicController.setPlaybackSourceContext(PlaybackSourceContext.DOWNLOADS, "Downloaded songs")
                musicController.playDownloadedSong(orig)
            }
            is PlayableSong -> {
                musicController.playSong(orig)
            }
            is SongEntity -> {
                val queue = musicController.playbackQueue.value
                val idx = queue.indexOfFirst { it.id == item.id || it.videoId == item.id }
                if (idx >= 0) {
                    musicController.playQueueIndex(idx)
                } else {
                    musicController.playSongs(listOf(orig), 0)
                }
            }
            else -> {
                val queue = musicController.playbackQueue.value
                val idx = queue.indexOfFirst { it.id == item.id || it.videoId == item.id }
                if (idx >= 0) {
                    musicController.playQueueIndex(idx)
                } else {
                    musicController.playOnlineSong(
                        OnlineSong(
                            videoId = item.id,
                            title = item.title,
                            artist = item.artist,
                            thumbnailUrl = item.albumArt ?: "",
                            durationMs = item.durationMs
                        )
                    )
                }
            }
        }
    }

    // ── Playlist Management ──────────────────────────────────────────────────
    val allPlaylists: StateFlow<List<PlaylistEntity>> = playlistRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCurrentSongToPlaylist(playlistId: Long) {
        val local = currentSong.value
        val online = currentOnlineSong.value
        val playable = local?.let { PlayableSong.Local(it) }
            ?: online?.let { PlayableSong.Online(it.videoId, it.title, it.artist, it.durationMs, it.thumbnailUrl) }
            ?: return
        viewModelScope.launch {
            playlistRepository.addSongToPlaylist(playlistId, playable, 0)
        }
    }

    fun addSongToPlaylist(playlistId: Long, song: SideTrackItem) {
        viewModelScope.launch {
            val playable = when (val orig = song.originalItem) {
                is DownloadedSongEntity -> PlayableSong.Downloaded(orig)
                is OnlineSong -> PlayableSong.Online(orig.videoId, orig.title, orig.artist, orig.durationMs, orig.thumbnailUrl)
                is SongEntity -> PlayableSong.Local(orig)
                is PlayableSong -> orig
                else -> PlayableSong.Online(song.id, song.title, song.artist, song.durationMs, song.albumArt)
            }
            playlistRepository.addSongToPlaylist(playlistId, playable, 0)
        }
    }

    fun createPlaylistAndAdd(name: String) {
        if (name.isBlank()) return
        val local = currentSong.value
        val online = currentOnlineSong.value
        val playable = local?.let { PlayableSong.Local(it) }
            ?: online?.let { PlayableSong.Online(it.videoId, it.title, it.artist, it.durationMs, it.thumbnailUrl) }

        viewModelScope.launch {
            val newId = playlistRepository.createPlaylist(name.trim())
            if (newId > 0 && playable != null) {
                playlistRepository.addSongToPlaylist(newId, playable, 0)
            }
        }
    }

    // ── Volume Control ───────────────────────────────────────────────────────
    private val _playerVolume = MutableStateFlow(1.0f)
    val playerVolume: StateFlow<Float> = _playerVolume.asStateFlow()

    fun setPlayerVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        _playerVolume.value = clamped
        musicController.setPlayerVolume(clamped)
    }
}


