package com.example.myplayer.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplayer.data.local.entity.FolderEntity
import com.example.myplayer.data.local.entity.PlaylistEntity
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.online.model.OnlineSong
import com.example.myplayer.data.repository.DownloadRepository
import com.example.myplayer.data.repository.HybridLibraryRepository
import com.example.myplayer.data.repository.MusicRepository
import com.example.myplayer.data.repository.PlayableSong
import com.example.myplayer.data.repository.PlaylistRepository
import com.example.myplayer.data.spotify.SpotifyIntegrationManager
import com.example.myplayer.data.youtube.YouTubeIntegrationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistOfflineInfo(
    val totalCount: Int = 0,
    val downloadedCount: Int = 0,
    val remainingCount: Int = 0
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val hybridLibraryRepository: HybridLibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val innertubeApi: InnertubeApi,
    val spotifyManager: SpotifyIntegrationManager,
    val youtubeManager: YouTubeIntegrationManager
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlistStats: StateFlow<Map<Long, PlaylistOfflineInfo>> = combine(
        repository.getAllPlaylistSongs(),
        downloadRepository.getAllDownloads(),
        repository.getAllSongs()
    ) { crossRefs, downloads, allSongs ->
        crossRefs.groupBy { it.playlistId }.mapValues { (_, refs) ->
            val resolved = PlaylistRepository.resolvePlaylistSongsForRefs(
                refs = refs.sortedBy { it.position },
                joinedSongs = emptyList(),
                allSongs = allSongs,
                downloadedSongs = downloads
            )
            val downloaded = resolved.count { it is PlayableSong.Downloaded }
            val remaining = resolved.count { it is PlayableSong.Online }
            PlaylistOfflineInfo(
                totalCount = resolved.size,
                downloadedCount = downloaded,
                remainingCount = remaining
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val playlistBatchStatus: StateFlow<Map<Long, Pair<Int, Int>>> = downloadRepository.playlistBatchStatus

    fun togglePlaylistDownload(playlistId: Long) {
        if (downloadRepository.playlistBatchStatus.value.containsKey(playlistId)) {
            downloadRepository.cancelPlaylistBatchDownload(playlistId)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val remaining = repository.getSongsInPlaylistAsPlayable(playlistId)
                .first()
                .filterIsInstance<PlayableSong.Online>()
            if (remaining.isEmpty()) {
                _importStatusMessage.value = "All songs in this playlist are already downloaded!"
                return@launch
            }
            downloadRepository.startSequentialDownloads(playlistId, remaining) { song ->
                resolveToOnlineSong(song, innertubeApi, repository)
            }
        }
    }

    // Downloaded songs strictly sorted in alphabetical order (case-insensitive)
    val downloadedSongs: StateFlow<List<PlayableSong>> = downloadRepository.getAllDownloads()
        .map { list ->
            list.sortedBy { it.title.lowercase() }.map { PlayableSong.Downloaded(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pure local songs scanned from user's device / configured local folders
    val localSongs: StateFlow<List<PlayableSong>> = repository.getAllSongs()
        .map { list ->
            list.filter { song ->
                !song.path.startsWith("online://") && !song.id.startsWith("spotify:") && (song.id.startsWith("local_") || song.videoId.isNullOrBlank())
            }
            .sortedBy { it.title.lowercase() }
            .map { PlayableSong.Local(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localFolders: StateFlow<List<FolderEntity>> = repository.getAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hybridLibrary: StateFlow<List<PlayableSong>> = hybridLibraryRepository.getHybridLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteSongs: StateFlow<List<PlayableSong>> = repository.getFavoriteSongs()
        .map { list -> list.map { PlayableSong.Local(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isImportingSpotify = MutableStateFlow(false)
    val isImportingSpotify: StateFlow<Boolean> = _isImportingSpotify.asStateFlow()

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    private val _importStatusMessage = MutableStateFlow<String?>(null)
    val importStatusMessage: StateFlow<String?> = _importStatusMessage.asStateFlow()

    fun clearImportStatusMessage() {
        _importStatusMessage.value = null
    }

    fun addLocalFolder(uri: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanningLocal.value = true
            try {
                repository.addFolder(uri, name)
                _importStatusMessage.value = "Added local folder '$name' & scanned audio files!"
            } catch (e: Exception) {
                _importStatusMessage.value = "Failed to scan folder: ${e.message}"
            } finally {
                _isScanningLocal.value = false
            }
        }
    }

    fun rescanLocalSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanningLocal.value = true
            try {
                repository.rescanAllFolders()
                _importStatusMessage.value = "Scanned local storage for audio files!"
            } catch (e: Exception) {
                _importStatusMessage.value = "Local scan error: ${e.message}"
            } finally {
                _isScanningLocal.value = false
            }
        }
    }

    fun exportSongAsFile(song: PlayableSong, formatExt: String = "mp3") {
        viewModelScope.launch(Dispatchers.IO) {
            val extLabel = formatExt.uppercase()
            try {
                val rawId = song.id.removePrefix("online://").removePrefix("spotify:").trim()
                val localOrStreamUrl = when (song) {
                    is PlayableSong.Downloaded -> song.entity.localPath
                    is PlayableSong.Local -> song.entity.path.takeIf { !it.startsWith("online://") }
                    else -> null
                } ?: innertubeApi.getCachedStreamUrl(rawId) ?: innertubeApi.getStreamUrl(rawId)

                val res = downloadRepository.exportSongToDeviceStorage(
                    OnlineSong(
                        videoId = rawId,
                        title = song.title,
                        artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl ?: "",
                        durationMs = song.durationMs,
                        streamUrl = localOrStreamUrl
                    ),
                    formatExt = formatExt
                )
                res.onSuccess { savedLoc ->
                    _importStatusMessage.value = "Saved $extLabel file to: $savedLoc"
                }.onFailure { err ->
                    _importStatusMessage.value = "Failed to save $extLabel file: ${err.message}"
                }
            } catch (e: Exception) {
                _importStatusMessage.value = "Failed to save $extLabel file: ${e.message}"
            }
        }
    }

    fun importSpotifyPlaylist(urlOrId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isImportingSpotify.value = true
            val res = spotifyManager.importPlaylistByUrl(urlOrId)
            _isImportingSpotify.value = false
            res.onSuccess { (title, count) ->
                val msg = "Imported '$title' ($count songs) from Spotify!"
                _importStatusMessage.value = msg
                onResult(true, msg)
            }.onFailure { err ->
                val msg = "Failed to import Spotify playlist: ${err.message ?: "Could not load playlist"}"
                _importStatusMessage.value = msg
                onResult(false, msg)
            }
        }
    }

    fun importYouTubePlaylist(urlOrId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isImportingSpotify.value = true
            val res = youtubeManager.importPlaylistByUrl(urlOrId)
            _isImportingSpotify.value = false
            res.onSuccess { (title, count) ->
                val msg = "Imported '$title' ($count songs) from YouTube!"
                _importStatusMessage.value = msg
                onResult(true, msg)
            }.onFailure { err ->
                val msg = "Failed to import YouTube playlist: ${err.message ?: "Could not load playlist"}"
                _importStatusMessage.value = msg
                onResult(false, msg)
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch { repository.deletePlaylist(playlist) }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch { repository.renamePlaylist(playlistId, newName) }
    }

    fun addSongToPlaylist(playlistId: Long, song: PlayableSong) {
        viewModelScope.launch { 
            repository.addSongToPlaylist(playlistId, song, 0)
        }
    }

    fun deleteDownload(videoId: String) {
        viewModelScope.launch {
            downloadRepository.deleteDownloadById(videoId)
        }
    }
}
