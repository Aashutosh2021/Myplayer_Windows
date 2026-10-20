package com.example.myplayer.data.local

import com.example.myplayer.data.local.dao.*
import com.example.myplayer.data.local.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class DesktopAppDatabase : AppDatabase() {

    private val dbFile: File by lazy {
        val appData = System.getenv("APPDATA")
        val baseDir = if (!appData.isNullOrBlank()) {
            File(appData, "MyPlayer")
        } else {
            File(System.getProperty("user.home"), ".myplayer")
        }
        baseDir.mkdirs()
        File(baseDir, "myplayer.db")
    }

    private val connection: Connection by lazy {
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        initTables(conn)
        conn
    }

    private val dbScope = CoroutineScope(Dispatchers.IO)
    private val changeNotifier = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private fun notifyChange(table: String) {
        changeNotifier.tryEmit(table)
    }

    private fun <T> tableFlow(table: String, query: () -> T): Flow<T> {
        return flow {
            emit(query())
            changeNotifier.filter { it == table || it == "*" }.collect {
                emit(query())
            }
        }.flowOn(Dispatchers.IO)
    }

    @Synchronized
    private fun initTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS songs (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    duration INTEGER NOT NULL,
                    path TEXT NOT NULL,
                    albumArt TEXT,
                    dateAdded INTEGER NOT NULL,
                    playCount INTEGER NOT NULL DEFAULT 0,
                    videoId TEXT
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS playlists (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS playlist_songs (
                    playlistId INTEGER NOT NULL,
                    songId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    PRIMARY KEY (playlistId, songId)
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS favorites (
                    songId TEXT PRIMARY KEY,
                    addedAt INTEGER NOT NULL
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS folders (
                    uri TEXT PRIMARY KEY,
                    name TEXT NOT NULL
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS recent_history (
                    songId TEXT PRIMARY KEY,
                    playedAt INTEGER NOT NULL
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS downloaded_songs (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL DEFAULT '',
                    thumbnailUrl TEXT NOT NULL,
                    durationMs INTEGER NOT NULL,
                    localPath TEXT NOT NULL,
                    fileSizeBytes INTEGER NOT NULL DEFAULT 0,
                    downloadedAt INTEGER NOT NULL
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS recent_searches (
                    query TEXT PRIMARY KEY,
                    timestamp INTEGER NOT NULL
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS cached_lyrics (
                    songId TEXT PRIMARY KEY,
                    plainLyrics TEXT,
                    syncedLyrics TEXT,
                    trackName TEXT NOT NULL,
                    artistName TEXT NOT NULL,
                    cachedAt INTEGER NOT NULL
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS cached_recommendations (
                    videoId TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    thumbnailUrl TEXT NOT NULL,
                    durationMs INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    seedId TEXT NOT NULL,
                    cachedAt INTEGER NOT NULL
                );
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS played_songs_history (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    thumbnailUrl TEXT,
                    durationMs INTEGER NOT NULL,
                    isOnline INTEGER NOT NULL,
                    playedAt INTEGER NOT NULL
                );
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS index_played_songs_history_playedAt ON played_songs_history (playedAt);")
        }
    }

    override fun clearAllTables() {
        synchronized(connection) {
            connection.createStatement().use { stmt ->
                stmt.execute("DELETE FROM songs")
                stmt.execute("DELETE FROM playlists")
                stmt.execute("DELETE FROM playlist_songs")
                stmt.execute("DELETE FROM favorites")
                stmt.execute("DELETE FROM folders")
                stmt.execute("DELETE FROM recent_history")
                stmt.execute("DELETE FROM downloaded_songs")
                stmt.execute("DELETE FROM recent_searches")
                stmt.execute("DELETE FROM cached_lyrics")
                stmt.execute("DELETE FROM cached_recommendations")
                stmt.execute("DELETE FROM played_songs_history")
            }
        }
        notifyChange("*")
    }

    override fun songDao(): SongDao = object : SongDao {
        override fun getAllSongs(): Flow<List<SongEntity>> = tableFlow("songs") {
            querySongs("SELECT * FROM songs ORDER BY title ASC")
        }

        override fun getTrendingSongs(): Flow<List<SongEntity>> = tableFlow("songs") {
            querySongs("SELECT * FROM songs ORDER BY id ASC LIMIT 15")
        }

        override fun getSongById(id: String): SongEntity? = synchronized(connection) {
            val ps = connection.prepareStatement("SELECT * FROM songs WHERE id = ?")
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) readSong(rs) else null
        }

        override fun insertSongs(songs: List<SongEntity>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("""
                    INSERT OR IGNORE INTO songs (id, title, artist, album, duration, path, albumArt, dateAdded, playCount, videoId)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent())
                for (s in songs) {
                    ps.setString(1, s.id)
                    ps.setString(2, s.title)
                    ps.setString(3, s.artist)
                    ps.setString(4, s.album)
                    ps.setLong(5, s.duration)
                    ps.setString(6, s.path)
                    ps.setString(7, s.albumArt)
                    ps.setLong(8, s.dateAdded)
                    ps.setInt(9, s.playCount)
                    ps.setString(10, s.videoId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("songs")
        }

        override fun deleteSongsByFolder(folderUri: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM songs WHERE path LIKE ?")
                ps.setString(1, "$folderUri%")
                ps.executeUpdate()
            }
            notifyChange("songs")
        }

        override fun getRecentlyAddedSongs(): Flow<List<SongEntity>> = tableFlow("songs") {
            querySongs("SELECT * FROM songs ORDER BY dateAdded DESC LIMIT 50")
        }

        override fun getMostPlayedSongs(): Flow<List<SongEntity>> = tableFlow("songs") {
            querySongs("SELECT * FROM songs ORDER BY playCount DESC LIMIT 50")
        }

        override fun incrementPlayCount(id: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("UPDATE songs SET playCount = playCount + 1 WHERE id = ?")
                ps.setString(1, id)
                ps.executeUpdate()
            }
            notifyChange("songs")
        }

        override fun updateSongPath(id: String, path: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("UPDATE songs SET path = ? WHERE id = ?")
                ps.setString(1, path)
                ps.setString(2, id)
                ps.executeUpdate()
            }
            notifyChange("songs")
        }

        override fun searchSongs(query: String): Flow<List<SongEntity>> = tableFlow("songs") {
            val q = "%$query%"
            synchronized(connection) {
                val ps = connection.prepareStatement("SELECT * FROM songs WHERE title LIKE ? OR artist LIKE ? OR album LIKE ?")
                ps.setString(1, q)
                ps.setString(2, q)
                ps.setString(3, q)
                val rs = ps.executeQuery()
                val list = mutableListOf<SongEntity>()
                while (rs.next()) list.add(readSong(rs))
                list
            }
        }

        override suspend fun getAllSongsSync(): List<SongEntity> = querySongs("SELECT * FROM songs")

        override suspend fun getSongByVideoId(videoId: String): SongEntity? = synchronized(connection) {
            val ps = connection.prepareStatement("SELECT * FROM songs WHERE videoId = ? LIMIT 1")
            ps.setString(1, videoId)
            val rs = ps.executeQuery()
            if (rs.next()) readSong(rs) else null
        }

        private fun querySongs(sql: String): List<SongEntity> = synchronized(connection) {
            val rs = connection.createStatement().executeQuery(sql)
            val list = mutableListOf<SongEntity>()
            while (rs.next()) list.add(readSong(rs))
            list
        }

        private fun readSong(rs: ResultSet): SongEntity = SongEntity(
            id = rs.getString("id"),
            title = rs.getString("title"),
            artist = rs.getString("artist"),
            album = rs.getString("album"),
            duration = rs.getLong("duration"),
            path = rs.getString("path"),
            albumArt = rs.getString("albumArt"),
            dateAdded = rs.getLong("dateAdded"),
            playCount = rs.getInt("playCount"),
            videoId = rs.getString("videoId")
        )
    }

    override fun playlistDao(): PlaylistDao = object : PlaylistDao {
        override fun getAllPlaylists(): Flow<List<PlaylistEntity>> = tableFlow("playlists") {
            synchronized(connection) {
                val rs = connection.createStatement().executeQuery("SELECT * FROM playlists ORDER BY createdAt DESC")
                val list = mutableListOf<PlaylistEntity>()
                while (rs.next()) {
                    list.add(PlaylistEntity(rs.getLong("id"), rs.getString("name"), rs.getLong("createdAt")))
                }
                list
            }
        }

        override fun insertPlaylist(playlist: PlaylistEntity): Long = synchronized(connection) {
            val ps = connection.prepareStatement(
                "INSERT INTO playlists (name, createdAt) VALUES (?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS
            )
            ps.setString(1, playlist.name)
            ps.setLong(2, playlist.createdAt)
            ps.executeUpdate()
            val keys = ps.generatedKeys
            val id = if (keys.next()) keys.getLong(1) else 0L
            notifyChange("playlists")
            id
        }

        override fun deletePlaylist(playlist: PlaylistEntity) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM playlists WHERE id = ?")
                ps.setLong(1, playlist.id)
                ps.executeUpdate()
                val psSongs = connection.prepareStatement("DELETE FROM playlist_songs WHERE playlistId = ?")
                psSongs.setLong(1, playlist.id)
                psSongs.executeUpdate()
            }
            notifyChange("playlists")
            notifyChange("playlist_songs")
        }

        override fun renamePlaylist(playlistId: Long, newName: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("UPDATE playlists SET name = ? WHERE id = ?")
                ps.setString(1, newName)
                ps.setLong(2, playlistId)
                ps.executeUpdate()
            }
            notifyChange("playlists")
        }

        override fun insertSongToPlaylist(crossRef: PlaylistSongCrossReference) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR IGNORE INTO playlist_songs (playlistId, songId, position) VALUES (?, ?, ?)")
                ps.setLong(1, crossRef.playlistId)
                ps.setString(2, crossRef.songId)
                ps.setInt(3, crossRef.position)
                ps.executeUpdate()
            }
            notifyChange("playlist_songs")
        }

        override fun removeSongFromPlaylist(playlistId: Long, songId: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM playlist_songs WHERE playlistId = ? AND songId = ?")
                ps.setLong(1, playlistId)
                ps.setString(2, songId)
                ps.executeUpdate()
            }
            notifyChange("playlist_songs")
        }

        override fun getSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>> = tableFlow("playlist_songs") {
            synchronized(connection) {
                val ps = connection.prepareStatement("""
                    SELECT songs.* FROM songs 
                    INNER JOIN playlist_songs ON songs.id = playlist_songs.songId 
                    WHERE playlist_songs.playlistId = ? 
                    ORDER BY playlist_songs.position ASC
                """.trimIndent())
                ps.setLong(1, playlistId)
                val rs = ps.executeQuery()
                val list = mutableListOf<SongEntity>()
                while (rs.next()) {
                    list.add(SongEntity(
                        id = rs.getString("id"),
                        title = rs.getString("title"),
                        artist = rs.getString("artist"),
                        album = rs.getString("album"),
                        duration = rs.getLong("duration"),
                        path = rs.getString("path"),
                        albumArt = rs.getString("albumArt"),
                        dateAdded = rs.getLong("dateAdded"),
                        playCount = rs.getInt("playCount"),
                        videoId = rs.getString("videoId")
                    ))
                }
                list
            }
        }

        override suspend fun updateSongPosition(playlistId: Long, songId: String, position: Int) {
            synchronized(connection) {
                val ps = connection.prepareStatement("UPDATE playlist_songs SET position = ? WHERE playlistId = ? AND songId = ?")
                ps.setInt(1, position)
                ps.setLong(2, playlistId)
                ps.setString(3, songId)
                ps.executeUpdate()
            }
            notifyChange("playlist_songs")
        }

        override fun getAllPlaylistSongsSync(): List<PlaylistSongCrossReference> = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT * FROM playlist_songs")
            val list = mutableListOf<PlaylistSongCrossReference>()
            while (rs.next()) {
                list.add(PlaylistSongCrossReference(rs.getLong("playlistId"), rs.getString("songId"), rs.getInt("position")))
            }
            list
        }

        override fun insertPlaylistSongs(songs: List<PlaylistSongCrossReference>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO playlist_songs (playlistId, songId, position) VALUES (?, ?, ?)")
                for (s in songs) {
                    ps.setLong(1, s.playlistId)
                    ps.setString(2, s.songId)
                    ps.setInt(3, s.position)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("playlist_songs")
        }

        override fun insertPlaylists(playlists: List<PlaylistEntity>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO playlists (id, name, createdAt) VALUES (?, ?, ?)")
                for (p in playlists) {
                    ps.setLong(1, p.id)
                    ps.setString(2, p.name)
                    ps.setLong(3, p.createdAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("playlists")
        }
    }

    override fun favoriteDao(): FavoriteDao = object : FavoriteDao {
        override fun getFavoriteSongs(): Flow<List<SongEntity>> = tableFlow("favorites") {
            synchronized(connection) {
                val rs = connection.createStatement().executeQuery("""
                    SELECT songs.* FROM songs 
                    INNER JOIN favorites ON songs.id = favorites.songId 
                    ORDER BY favorites.addedAt DESC
                """.trimIndent())
                val list = mutableListOf<SongEntity>()
                while (rs.next()) {
                    list.add(SongEntity(
                        id = rs.getString("id"),
                        title = rs.getString("title"),
                        artist = rs.getString("artist"),
                        album = rs.getString("album"),
                        duration = rs.getLong("duration"),
                        path = rs.getString("path"),
                        albumArt = rs.getString("albumArt"),
                        dateAdded = rs.getLong("dateAdded"),
                        playCount = rs.getInt("playCount"),
                        videoId = rs.getString("videoId")
                    ))
                }
                list
            }
        }

        override fun addFavorite(favorite: FavoriteEntity) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO favorites (songId, addedAt) VALUES (?, ?)")
                ps.setString(1, favorite.songId)
                ps.setLong(2, favorite.addedAt)
                ps.executeUpdate()
            }
            notifyChange("favorites")
        }

        override fun removeFavorite(songId: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM favorites WHERE songId = ?")
                ps.setString(1, songId)
                ps.executeUpdate()
            }
            notifyChange("favorites")
        }

        override fun isFavorite(songId: String): Flow<Boolean> = tableFlow("favorites") {
            synchronized(connection) {
                val ps = connection.prepareStatement("SELECT 1 FROM favorites WHERE songId = ?")
                ps.setString(1, songId)
                val rs = ps.executeQuery()
                rs.next()
            }
        }

        override fun getAllFavoritesSync(): List<FavoriteEntity> = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT * FROM favorites")
            val list = mutableListOf<FavoriteEntity>()
            while (rs.next()) list.add(FavoriteEntity(rs.getString("songId"), rs.getLong("addedAt")))
            list
        }

        override fun insertFavorites(favorites: List<FavoriteEntity>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO favorites (songId, addedAt) VALUES (?, ?)")
                for (f in favorites) {
                    ps.setString(1, f.songId)
                    ps.setLong(2, f.addedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("favorites")
        }
    }

    override fun folderDao(): FolderDao = object : FolderDao {
        override fun getAllFolders(): Flow<List<FolderEntity>> = tableFlow("folders") {
            getFoldersSync()
        }

        override fun getFoldersSync(): List<FolderEntity> = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT * FROM folders")
            val list = mutableListOf<FolderEntity>()
            while (rs.next()) list.add(FolderEntity(rs.getString("uri"), rs.getString("name")))
            list
        }

        override fun addFolder(folder: FolderEntity) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO folders (uri, name) VALUES (?, ?)")
                ps.setString(1, folder.uri)
                ps.setString(2, folder.name)
                ps.executeUpdate()
            }
            notifyChange("folders")
        }

        override fun deleteFolder(folder: FolderEntity) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM folders WHERE uri = ?")
                ps.setString(1, folder.uri)
                ps.executeUpdate()
            }
            notifyChange("folders")
        }
    }

    override fun recentHistoryDao(): RecentHistoryDao = object : RecentHistoryDao {
        override fun getRecentHistory(): Flow<List<SongEntity>> = tableFlow("recent_history") {
            synchronized(connection) {
                val rs = connection.createStatement().executeQuery("""
                    SELECT songs.* FROM songs 
                    INNER JOIN recent_history ON songs.id = recent_history.songId 
                    ORDER BY recent_history.playedAt DESC LIMIT 50
                """.trimIndent())
                val list = mutableListOf<SongEntity>()
                while (rs.next()) {
                    list.add(SongEntity(
                        id = rs.getString("id"),
                        title = rs.getString("title"),
                        artist = rs.getString("artist"),
                        album = rs.getString("album"),
                        duration = rs.getLong("duration"),
                        path = rs.getString("path"),
                        albumArt = rs.getString("albumArt"),
                        dateAdded = rs.getLong("dateAdded"),
                        playCount = rs.getInt("playCount"),
                        videoId = rs.getString("videoId")
                    ))
                }
                list
            }
        }

        override fun addRecent(history: RecentHistoryEntity) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO recent_history (songId, playedAt) VALUES (?, ?)")
                ps.setString(1, history.songId)
                ps.setLong(2, history.playedAt)
                ps.executeUpdate()
            }
            notifyChange("recent_history")
        }

        override fun getAllRecentHistorySync(): List<RecentHistoryEntity> = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT * FROM recent_history")
            val list = mutableListOf<RecentHistoryEntity>()
            while (rs.next()) list.add(RecentHistoryEntity(rs.getString("songId"), rs.getLong("playedAt")))
            list
        }

        override suspend fun getHistoryCount(): Int = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM recent_history")
            if (rs.next()) rs.getInt(1) else 0
        }

        override suspend fun getRecentHistoryEntries(limit: Int): List<RecentHistoryEntity> = synchronized(connection) {
            val ps = connection.prepareStatement("SELECT * FROM recent_history ORDER BY playedAt DESC LIMIT ?")
            ps.setInt(1, limit)
            val rs = ps.executeQuery()
            val list = mutableListOf<RecentHistoryEntity>()
            while (rs.next()) list.add(RecentHistoryEntity(rs.getString("songId"), rs.getLong("playedAt")))
            list
        }

        override fun insertRecentHistory(recent: List<RecentHistoryEntity>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO recent_history (songId, playedAt) VALUES (?, ?)")
                for (r in recent) {
                    ps.setString(1, r.songId)
                    ps.setLong(2, r.playedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("recent_history")
        }
    }

    override fun downloadedSongDao(): DownloadedSongDao = object : DownloadedSongDao {
        override fun getAllDownloads(): Flow<List<DownloadedSongEntity>> = tableFlow("downloaded_songs") {
            getAllDownloadsSync()
        }

        override fun getAllDownloadsSync(): List<DownloadedSongEntity> = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT * FROM downloaded_songs ORDER BY downloadedAt DESC")
            val list = mutableListOf<DownloadedSongEntity>()
            while (rs.next()) {
                list.add(DownloadedSongEntity(
                    id = rs.getString("id"),
                    title = rs.getString("title"),
                    artist = rs.getString("artist"),
                    album = rs.getString("album"),
                    thumbnailUrl = rs.getString("thumbnailUrl"),
                    durationMs = rs.getLong("durationMs"),
                    localPath = rs.getString("localPath"),
                    fileSizeBytes = rs.getLong("fileSizeBytes"),
                    downloadedAt = rs.getLong("downloadedAt")
                ))
            }
            list
        }

        override fun insertDownloads(downloads: List<DownloadedSongEntity>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO downloaded_songs 
                    (id, title, artist, album, thumbnailUrl, durationMs, localPath, fileSizeBytes, downloadedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent())
                for (d in downloads) {
                    ps.setString(1, d.id)
                    ps.setString(2, d.title)
                    ps.setString(3, d.artist)
                    ps.setString(4, d.album)
                    ps.setString(5, d.thumbnailUrl)
                    ps.setLong(6, d.durationMs)
                    ps.setString(7, d.localPath)
                    ps.setLong(8, d.fileSizeBytes)
                    ps.setLong(9, d.downloadedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("downloaded_songs")
        }

        override suspend fun getById(id: String): DownloadedSongEntity? = synchronized(connection) {
            val ps = connection.prepareStatement("SELECT * FROM downloaded_songs WHERE id = ? LIMIT 1")
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) {
                DownloadedSongEntity(
                    id = rs.getString("id"),
                    title = rs.getString("title"),
                    artist = rs.getString("artist"),
                    album = rs.getString("album"),
                    thumbnailUrl = rs.getString("thumbnailUrl"),
                    durationMs = rs.getLong("durationMs"),
                    localPath = rs.getString("localPath"),
                    fileSizeBytes = rs.getLong("fileSizeBytes"),
                    downloadedAt = rs.getLong("downloadedAt")
                )
            } else null
        }

        override suspend fun existsById(id: String): Boolean = synchronized(connection) {
            val ps = connection.prepareStatement("SELECT 1 FROM downloaded_songs WHERE id = ?")
            ps.setString(1, id)
            val rs = ps.executeQuery()
            rs.next()
        }

        override suspend fun insert(song: DownloadedSongEntity) {
            insertDownloads(listOf(song))
        }

        override suspend fun deleteById(id: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM downloaded_songs WHERE id = ?")
                ps.setString(1, id)
                ps.executeUpdate()
            }
            notifyChange("downloaded_songs")
        }

        override fun searchDownloads(query: String): Flow<List<DownloadedSongEntity>> = tableFlow("downloaded_songs") {
            val q = "%$query%"
            synchronized(connection) {
                val ps = connection.prepareStatement("SELECT * FROM downloaded_songs WHERE title LIKE ? OR artist LIKE ?")
                ps.setString(1, q)
                ps.setString(2, q)
                val rs = ps.executeQuery()
                val list = mutableListOf<DownloadedSongEntity>()
                while (rs.next()) {
                    list.add(DownloadedSongEntity(
                        id = rs.getString("id"),
                        title = rs.getString("title"),
                        artist = rs.getString("artist"),
                        album = rs.getString("album"),
                        thumbnailUrl = rs.getString("thumbnailUrl"),
                        durationMs = rs.getLong("durationMs"),
                        localPath = rs.getString("localPath"),
                        fileSizeBytes = rs.getLong("fileSizeBytes"),
                        downloadedAt = rs.getLong("downloadedAt")
                    ))
                }
                list
            }
        }
    }

    override fun recentSearchDao(): RecentSearchDao = object : RecentSearchDao {
        override fun getRecentSearches(): Flow<List<RecentSearchEntity>> = tableFlow("recent_searches") {
            synchronized(connection) {
                val rs = connection.createStatement().executeQuery("SELECT * FROM recent_searches ORDER BY timestamp DESC LIMIT 20")
                val list = mutableListOf<RecentSearchEntity>()
                while (rs.next()) list.add(RecentSearchEntity(rs.getString("query"), rs.getLong("timestamp")))
                list
            }
        }

        override suspend fun insert(entity: RecentSearchEntity) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO recent_searches (query, timestamp) VALUES (?, ?)")
                ps.setString(1, entity.query)
                ps.setLong(2, entity.timestamp)
                ps.executeUpdate()
            }
            notifyChange("recent_searches")
        }

        override suspend fun delete(query: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM recent_searches WHERE query = ?")
                ps.setString(1, query)
                ps.executeUpdate()
            }
            notifyChange("recent_searches")
        }

        override suspend fun trimOldEntries() {
            synchronized(connection) {
                connection.createStatement().execute("""
                    DELETE FROM recent_searches WHERE query NOT IN (
                        SELECT query FROM recent_searches ORDER BY timestamp DESC LIMIT 20
                    )
                """.trimIndent())
            }
            notifyChange("recent_searches")
        }

        override suspend fun clearAll() {
            synchronized(connection) {
                connection.createStatement().execute("DELETE FROM recent_searches")
            }
            notifyChange("recent_searches")
        }

        override fun getAllRecentSearchesSync(): List<RecentSearchEntity> = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT * FROM recent_searches")
            val list = mutableListOf<RecentSearchEntity>()
            while (rs.next()) list.add(RecentSearchEntity(rs.getString("query"), rs.getLong("timestamp")))
            list
        }

        override fun insertRecentSearches(searches: List<RecentSearchEntity>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("INSERT OR REPLACE INTO recent_searches (query, timestamp) VALUES (?, ?)")
                for (s in searches) {
                    ps.setString(1, s.query)
                    ps.setLong(2, s.timestamp)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("recent_searches")
        }
    }

    override fun cachedLyricsDao(): CachedLyricsDao = object : CachedLyricsDao {
        override suspend fun getLyricsForSong(songId: String): CachedLyricsEntity? = synchronized(connection) {
            val ps = connection.prepareStatement("SELECT * FROM cached_lyrics WHERE songId = ? LIMIT 1")
            ps.setString(1, songId)
            val rs = ps.executeQuery()
            if (rs.next()) {
                CachedLyricsEntity(
                    songId = rs.getString("songId"),
                    plainLyrics = rs.getString("plainLyrics"),
                    syncedLyrics = rs.getString("syncedLyrics"),
                    trackName = rs.getString("trackName"),
                    artistName = rs.getString("artistName"),
                    cachedAt = rs.getLong("cachedAt")
                )
            } else null
        }

        override suspend fun insertLyrics(lyrics: CachedLyricsEntity) {
            synchronized(connection) {
                val ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO cached_lyrics (songId, plainLyrics, syncedLyrics, trackName, artistName, cachedAt)
                    VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent())
                ps.setString(1, lyrics.songId)
                ps.setString(2, lyrics.plainLyrics)
                ps.setString(3, lyrics.syncedLyrics)
                ps.setString(4, lyrics.trackName)
                ps.setString(5, lyrics.artistName)
                ps.setLong(6, lyrics.cachedAt)
                ps.executeUpdate()
            }
            notifyChange("cached_lyrics")
        }

        override suspend fun deleteLyricsForSong(songId: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM cached_lyrics WHERE songId = ?")
                ps.setString(1, songId)
                ps.executeUpdate()
            }
            notifyChange("cached_lyrics")
        }

        override fun getAllCachedLyricsSync(): List<CachedLyricsEntity> = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT * FROM cached_lyrics")
            val list = mutableListOf<CachedLyricsEntity>()
            while (rs.next()) {
                list.add(CachedLyricsEntity(
                    songId = rs.getString("songId"),
                    plainLyrics = rs.getString("plainLyrics"),
                    syncedLyrics = rs.getString("syncedLyrics"),
                    trackName = rs.getString("trackName"),
                    artistName = rs.getString("artistName"),
                    cachedAt = rs.getLong("cachedAt")
                ))
            }
            list
        }

        override fun insertCachedLyrics(lyrics: List<CachedLyricsEntity>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO cached_lyrics (songId, plainLyrics, syncedLyrics, trackName, artistName, cachedAt)
                    VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent())
                for (l in lyrics) {
                    ps.setString(1, l.songId)
                    ps.setString(2, l.plainLyrics)
                    ps.setString(3, l.syncedLyrics)
                    ps.setString(4, l.trackName)
                    ps.setString(5, l.artistName)
                    ps.setLong(6, l.cachedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("cached_lyrics")
        }
    }

    override fun cachedRecommendationDao(): CachedRecommendationDao = object : CachedRecommendationDao {
        override suspend fun insertAll(songs: List<CachedRecommendationEntity>) {
            synchronized(connection) {
                val ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO cached_recommendations (videoId, title, artist, thumbnailUrl, durationMs, source, seedId, cachedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent())
                for (s in songs) {
                    ps.setString(1, s.videoId)
                    ps.setString(2, s.title)
                    ps.setString(3, s.artist)
                    ps.setString(4, s.thumbnailUrl)
                    ps.setLong(5, s.durationMs)
                    ps.setString(6, s.source)
                    ps.setString(7, s.seedId)
                    ps.setLong(8, s.cachedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            notifyChange("cached_recommendations")
        }

        override suspend fun getRecent(limit: Int): List<CachedRecommendationEntity> = synchronized(connection) {
            val ps = connection.prepareStatement("SELECT * FROM cached_recommendations ORDER BY cachedAt DESC LIMIT ?")
            ps.setInt(1, limit)
            val rs = ps.executeQuery()
            val list = mutableListOf<CachedRecommendationEntity>()
            while (rs.next()) list.add(readRec(rs))
            list
        }

        override suspend fun getBySeed(seedId: String, limit: Int): List<CachedRecommendationEntity> = synchronized(connection) {
            val ps = connection.prepareStatement("SELECT * FROM cached_recommendations WHERE seedId = ? ORDER BY cachedAt DESC LIMIT ?")
            ps.setString(1, seedId)
            ps.setInt(2, limit)
            val rs = ps.executeQuery()
            val list = mutableListOf<CachedRecommendationEntity>()
            while (rs.next()) list.add(readRec(rs))
            list
        }

        override suspend fun deleteExpired(expireBeforeMs: Long) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM cached_recommendations WHERE cachedAt < ?")
                ps.setLong(1, expireBeforeMs)
                ps.executeUpdate()
            }
            notifyChange("cached_recommendations")
        }

        override suspend fun clearAll() {
            synchronized(connection) {
                connection.createStatement().execute("DELETE FROM cached_recommendations")
            }
            notifyChange("cached_recommendations")
        }

        override suspend fun count(): Int = synchronized(connection) {
            val rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM cached_recommendations")
            if (rs.next()) rs.getInt(1) else 0
        }

        private fun readRec(rs: ResultSet): CachedRecommendationEntity = CachedRecommendationEntity(
            videoId = rs.getString("videoId"),
            title = rs.getString("title"),
            artist = rs.getString("artist"),
            thumbnailUrl = rs.getString("thumbnailUrl"),
            durationMs = rs.getLong("durationMs"),
            source = rs.getString("source"),
            seedId = rs.getString("seedId"),
            cachedAt = rs.getLong("cachedAt")
        )
    }

    override fun playedSongHistoryDao(): PlayedSongHistoryDao = object : PlayedSongHistoryDao {
        override fun getPlayedHistory(cutoffTime: Long): Flow<List<PlayedSongHistoryEntity>> = tableFlow("played_songs_history") {
            synchronized(connection) {
                val ps = connection.prepareStatement("SELECT * FROM played_songs_history WHERE playedAt >= ? ORDER BY playedAt DESC LIMIT 100")
                ps.setLong(1, cutoffTime)
                val rs = ps.executeQuery()
                val list = mutableListOf<PlayedSongHistoryEntity>()
                while (rs.next()) {
                    list.add(
                        PlayedSongHistoryEntity(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            artist = rs.getString("artist"),
                            thumbnailUrl = rs.getString("thumbnailUrl"),
                            durationMs = rs.getLong("durationMs"),
                            isOnline = rs.getInt("isOnline") == 1,
                            playedAt = rs.getLong("playedAt")
                        )
                    )
                }
                list
            }
        }

        override suspend fun insertOrUpdate(song: PlayedSongHistoryEntity) {
            synchronized(connection) {
                val ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO played_songs_history (id, title, artist, thumbnailUrl, durationMs, isOnline, playedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent())
                ps.setString(1, song.id)
                ps.setString(2, song.title)
                ps.setString(3, song.artist)
                ps.setString(4, song.thumbnailUrl)
                ps.setLong(5, song.durationMs)
                ps.setInt(6, if (song.isOnline) 1 else 0)
                ps.setLong(7, song.playedAt)
                ps.executeUpdate()
            }
            notifyChange("played_songs_history")
        }

        override suspend fun deleteById(id: String) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM played_songs_history WHERE id = ?")
                ps.setString(1, id)
                ps.executeUpdate()
            }
            notifyChange("played_songs_history")
        }

        override suspend fun clearHistory() {
            synchronized(connection) {
                connection.createStatement().execute("DELETE FROM played_songs_history")
            }
            notifyChange("played_songs_history")
        }

        override suspend fun pruneOldHistory(cutoffTime: Long) {
            synchronized(connection) {
                val ps = connection.prepareStatement("DELETE FROM played_songs_history WHERE playedAt < ?")
                ps.setLong(1, cutoffTime)
                ps.executeUpdate()
            }
            notifyChange("played_songs_history")
        }
    }
}
