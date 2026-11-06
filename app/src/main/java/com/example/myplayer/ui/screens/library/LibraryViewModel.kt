package com.example.myplayer.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplayer.data.local.entity.PlaylistEntity
import com.example.myplayer.data.repository.DownloadRepository
import com.example.myplayer.data.repository.HybridLibraryRepository
import com.example.myplayer.data.repository.MusicRepository
import com.example.myplayer.data.repository.PlayableSong
import com.example.myplayer.data.spotify.SpotifyIntegrationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val hybridLibraryRepository: HybridLibraryRepository,
    private val downloadRepository: DownloadRepository,
    val spotifyManager: SpotifyIntegrationManager
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Downloaded songs strictly sorted in alphabetical order (case-insensitive)
    val downloadedSongs: StateFlow<List<PlayableSong>> = downloadRepository.getAllDownloads()
        .map { list ->
            list.sortedBy { it.title.lowercase() }.map { PlayableSong.Downloaded(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hybridLibrary: StateFlow<List<PlayableSong>> = hybridLibraryRepository.getHybridLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteSongs: StateFlow<List<PlayableSong>> = repository.getFavoriteSongs()
        .map { list -> list.map { PlayableSong.Local(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isImportingSpotify = MutableStateFlow(false)
    val isImportingSpotify: StateFlow<Boolean> = _isImportingSpotify.asStateFlow()

    private val _importStatusMessage = MutableStateFlow<String?>(null)
    val importStatusMessage: StateFlow<String?> = _importStatusMessage.asStateFlow()

    fun clearImportStatusMessage() {
        _importStatusMessage.value = null
    }

    fun importSpotifyPlaylist(urlOrId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isImportingSpotify.value = true
            val res = spotifyManager.importPlaylistByUrl(urlOrId)
            _isImportingSpotify.value = false
            res.onSuccess { (title, count) ->
                val msg = "Imported '$title' ($count songs) from Spotify!"
                _importStatusMessage.value = msg
                onResult(true, msg)
            }.onFailure { err ->
                val msg = "Failed to import playlist: ${err.message ?: "Could not load playlist"}"
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
