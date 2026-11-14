package com.example.myplayer.data.repository

import com.example.myplayer.data.local.dao.DownloadedSongDao
import com.example.myplayer.data.local.dao.PlaylistDao
import com.example.myplayer.data.local.dao.SongDao
import com.example.myplayer.data.local.entity.DownloadedSongEntity
import com.example.myplayer.data.local.entity.PlaylistEntity
import com.example.myplayer.data.local.entity.PlaylistSongCrossReference
import com.example.myplayer.data.local.entity.SongEntity
import com.example.myplayer.data.model.toSongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository owning Playlist creation, deletions, item additions, reordering, and removals.
 * Includes self-healing resolution across `playlist_songs`, `songs`, and `downloaded_songs`
 * so downloaded or imported playlist tracks never disappear when local folders are rescanned.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val downloadedSongDao: DownloadedSongDao
) {
    private val healScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Self-heal any downloaded songs that were previously cleared from the `songs` table
        healScope.launch {
            runCatching {
                val downloads = downloadedSongDao.getAllDownloadsSync()
                if (downloads.isNotEmpty()) {
                    val backfilled = downloads.map { dl ->
                        SongEntity(
                            id = dl.id,
                            title = dl.title,
                            artist = dl.artist,
                            album = dl.album.ifBlank { "Downloads" },
                            duration = dl.durationMs,
                            path = "online://${dl.id}",
                            albumArt = dl.thumbnailUrl,
                            dateAdded = dl.downloadedAt,
                            playCount = 0,
                            videoId = dl.id
                        )
                    }
                    songDao.insertSongs(backfilled)
                }
            }
        }
    }

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    fun getAllPlaylistSongs(): Flow<List<PlaylistSongCrossReference>> = playlistDao.getAllPlaylistSongs()

    fun getSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>> = playlistDao.getSongsInPlaylist(playlistId)

    fun getSongsInPlaylistAsPlayable(playlistId: Long): Flow<List<PlayableSong>> {
        return combine(
            playlistDao.getAllPlaylistSongs(),
            playlistDao.getSongsInPlaylist(playlistId),
            songDao.getAllSongs(),
            downloadedSongDao.getAllDownloads()
        ) { allRefs, joinedSongs, allSongs, downloadedSongs ->
            val playlistRefs = allRefs
                .filter { it.playlistId == playlistId }
                .sortedBy { it.position }
            resolvePlaylistSongsForRefs(
                refs = playlistRefs,
                joinedSongs = joinedSongs,
                allSongs = allSongs,
                downloadedSongs = downloadedSongs
            )
        }
    }

    companion object {
        private fun normalizeRawId(raw: String): String =
            raw.removePrefix("online://").removePrefix("spotify:").trim()

        private fun normalizeTrackKey(title: String, artist: String): String =
            "${title.trim().lowercase()}__${artist.trim().lowercase()}"

        /**
         * Resolves a playlist's [PlaylistSongCrossReference] entries against both [downloadedSongs]
         * and [allSongs] (plus [joinedSongs] fallback), guaranteeing that:
         * 1. Songs present in `downloaded_songs` ALWAYS appear even if missing from `songs`.
         * 2. Spotify tracks (`spotify:xxx`) and YouTube tracks (`videoId`) resolve seamlessly.
         * 3. Duplicate entries for the same track (e.g. both `spotify:id` and resolved `videoId` in
         *    the same playlist) are deduplicated so Library counts and Detail screens match 100%.
         */
        fun resolvePlaylistSongsForRefs(
            refs: List<PlaylistSongCrossReference>,
            joinedSongs: List<SongEntity> = emptyList(),
            allSongs: List<SongEntity>,
            downloadedSongs: List<DownloadedSongEntity>
        ): List<PlayableSong> {
            val downloadedById = HashMap<String, DownloadedSongEntity>(downloadedSongs.size * 2)
            for (dl in downloadedSongs) {
                downloadedById[dl.id] = dl
                downloadedById[normalizeRawId(dl.id)] = dl
            }

            val songsById = HashMap<String, SongEntity>((allSongs.size + joinedSongs.size) * 3)
            val songsByVideoId = HashMap<String, SongEntity>(allSongs.size + joinedSongs.size)
            for (s in joinedSongs) {
                songsById[s.id] = s
                songsById[normalizeRawId(s.id)] = s
                if (!s.videoId.isNullOrBlank()) {
                    songsByVideoId[s.videoId] = s
                }
            }
            for (s in allSongs) {
                songsById[s.id] = s
                songsById[normalizeRawId(s.id)] = s
                if (!s.videoId.isNullOrBlank()) {
                    songsByVideoId[s.videoId] = s
                }
            }

            val resolvedList = ArrayList<PlayableSong>(refs.size)
            val seenIds = HashSet<String>()
            val seenTitleArtist = HashMap<String, Int>() // trackKey -> index in resolvedList

            for (ref in refs) {
                val rawRefId = ref.songId
                val cleanRefId = normalizeRawId(rawRefId)

                val songEntity = songsById[rawRefId]
                    ?: songsById[cleanRefId]
                    ?: songsById["spotify:$cleanRefId"]
                    ?: songsByVideoId[cleanRefId]

                val dlEntity = downloadedById[rawRefId]
                    ?: downloadedById[cleanRefId]
                    ?: songEntity?.videoId?.let { downloadedById[it] ?: downloadedById[normalizeRawId(it)] }

                val playable: PlayableSong? = when {
                    dlEntity != null -> PlayableSong.Downloaded(dlEntity)
                    songEntity != null -> {
                        if (songEntity.path.startsWith("online://")) {
                            val vid = songEntity.videoId?.takeIf { it.isNotBlank() }
                                ?: songEntity.path.removePrefix("online://").ifBlank { cleanRefId }
                            PlayableSong.Online(
                                id = vid,
                                title = songEntity.title,
                                artist = songEntity.artist,
                                durationMs = songEntity.duration,
                                thumbnailUrl = songEntity.albumArt
                            )
                        } else if (!songEntity.videoId.isNullOrBlank() && !songEntity.id.startsWith("local_")) {
                            PlayableSong.Online(
                                id = songEntity.videoId,
                                title = songEntity.title,
                                artist = songEntity.artist,
                                durationMs = songEntity.duration,
                                thumbnailUrl = songEntity.albumArt
                            )
                        } else {
                            PlayableSong.Local(songEntity)
                        }
                    }
                    else -> {
                        if (cleanRefId.length == 11 && cleanRefId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                            PlayableSong.Online(
                                id = cleanRefId,
                                title = "Track ($cleanRefId)",
                                artist = "YouTube Music",
                                durationMs = 0L,
                                thumbnailUrl = "https://img.youtube.com/vi/$cleanRefId/hqdefault.jpg"
                            )
                        } else null
                    }
                }

                if (playable != null) {
                    val canonicalId = normalizeRawId(playable.id)
                    if (!seenIds.add(canonicalId)) {
                        // Already added this exact ID; if the new one is Downloaded and old was Online, upgrade it
                        val existingIdx = resolvedList.indexOfFirst { normalizeRawId(it.id) == canonicalId }
                        if (existingIdx >= 0 && playable is PlayableSong.Downloaded && resolvedList[existingIdx] !is PlayableSong.Downloaded) {
                            resolvedList[existingIdx] = playable
                        }
                        continue
                    }

                    val tKey = normalizeTrackKey(playable.title, playable.artist)
                    val existingByTrackIdx = seenTitleArtist[tKey]
                    if (existingByTrackIdx != null && playable.title.isNotBlank() && !playable.title.equals("Unknown Title", ignoreCase = true) && !playable.title.startsWith("Track (")) {
                        val existingItem = resolvedList[existingByTrackIdx]
                        // If same playlist has both an unresolved `spotify:...` entry and a `Downloaded` (or resolved YouTube) entry for the exact same song, merge them!
                        if (playable is PlayableSong.Downloaded && existingItem !is PlayableSong.Downloaded) {
                            resolvedList[existingByTrackIdx] = playable
                        }
                        continue
                    }

                    seenTitleArtist[tKey] = resolvedList.size
                    resolvedList.add(playable)
                }
            }

            return resolvedList
        }
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        try {
            playlistDao.insertPlaylist(PlaylistEntity(name = name))
        } catch (e: Exception) {
            android.util.Log.e("PlaylistRepository", "Failed to create playlist $name", e)
            -1L
        }
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(Dispatchers.IO) {
        try {
            playlistDao.renamePlaylist(playlistId, newName)
        } catch (e: Exception) {
            android.util.Log.e("PlaylistRepository", "Failed to rename playlist $playlistId to $newName", e)
        }
    }

    suspend fun deletePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        try {
            playlistDao.deleteAllSongsFromPlaylist(playlist.id)
            playlistDao.deletePlaylist(playlist)
        } catch (e: Exception) {
            android.util.Log.e("PlaylistRepository", "Failed to delete playlist ${playlist.id}", e)
        }
    }

    suspend fun addSongToPlaylist(playlistId: Long, song: PlayableSong, position: Int) = withContext(Dispatchers.IO) {
        try {
            when (song) {
                is PlayableSong.Online -> {
                    val entity = song.toSongEntity()
                    songDao.insertSong(entity)
                }
                is PlayableSong.Downloaded -> {
                    val entity = song.toSongEntity().copy(
                        path = "online://${song.id}",
                        videoId = song.id
                    )
                    songDao.insertSong(entity)
                }
                is PlayableSong.Local -> {
                    songDao.insertSongs(listOf(song.entity))
                }
            }
            playlistDao.insertSongToPlaylist(PlaylistSongCrossReference(playlistId, song.id, position))
        } catch (e: Exception) {
            android.util.Log.e("PlaylistRepository", "Failed to add song ${song.id} to playlist $playlistId", e)
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        try {
            playlistDao.removeSongFromPlaylist(playlistId, songId)
        } catch (e: Exception) {
            android.util.Log.e("PlaylistRepository", "Failed to remove song $songId from playlist $playlistId", e)
        }
    }

    suspend fun reorderSongsInPlaylist(playlistId: Long, orderedSongIds: List<String>) = withContext(Dispatchers.IO) {
        try {
            playlistDao.reorderPlaylistSongs(playlistId, orderedSongIds)
        } catch (e: Exception) {
            android.util.Log.e("PlaylistRepository", "Failed to reorder playlist $playlistId", e)
        }
    }
}
