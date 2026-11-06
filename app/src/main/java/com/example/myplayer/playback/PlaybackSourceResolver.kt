package com.example.myplayer.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.example.myplayer.data.local.dao.DownloadedSongDao
import com.example.myplayer.data.local.dao.SongDao
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.preferences.AudioSettingsRepository
import com.example.myplayer.data.preferences.StreamQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackSourceResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadedSongDao: DownloadedSongDao,
    private val songDao: SongDao,
    private val innertubeApi: InnertubeApi,
    private val losslessStreamResolver: LosslessStreamResolver,
    private val audioSettingsRepository: AudioSettingsRepository? = null
) {
    private val _currentAudioQuality = MutableStateFlow(AudioQualityInfo())
    val currentAudioQuality: StateFlow<AudioQualityInfo> = _currentAudioQuality.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    private val streamUrlTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val STREAM_URL_TTL_MS = 5 * 60 * 60 * 1000L

    private val prefs by lazy { context.getSharedPreferences("spotify_yt_id_mapping", Context.MODE_PRIVATE) }
    private val spotifyToYoutubeIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activePrefetchJob: Job? = null

    suspend fun resolve(
        request: PlayRequest,
        setCustomError: ((String) -> Unit)? = null
    ): String? {
        val localUri = request.localUri
        val quickId = (localUri?.removePrefix("online://") ?: request.songId)
            .removePrefix("online://")
            .removePrefix("spotify:track:")
            .removePrefix("spotify:")
            .trim()
        val mappedQuickId = spotifyToYoutubeIdCache[quickId] ?: prefs.getString(quickId, null) ?: quickId

        // 1. Check if request.localUri directly points to an existing local/downloaded file on disk
        val directLocalFile = withContext(Dispatchers.IO) {
            resolveExistingLocalFile(localUri, request.title, quickId)
        }
        if (directLocalFile != null) {
            val absPath = directLocalFile.absolutePath
            Log.d("PlaybackSourceResolver", "Playing direct local file on Windows: $absPath")
            _currentAudioQuality.value = losslessStreamResolver.inspectQuality(absPath, isLocalFile = true)
            return absPath
        }

        // 2. ALWAYS check if song is downloaded locally in DB!
        // Ensures downloaded songs play from disk with 0 network usage even if recently played online.
        val downloaded = withContext(Dispatchers.IO) {
            try {
                downloadedSongDao.getById(request.songId)
                    ?: downloadedSongDao.getById(quickId)
                    ?: downloadedSongDao.getById(mappedQuickId)
            } catch (_: Exception) { null }
        }
        if (downloaded != null) {
            val resolvedDownloadFile = withContext(Dispatchers.IO) {
                resolveExistingLocalFile(downloaded.localPath, downloaded.title, downloaded.id)
            }
            if (resolvedDownloadFile != null) {
                val absPath = resolvedDownloadFile.absolutePath
                Log.d("PlaybackSourceResolver", "Playing from local downloaded file: $absPath")
                _currentAudioQuality.value = losslessStreamResolver.inspectQuality(absPath, isLocalFile = true)
                return absPath
            }
        }

        // 3. Fast in-memory stream URL cache (<1ms) when network is available
        if (isNetworkAvailable() && mappedQuickId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            val fastCachedUrl = innertubeApi.getCachedStreamUrl(mappedQuickId)
            if (!fastCachedUrl.isNullOrBlank()) {
                Log.d("PlaybackSourceResolver", "0ms instant cache hit for $mappedQuickId")
                DesktopChunkStreamProxy.registerSongStream(mappedQuickId, fastCachedUrl)
                _currentAudioQuality.value = losslessStreamResolver.inspectQuality(fastCachedUrl, isLocalFile = false)
                return fastCachedUrl
            }
        }

        // 4. Direct HTTP URL
        if (!request.streamUrl.isNullOrBlank() && request.streamUrl.startsWith("http")) {
            DesktopChunkStreamProxy.registerSongStream(mappedQuickId, request.streamUrl)
            _currentAudioQuality.value = losslessStreamResolver.inspectQuality(request.streamUrl, isLocalFile = false)
            return request.streamUrl
        } else if (!localUri.isNullOrBlank() && (localUri.startsWith("http://") || localUri.startsWith("https://"))) {
            DesktopChunkStreamProxy.registerSongStream(mappedQuickId, localUri)
            _currentAudioQuality.value = losslessStreamResolver.inspectQuality(localUri, isLocalFile = false)
            return localUri
        }

        var cleanId = mappedQuickId
        if (cleanId.isBlank()) {
            setCustomError?.invoke("Invalid track ID")
            return null
        }

        // 5. If cleanId is a 22-char Spotify ID, map to 11-char YouTube ID
        val isYouTubeId = cleanId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))
        if (!isYouTubeId) {
            val dbSong = withContext(Dispatchers.IO) {
                try { songDao.getSongById(request.songId) } catch (_: Exception) { null }
            }
            val existingYtId = dbSong?.videoId?.takeIf { it.matches(Regex("^[a-zA-Z0-9_-]{11}$")) }
            if (existingYtId != null) {
                spotifyToYoutubeIdCache[quickId] = existingYtId
                prefs.edit().putString(quickId, existingYtId).apply()
                cleanId = existingYtId

                val mappedDownload = withContext(Dispatchers.IO) {
                    try { downloadedSongDao.getById(existingYtId) } catch (_: Exception) { null }
                }
                if (mappedDownload != null) {
                    val mappedFile = withContext(Dispatchers.IO) {
                        resolveExistingLocalFile(mappedDownload.localPath, mappedDownload.title, mappedDownload.id)
                    }
                    if (mappedFile != null) {
                        val absPath = mappedFile.absolutePath
                        _currentAudioQuality.value = losslessStreamResolver.inspectQuality(absPath, isLocalFile = true)
                        return absPath
                    }
                }
            } else {
                val qTitle = request.title.ifBlank { dbSong?.title ?: request.metadata["title"] ?: "" }
                val qArtist = request.artist.ifBlank { dbSong?.artist ?: request.metadata["artist"] ?: "" }
                if (qTitle.isNotBlank()) {
                    val matchedYtId = withContext(Dispatchers.IO) {
                        try {
                            innertubeApi.search("$qTitle $qArtist".trim(), filtered = true)
                                .songs.firstOrNull()?.videoId
                        } catch (e: Exception) {
                            Log.w("PlaybackSourceResolver", "Spotify->YouTube match failed for $cleanId", e)
                            null
                        }
                    }
                    if (!matchedYtId.isNullOrBlank()) {
                        spotifyToYoutubeIdCache[quickId] = matchedYtId
                        prefs.edit().putString(quickId, matchedYtId).apply()
                        cleanId = matchedYtId
                        prefetchScope.launch {
                            try {
                                songDao.updateSongVideoId(request.songId, matchedYtId)
                                songDao.updateSongPath(request.songId, "online://$matchedYtId")
                                if (dbSong != null) {
                                    songDao.insertSongs(
                                        listOf(dbSong.copy(videoId = matchedYtId, path = "online://$matchedYtId"))
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    } else {
                        setCustomError?.invoke("Could not find stream for '$qTitle'")
                        return null
                    }
                } else {
                    setCustomError?.invoke("Could not find stream for '${request.songId}'")
                    return null
                }
            }
        }

        return resolveOnlineStream(cleanId, setCustomError, forceRefresh = false)
    }

    /**
     * Resolves any Windows/Desktop path (`C:\...`, `C:/...`, `file:/C:/...`, `file://...`, `%20`)
     * or fallback file in `~/.myplayer/files/Music` into a verified existing non-empty [File].
     */
    fun resolveExistingLocalFile(
        rawPath: String?,
        titleHint: String? = null,
        songIdHint: String? = null
    ): File? {
        if (!rawPath.isNullOrBlank()) {
            val resolved = DesktopAudioBackend.resolveLocalFile(rawPath)
            if (resolved != null && resolved.exists() && resolved.length() > 0L) {
                return resolved
            }
        }

        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        if (!musicDir.exists()) return null

        if (!titleHint.isNullOrBlank()) {
            val safeTitle = titleHint
                .replace(Regex("[^a-zA-Z0-9 _\\-]"), "")
                .replace(Regex(" {2,}"), " ")
                .trim()
                .take(60)
            if (safeTitle.isNotBlank()) {
                val byTitle = File(musicDir, "$safeTitle.m4a")
                if (byTitle.exists() && byTitle.length() > 0L) return byTitle
                if (!songIdHint.isNullOrBlank()) {
                    val byTitleAndId = File(musicDir, "${safeTitle}_${songIdHint}.m4a")
                    if (byTitleAndId.exists() && byTitleAndId.length() > 0L) return byTitleAndId
                }
            }
        }

        if (!songIdHint.isNullOrBlank() && songIdHint.length >= 6) {
            val byId = File(musicDir, "$songIdHint.m4a")
            if (byId.exists() && byId.length() > 0L) return byId
        }

        return null
    }

    private suspend fun checkLocalFileExists(filePath: String): Boolean = withContext(Dispatchers.IO) {
        resolveExistingLocalFile(filePath) != null
    }

    suspend fun resolveFreshStreamUrl(
        videoId: String,
        setCustomError: ((String) -> Unit)? = null
    ): String? {
        streamUrlTimestamps.remove(videoId)
        return resolveOnlineStream(videoId, setCustomError = setCustomError, forceRefresh = true)
    }

    fun cancelPrefetches() {
        activePrefetchJob?.cancel()
        innertubeApi.cancelAllPrefetches()
    }

    suspend fun resolveOnlineStream(
        videoId: String,
        setCustomError: ((String) -> Unit)? = null,
        forceRefresh: Boolean = false
    ): String? {
        _isResolving.value = true
        try {
            if (!forceRefresh) {
                val cached = innertubeApi.getCachedStreamUrl(videoId)
                if (!cached.isNullOrBlank()) {
                    val ts = streamUrlTimestamps[videoId]
                    val expiredByResolver = ts != null && (System.currentTimeMillis() - ts) >= STREAM_URL_TTL_MS
                    if (!expiredByResolver) {
                        if (ts == null) streamUrlTimestamps[videoId] = System.currentTimeMillis()
                        DesktopChunkStreamProxy.registerSongStream(videoId, cached)
                        _currentAudioQuality.value = losslessStreamResolver.inspectQuality(cached, isLocalFile = false)
                        return cached
                    }
                }
            }

            if (!isNetworkAvailable()) {
                Log.e("PlaybackSourceResolver", "Offline, cannot play online song: $videoId")
                setCustomError?.invoke("No internet connection. Cannot stream song.")
                return null
            }

            val config = audioSettingsRepository?.configFlow?.firstOrNull()
            val targetQuality = if (isWifiConnected()) {
                config?.wifiQuality ?: StreamQuality.HIGH
            } else {
                config?.mobileQuality ?: StreamQuality.MEDIUM
            }

            val streamUrl = withContext(Dispatchers.IO) {
                try {
                    innertubeApi.getStreamUrl(videoId, targetQuality = targetQuality, forceRefresh = forceRefresh)
                } catch (e: Exception) {
                    Log.e("PlaybackSourceResolver", "Failed to resolve stream URL for $videoId", e)
                    null
                }
            }

            if (!streamUrl.isNullOrBlank()) {
                streamUrlTimestamps[videoId] = System.currentTimeMillis()
                DesktopChunkStreamProxy.registerSongStream(videoId, streamUrl)
                _currentAudioQuality.value = losslessStreamResolver.inspectQuality(streamUrl, isLocalFile = false)
                return streamUrl
            } else {
                setCustomError?.invoke("Failed to load audio stream. Please check connection.")
                return null
            }
        } finally {
            _isResolving.value = false
        }
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun prefetchVisibleStreams(videoIds: List<String>) {
        innertubeApi.prefetchVisibleStreams(videoIds)
    }

    fun prefetchSongs(requests: List<PlayRequest>) {
        if (requests.isEmpty()) return
        activePrefetchJob?.cancel()
        activePrefetchJob = prefetchScope.launch {
            for (req in requests.take(3)) {
                if (!isActive) break
                try {
                    resolve(req)
                } catch (_: Exception) {}
            }
        }
    }
}
