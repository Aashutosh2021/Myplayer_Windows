package com.example.myplayer.data.local.dao

import androidx.room.*
import com.example.myplayer.data.local.entity.PlaylistEntity
import com.example.myplayer.data.local.entity.PlaylistSongCrossReference
import com.example.myplayer.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getPlaylistByName(name: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylist(playlist: PlaylistEntity): Long

    @Delete
    fun deletePlaylist(playlist: PlaylistEntity): Unit

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteAllSongsFromPlaylist(playlistId: Long)

    @Query("UPDATE playlists SET name = :newName WHERE id = :playlistId")
    fun renamePlaylist(playlistId: Long, newName: String): Unit

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSongToPlaylist(crossRef: PlaylistSongCrossReference): Unit

    @Query("""
        DELETE FROM playlist_songs 
        WHERE playlistId = :playlistId 
          AND (
              songId = :songId 
              OR songId = 'spotify:' || :songId 
              OR songId = 'online://' || :songId
              OR REPLACE(REPLACE(songId, 'online://', ''), 'spotify:', '') = REPLACE(REPLACE(:songId, 'online://', ''), 'spotify:', '')
              OR songId IN (SELECT id FROM songs WHERE videoId = :songId OR path = 'online://' || :songId)
          )
    """)
    fun removeSongFromPlaylist(playlistId: Long, songId: String): Unit

    @Query("""
        SELECT 
            COALESCE(s.id, d.id, ps.songId) AS id,
            COALESCE(s.title, d.title, 'Unknown Title') AS title,
            COALESCE(s.artist, d.artist, 'Unknown Artist') AS artist,
            COALESCE(s.album, NULLIF(d.album, ''), 'Downloads') AS album,
            COALESCE(s.duration, d.durationMs, 0) AS duration,
            COALESCE(d.localPath, s.path, 'online://' || REPLACE(REPLACE(ps.songId, 'online://', ''), 'spotify:', '')) AS path,
            COALESCE(d.thumbnailUrl, s.albumArt) AS albumArt,
            COALESCE(s.dateAdded, d.downloadedAt, 0) AS dateAdded,
            COALESCE(s.playCount, 0) AS playCount,
            COALESCE(s.videoId, d.id, CASE WHEN ps.songId NOT LIKE 'local_%' AND ps.songId NOT LIKE 'spotify:%' THEN REPLACE(ps.songId, 'online://', '') ELSE NULL END) AS videoId
        FROM playlist_songs ps
        LEFT JOIN songs s ON s.id = ps.songId 
            OR s.videoId = ps.songId 
            OR s.id = 'spotify:' || ps.songId 
            OR s.path = 'online://' || ps.songId
        LEFT JOIN downloaded_songs d ON d.id = ps.songId 
            OR d.id = REPLACE(REPLACE(ps.songId, 'online://', ''), 'spotify:', '')
            OR (s.videoId IS NOT NULL AND d.id = s.videoId)
        WHERE ps.playlistId = :playlistId 
          AND (s.id IS NOT NULL OR d.id IS NOT NULL)
        GROUP BY ps.songId
        ORDER BY ps.position ASC
    """)
    fun getSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("""
        UPDATE playlist_songs 
        SET position = :position 
        WHERE playlistId = :playlistId 
          AND (
              songId = :songId 
              OR songId = 'spotify:' || :songId 
              OR songId = 'online://' || :songId
              OR REPLACE(REPLACE(songId, 'online://', ''), 'spotify:', '') = REPLACE(REPLACE(:songId, 'online://', ''), 'spotify:', '')
              OR songId IN (SELECT id FROM songs WHERE videoId = :songId OR path = 'online://' || :songId)
          )
    """)
    suspend fun updateSongPosition(playlistId: Long, songId: String, position: Int)

    @Transaction
    suspend fun reorderPlaylistSongs(playlistId: Long, orderedSongIds: List<String>) {
        orderedSongIds.forEachIndexed { index, songId ->
            updateSongPosition(playlistId, songId, index)
        }
    }

    @Query("SELECT * FROM playlist_songs ORDER BY position ASC")
    fun getAllPlaylistSongs(): Flow<List<PlaylistSongCrossReference>>

    @Query("SELECT * FROM playlist_songs ORDER BY position ASC")
    fun getAllPlaylistSongsSync(): List<PlaylistSongCrossReference>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylistSongs(songs: List<PlaylistSongCrossReference>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylists(playlists: List<PlaylistEntity>)
}
