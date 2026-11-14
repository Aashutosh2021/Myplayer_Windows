package com.example.myplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myplayer.data.local.entity.FavoriteEntity
import com.example.myplayer.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("""
        SELECT 
            COALESCE(s.id, d.id, f.songId) AS id,
            COALESCE(s.title, d.title, 'Unknown Title') AS title,
            COALESCE(s.artist, d.artist, 'Unknown Artist') AS artist,
            COALESCE(s.album, NULLIF(d.album, ''), 'Favorites') AS album,
            COALESCE(s.duration, d.durationMs, 0) AS duration,
            COALESCE(d.localPath, s.path, 'online://' || REPLACE(REPLACE(f.songId, 'online://', ''), 'spotify:', '')) AS path,
            COALESCE(d.thumbnailUrl, s.albumArt) AS albumArt,
            COALESCE(s.dateAdded, d.downloadedAt, f.addedAt) AS dateAdded,
            COALESCE(s.playCount, 0) AS playCount,
            COALESCE(s.videoId, d.id) AS videoId
        FROM favorites f
        LEFT JOIN songs s ON s.id = f.songId OR s.videoId = f.songId
        LEFT JOIN downloaded_songs d ON d.id = f.songId OR (s.videoId IS NOT NULL AND d.id = s.videoId)
        WHERE s.id IS NOT NULL OR d.id IS NOT NULL
        GROUP BY f.songId
        ORDER BY f.addedAt DESC
    """)
    fun getFavoriteSongs(): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addFavorite(favorite: FavoriteEntity): Unit

    @Query("DELETE FROM favorites WHERE songId = :songId")
    fun removeFavorite(songId: String): Unit

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    fun isFavorite(songId: String): Flow<Boolean>

    @Query("SELECT * FROM favorites")
    fun getAllFavoritesSync(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFavorites(favorites: List<FavoriteEntity>)
}
