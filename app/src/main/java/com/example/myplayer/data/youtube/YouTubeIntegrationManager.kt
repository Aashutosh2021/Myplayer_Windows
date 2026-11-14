package com.example.myplayer.data.youtube

import android.content.Context
import android.util.Log
import com.example.myplayer.data.local.dao.FavoriteDao
import com.example.myplayer.data.local.dao.PlaylistDao
import com.example.myplayer.data.local.dao.SongDao
import com.example.myplayer.data.local.entity.FavoriteEntity
import com.example.myplayer.data.local.entity.PlaylistEntity
import com.example.myplayer.data.local.entity.PlaylistSongCrossReference
import com.example.myplayer.data.local.entity.SongEntity
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.online.model.OnlineSong
import com.example.myplayer.data.recommendation.ai.OnDeviceMusicAiModel
import com.example.myplayer.security.StringEncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubeTrackSeed(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val weight: Float,
    val thumbnailUrl: String? = null
)

data class YouTubePlaylistSummary(
    val playlistId: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val trackCount: Int
)

data class YouTubeFullSyncResult(
    val likedSongsCount: Int,
    val playlistsCount: Int,
    val recommendationsCount: Int,
    val totalTracksSynced: Int
)

@Singleton
class YouTubeIntegrationManager internal constructor(
    context: Context?,
    @Suppress("UNUSED_PARAMETER") isTest: Boolean,
    private val okHttpClient: OkHttpClient? = null,
    private val enc: StringEncryptionManager? = null,
    private val innertubeApi: InnertubeApi? = null,
    private val aiModel: OnDeviceMusicAiModel? = null,
    private val songDao: SongDao? = null,
    private val playlistDao: PlaylistDao? = null,
    private val favoriteDao: FavoriteDao? = null
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        enc: StringEncryptionManager,
        innertubeApi: InnertubeApi,
        aiModel: OnDeviceMusicAiModel,
        songDao: SongDao,
        playlistDao: PlaylistDao,
        favoriteDao: FavoriteDao
    ) : this(context, false, okHttpClient, enc, innertubeApi, aiModel, songDao, playlistDao, favoriteDao)

    constructor() : this(null, true)

    companion object {
        private const val TAG = "YouTubeIntegration"
        private const val PREFS_NAME = "youtube_integration_prefs"

        const val LOGIN_URL = "https://music.youtube.com/"
        const val YTM_ORIGIN = "https://music.youtube.com"
        const val USER_AGENT_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()

        fun extractPlaylistId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return null

            val listParamRegex = Regex("""[?&]list=([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
            listParamRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { raw ->
                return raw.removePrefix("VL")
            }

            val browseIdRegex = Regex("""^(?:VL)?((?:PL|OLAK5uy_|RDCLAK5uy_|UU|FL|LP|RD)[a-zA-Z0-9_-]{10,}|LM|LL)$""")
            browseIdRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

            if (!trimmed.contains(" ") && !trimmed.contains("/") && trimmed.length in 12..64) {
                return trimmed.removePrefix("VL")
            }
            return null
        }
    }

    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val backgroundSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fullSyncMutex = Mutex()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val BASE_URL: String by lazy { enc?.baseUrl ?: "https://music.youtube.com/youtubei/v1" }
    private val API_KEY: String by lazy { enc?.ytmApiKey ?: "" }

    init {
        val savedCookies = prefs?.getString("yt_cookies", null)
        _isLoggedIn.value = !savedCookies.isNullOrBlank() && extractSapIsid(savedCookies) != null
        _username.value = prefs?.getString("yt_username", null)
    }

    suspend fun completeLoginWithCookies(rawCookieInput: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedCookies = normalizeCookieString(rawCookieInput)
            val sapisid = extractSapIsid(normalizedCookies)
                ?: throw IllegalArgumentException("SAPISID / __Secure-3PAPISID cookie not found. Please copy your YouTube Music cookie string or SAPISID value.")

            prefs?.edit()
                ?.putString("yt_cookies", normalizedCookies)
                ?.putString("yt_sapisid", sapisid)
                ?.apply()

            _isLoggedIn.value = true

            val accountName = try {
                fetchAccountName(normalizedCookies, sapisid) ?: "YouTube Music User"
            } catch (e: Exception) {
                Log.w(TAG, "Account name lookup delayed, defaulting to YouTube Music User", e)
                "YouTube Music User"
            }

            prefs?.edit()?.putString("yt_username", accountName)?.apply()
            _username.value = accountName

            backgroundSyncScope.launch {
                try {
                    syncYouTubeTasteIntoAi()
                } catch (e: Exception) {
                    Log.w(TAG, "Background YouTube taste sync error", e)
                }
            }

            accountName
        }
    }

    fun logout() {
        prefs?.edit()?.clear()?.apply()
        _isLoggedIn.value = false
        _username.value = null
        aiModel?.clearYouTubePersonalizedSeeds()
    }

    private fun normalizeCookieString(input: String): String {
        val trimmed = input.trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
        if (!trimmed.contains("=") && trimmed.length >= 16 && !trimmed.contains(" ")) {
            return "SAPISID=$trimmed; __Secure-3PAPISID=$trimmed; CONSENT=YES+"
        }
        return trimmed
    }

    fun extractSapIsid(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val map = cookieHeader.split(";").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim().isNotEmpty() && kv[1].trim().isNotEmpty()) {
                kv[0].trim() to kv[1].trim()
            } else null
        }.toMap()

        return map["SAPISID"]
            ?: map["__Secure-3PAPISID"]
            ?: map["__Secure-1PAPISID"]
    }

    private fun buildSapisidHashHeader(sapisid: String, origin: String = YTM_ORIGIN): String {
        val timestampSec = System.currentTimeMillis() / 1000L
        val payload = "$timestampSec $sapisid $origin"
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestampSec}_$sha1 SAPISID1PHASH ${timestampSec}_$sha1 SAPISID3PHASH ${timestampSec}_$sha1"
    }

    suspend fun syncYouTubeTasteIntoAi(): List<YouTubeTrackSeed> = withContext(Dispatchers.IO) {
        val cookies = prefs?.getString("yt_cookies", null) ?: return@withContext emptyList()
        val sapisid = extractSapIsid(cookies) ?: return@withContext emptyList()

        _isSyncing.value = true
        try {
            val seeds = mutableListOf<YouTubeTrackSeed>()
            val personalizedOnlineSongs = mutableListOf<OnlineSong>()

            try {
                val homeTracks = fetchPersonalizedHomeRecommendations(cookies, sapisid, limit = 50)
                homeTracks.forEachIndexed { index, song ->
                    val posFactor = 1.0f - (index.toFloat() / homeTracks.size.coerceAtLeast(1)) * 0.4f
                    seeds.add(
                        YouTubeTrackSeed(
                            videoId = song.videoId,
                            title = song.title,
                            artist = song.artist,
                            durationMs = song.durationMs,
                            weight = 2.0f * posFactor,
                            thumbnailUrl = song.thumbnailUrl
                        )
                    )
                }
                personalizedOnlineSongs.addAll(homeTracks)
            } catch (e: Exception) {
                Log.w(TAG, "FEmusic_home fetch failed", e)
            }

            delay(120L)

            try {
                val likedTracks = fetchLikedSongs(cookies, sapisid, limit = 60)
                likedTracks.forEachIndexed { index, seed ->
                    val posFactor = 1.0f - (index.toFloat() / likedTracks.size.coerceAtLeast(1)) * 0.45f
                    seeds.add(seed.copy(weight = 1.8f * posFactor))
                    personalizedOnlineSongs.add(
                        OnlineSong(
                            videoId = seed.videoId,
                            title = seed.title,
                            artist = seed.artist,
                            thumbnailUrl = seed.thumbnailUrl ?: "https://img.youtube.com/vi/${seed.videoId}/hqdefault.jpg",
                            durationMs = seed.durationMs
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Liked songs fetch failed", e)
            }

            delay(120L)

            try {
                val historyTracks = fetchListeningHistory(cookies, sapisid, limit = 40)
                historyTracks.forEachIndexed { index, seed ->
                    val posFactor = 1.0f - (index.toFloat() / historyTracks.size.coerceAtLeast(1)) * 0.45f
                    seeds.add(seed.copy(weight = 1.6f * posFactor))
                }
            } catch (e: Exception) {
                Log.w(TAG, "History fetch failed", e)
            }

            val deduplicated = seeds.distinctBy { it.videoId }
            Log.d(TAG, "Synced ${deduplicated.size} personalized YouTube tracks into OnDeviceMusicAiModel")

            if (personalizedOnlineSongs.isNotEmpty()) {
                aiModel?.registerYouTubePersonalizedSeeds(personalizedOnlineSongs.distinctBy { it.videoId })
            }

            deduplicated.take(70).forEach { track ->
                aiModel?.learnFromInteraction(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationMs = track.durationMs,
                    reward = track.weight
                )
            }

            backgroundSyncScope.launch {
                delay(2_500L)
                runCatching { syncAllYouTubeLibraryAndPlaylists() }
                    .onFailure { Log.w(TAG, "Full YouTube library sync failed", it) }
            }

            deduplicated
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun syncAllYouTubeLibraryAndPlaylists(): Result<YouTubeFullSyncResult> = fullSyncMutex.withLock {
        withContext(Dispatchers.IO) {
            _isSyncing.value = true
            runCatching {
                val cookies = prefs?.getString("yt_cookies", null)
                    ?: throw IllegalStateException("Not logged in to YouTube Music")
                val sapisid = extractSapIsid(cookies)
                    ?: throw IllegalStateException("Invalid YouTube Music session cookies")

                var totalTracks = 0
                val allCollectedSeeds = mutableListOf<YouTubeTrackSeed>()

                val homeRecos = runCatching {
                    fetchPersonalizedHomeRecommendations(cookies, sapisid, limit = 50)
                }.getOrDefault(emptyList())
                if (homeRecos.isNotEmpty()) {
                    aiModel?.registerYouTubePersonalizedSeeds(homeRecos)
                }

                val likedTracks = fetchLikedSongs(cookies, sapisid, limit = 100)
                if (likedTracks.isNotEmpty()) {
                    val songEntities = likedTracks.map { seed ->
                        SongEntity(
                            id = seed.videoId,
                            title = seed.title,
                            artist = seed.artist,
                            album = "YouTube Liked Music",
                            duration = seed.durationMs,
                            path = "online://${seed.videoId}",
                            albumArt = seed.thumbnailUrl ?: "https://img.youtube.com/vi/${seed.videoId}/hqdefault.jpg",
                            dateAdded = System.currentTimeMillis(),
                            videoId = seed.videoId
                        )
                    }
                    songDao?.insertSongs(songEntities)
                    favoriteDao?.insertFavorites(
                        songEntities.map { FavoriteEntity(songId = it.id, addedAt = System.currentTimeMillis()) }
                    )
                    allCollectedSeeds.addAll(likedTracks)
                    totalTracks += likedTracks.size
                    Log.d(TAG, "Synced ${likedTracks.size} YouTube Liked Songs to Favorites")
                }

                delay(150L)

                val playlists = fetchUserPlaylists(cookies, sapisid)
                var playlistsSynced = 0
                for (pl in playlists) {
                    delay(150L)
                    try {
                        val tracks = fetchPlaylistTracks(pl.playlistId, cookies, sapisid)
                        if (tracks.isEmpty()) continue

                        val existingPl = playlistDao?.getPlaylistByName(pl.title)
                        val plId = existingPl?.id ?: playlistDao?.insertPlaylist(PlaylistEntity(name = pl.title)) ?: 0L

                        val trackEntities = mutableListOf<SongEntity>()
                        val crossRefs = mutableListOf<PlaylistSongCrossReference>()

                        tracks.forEachIndexed { pos, seed ->
                            trackEntities.add(
                                SongEntity(
                                    id = seed.videoId,
                                    title = seed.title,
                                    artist = seed.artist,
                                    album = pl.title,
                                    duration = seed.durationMs,
                                    path = "online://${seed.videoId}",
                                    albumArt = seed.thumbnailUrl ?: pl.thumbnailUrl ?: "https://img.youtube.com/vi/${seed.videoId}/hqdefault.jpg",
                                    dateAdded = System.currentTimeMillis(),
                                    videoId = seed.videoId
                                )
                            )
                            crossRefs.add(
                                PlaylistSongCrossReference(
                                    playlistId = plId,
                                    songId = seed.videoId,
                                    position = pos
                                )
                            )
                        }

                        songDao?.insertSongs(trackEntities)
                        playlistDao?.insertPlaylistSongs(crossRefs)
                        allCollectedSeeds.addAll(tracks)
                        totalTracks += tracks.size
                        playlistsSynced++
                        Log.d(TAG, "Synced YouTube playlist '${pl.title}' (${tracks.size} tracks)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sync YouTube playlist '${pl.title}'", e)
                    }
                }

                allCollectedSeeds.distinctBy { it.videoId }
                    .take(100)
                    .forEach { track ->
                        aiModel?.learnFromInteraction(
                            videoId = track.videoId,
                            title = track.title,
                            artist = track.artist,
                            durationMs = track.durationMs,
                            reward = 1.85f
                        )
                    }

                YouTubeFullSyncResult(
                    likedSongsCount = likedTracks.size,
                    playlistsCount = playlistsSynced,
                    recommendationsCount = homeRecos.size,
                    totalTracksSynced = totalTracks
                )
            }.also {
                _isSyncing.value = false
            }
        }
    }

    suspend fun importPlaylistByUrl(urlOrId: String): Result<Pair<String, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val playlistId = extractPlaylistId(urlOrId)
                ?: throw IllegalArgumentException("Invalid YouTube / YouTube Music playlist URL or ID")

            val cookies = prefs?.getString("yt_cookies", null)
            val sapisid = extractSapIsid(cookies)

            val (playlistTitle, tracks) = fetchPlaylistDetailsAndTracks(playlistId, cookies, sapisid)
            if (tracks.isEmpty()) {
                throw IllegalStateException("Playlist is empty or private (sign in to YouTube Music if it's a private playlist)")
            }

            val existingPl = playlistDao?.getPlaylistByName(playlistTitle)
            val plId = existingPl?.id ?: playlistDao?.insertPlaylist(PlaylistEntity(name = playlistTitle)) ?: 0L

            val trackEntities = tracks.map { seed ->
                SongEntity(
                    id = seed.videoId,
                    title = seed.title,
                    artist = seed.artist,
                    album = playlistTitle,
                    duration = seed.durationMs,
                    path = "online://${seed.videoId}",
                    albumArt = seed.thumbnailUrl ?: "https://img.youtube.com/vi/${seed.videoId}/hqdefault.jpg",
                    dateAdded = System.currentTimeMillis(),
                    videoId = seed.videoId
                )
            }

            val crossRefs = tracks.mapIndexed { pos, seed ->
                PlaylistSongCrossReference(playlistId = plId, songId = seed.videoId, position = pos)
            }

            songDao?.insertSongs(trackEntities)
            playlistDao?.insertPlaylistSongs(crossRefs)

            tracks.take(60).forEach { track ->
                aiModel?.learnFromInteraction(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationMs = track.durationMs,
                    reward = 1.65f
                )
            }

            playlistTitle to tracks.size
        }
    }

    suspend fun reportPlayedSongAndFetchYouTubeRecommendations(
        videoId: String,
        title: String,
        artist: String
    ): List<YouTubeTrackSeed> = withContext(Dispatchers.IO) {
        val cookies = prefs?.getString("yt_cookies", null) ?: return@withContext emptyList()
        val sapisid = extractSapIsid(cookies) ?: return@withContext emptyList()
        try {
            val cleanId = InnertubeApi.extractYouTubeVideoId(videoId) ?: videoId.removePrefix("online://").trim()
            val resolvedId = if (cleanId.length == 11) {
                cleanId
            } else {
                innertubeApi?.search("$title $artist", filtered = true)?.songs?.firstOrNull()?.videoId
                    ?: return@withContext emptyList()
            }

            val bodyJson = buildClientContextJson().apply {
                put("videoId", resolvedId)
                put("playlistId", "RDAMVM$resolvedId")
                put("isAudioOnly", true)
            }

            val responseStr = innertubePost("next", bodyJson, cookies, sapisid)
            val onlineTracks = parseNextPanelTracks(JSONObject(responseStr), excludeVideoId = resolvedId)

            if (onlineTracks.isNotEmpty()) {
                aiModel?.registerYouTubePersonalizedSeeds(onlineTracks.take(20))
            }

            val seeds = onlineTracks.mapIndexed { idx, song ->
                val weight = (2.2f - (idx * 0.12f)).coerceAtLeast(1.0f)
                aiModel?.learnFromInteraction(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = song.durationMs,
                    reward = weight
                )
                YouTubeTrackSeed(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = song.durationMs,
                    weight = weight,
                    thumbnailUrl = song.thumbnailUrl
                )
            }
            seeds
        } catch (e: Exception) {
            Log.w(TAG, "Authenticated YouTube next recommendation bridge failed", e)
            emptyList()
        }
    }

    private fun fetchAccountName(cookies: String, sapisid: String): String? {
        return try {
            val bodyJson = buildClientContextJson()
            val resp = innertubePost("account/account_menu", bodyJson, cookies, sapisid)
            val root = JSONObject(resp)
            val header = root.optJSONArray("actions")
                ?.optJSONObject(0)
                ?.optJSONObject("openPopupAction")
                ?.optJSONObject("popup")
                ?.optJSONObject("multiPageMenuRenderer")
                ?.optJSONObject("header")
                ?.optJSONObject("activeAccountHeaderRenderer")

            val accountName = header?.optJSONObject("accountName")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
            val channelHandle = header?.optJSONObject("channelHandle")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")

            when {
                !accountName.isNullOrBlank() -> accountName
                !channelHandle.isNullOrBlank() -> channelHandle
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAccountName failed", e)
            null
        }
    }

    suspend fun fetchPersonalizedHomeRecommendations(
        cookies: String,
        sapisid: String,
        limit: Int = 50
    ): List<OnlineSong> = withContext(Dispatchers.IO) {
        val out = mutableListOf<OnlineSong>()
        try {
            val bodyJson = buildClientContextJson().apply {
                put("browseId", "FEmusic_home")
            }
            val resp = innertubePost("browse", bodyJson, cookies, sapisid)
            val root = JSONObject(resp)
            extractSongsRecursively(root, out, limit)
        } catch (e: Exception) {
            Log.w(TAG, "fetchPersonalizedHomeRecommendations failed", e)
        }
        out.distinctBy { it.videoId }.take(limit)
    }

    suspend fun fetchLikedSongs(
        cookies: String,
        sapisid: String,
        limit: Int = 100
    ): List<YouTubeTrackSeed> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<OnlineSong>()
        for (browseId in listOf("FEmusic_liked_videos", "VLLM")) {
            try {
                val bodyJson = buildClientContextJson().apply {
                    put("browseId", browseId)
                }
                val resp = innertubePost("browse", bodyJson, cookies, sapisid)
                extractSongsRecursively(JSONObject(resp), songs, limit)
                if (songs.isNotEmpty()) break
            } catch (e: Exception) {
                Log.w(TAG, "fetchLikedSongs failed for $browseId", e)
            }
        }
        songs.distinctBy { it.videoId }.take(limit).map { s ->
            YouTubeTrackSeed(
                videoId = s.videoId,
                title = s.title,
                artist = s.artist,
                durationMs = s.durationMs,
                weight = 1.5f,
                thumbnailUrl = s.thumbnailUrl
            )
        }
    }

    suspend fun fetchListeningHistory(
        cookies: String,
        sapisid: String,
        limit: Int = 50
    ): List<YouTubeTrackSeed> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<OnlineSong>()
        try {
            val bodyJson = buildClientContextJson().apply {
                put("browseId", "FEmusic_history")
            }
            val resp = innertubePost("browse", bodyJson, cookies, sapisid)
            extractSongsRecursively(JSONObject(resp), songs, limit)
        } catch (e: Exception) {
            Log.w(TAG, "fetchListeningHistory failed", e)
        }
        songs.distinctBy { it.videoId }.take(limit).map { s ->
            YouTubeTrackSeed(
                videoId = s.videoId,
                title = s.title,
                artist = s.artist,
                durationMs = s.durationMs,
                weight = 1.4f,
                thumbnailUrl = s.thumbnailUrl
            )
        }
    }

    suspend fun fetchUserPlaylists(
        cookies: String,
        sapisid: String
    ): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        val out = mutableListOf<YouTubePlaylistSummary>()
        try {
            val bodyJson = buildClientContextJson().apply {
                put("browseId", "FEmusic_liked_playlists")
            }
            val resp = innertubePost("browse", bodyJson, cookies, sapisid)
            extractPlaylistsRecursively(JSONObject(resp), out)
        } catch (e: Exception) {
            Log.w(TAG, "fetchUserPlaylists failed", e)
        }
        out.distinctBy { it.playlistId }
            .filter { it.playlistId != "LM" && it.playlistId != "VLLM" && !it.playlistId.startsWith("SE") }
    }

    suspend fun fetchPlaylistTracks(
        playlistId: String,
        cookies: String? = prefs?.getString("yt_cookies", null),
        sapisid: String? = extractSapIsid(cookies)
    ): List<YouTubeTrackSeed> {
        return fetchPlaylistDetailsAndTracks(playlistId, cookies, sapisid).second
    }

    private suspend fun fetchPlaylistDetailsAndTracks(
        rawPlaylistId: String,
        cookies: String?,
        sapisid: String?
    ): Pair<String, List<YouTubeTrackSeed>> = withContext(Dispatchers.IO) {
        val cleanId = rawPlaylistId.removePrefix("VL").trim()
        val browseId = if (cleanId.startsWith("VL")) cleanId else "VL$cleanId"
        val collected = mutableListOf<OnlineSong>()
        var playlistTitle = ""

        try {
            val bodyJson = buildClientContextJson().apply {
                put("browseId", browseId)
            }
            val resp = innertubePost("browse", bodyJson, cookies, sapisid)
            val root = JSONObject(resp)
            playlistTitle = extractPlaylistHeaderTitle(root) ?: ""
            extractSongsRecursively(root, collected, 200)
        } catch (e: Exception) {
            Log.w(TAG, "Innertube browse failed for $browseId", e)
        }

        if (collected.isEmpty()) {
            try {
                val url = "https://www.youtube.com/playlist?list=$cleanId"
                val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
                extractor.fetchPage()
                if (playlistTitle.isBlank()) {
                    playlistTitle = extractor.name?.takeIf { it.isNotBlank() } ?: "YouTube Playlist"
                }
                val initialItems = extractor.initialPage?.items.orEmpty()
                for (item in initialItems) {
                    val streamItem = item as? StreamInfoItem ?: continue
                    val vId = InnertubeApi.extractYouTubeVideoId(streamItem.url ?: "") ?: continue
                    val title = streamItem.name?.takeIf { it.isNotBlank() } ?: continue
                    val artist = streamItem.uploaderName?.replace(Regex("(?i)-\\s*topic"), "")?.trim()
                        ?.takeIf { it.isNotBlank() } ?: "YouTube"
                    val durMs = (streamItem.duration.coerceAtLeast(0L)) * 1000L
                    val thumb = streamItem.thumbnails?.lastOrNull()?.url
                        ?: "https://img.youtube.com/vi/$vId/hqdefault.jpg"
                    collected.add(
                        OnlineSong(
                            videoId = vId,
                            title = title,
                            artist = artist,
                            thumbnailUrl = thumb,
                            durationMs = durMs
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "NewPipe playlist fallback failed for $cleanId", e)
            }
        }

        val finalTitle = playlistTitle.ifBlank { "YouTube Playlist" }
        val seeds = collected.distinctBy { it.videoId }.map { s ->
            YouTubeTrackSeed(
                videoId = s.videoId,
                title = s.title,
                artist = s.artist,
                durationMs = s.durationMs,
                weight = 1.5f,
                thumbnailUrl = s.thumbnailUrl
            )
        }
        finalTitle to seeds
    }

    private fun extractPlaylistHeaderTitle(root: JSONObject): String? {
        val header = root.optJSONObject("header")
        val detailTitle = header?.optJSONObject("musicDetailHeaderRenderer")
            ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
        if (!detailTitle.isNullOrBlank()) return detailTitle

        val editableTitle = header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
            ?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
            ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
        if (!editableTitle.isNullOrBlank()) return editableTitle

        val responsiveTitle = findFirstKeyObject(root, "musicResponsiveHeaderRenderer")
            ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
        return responsiveTitle?.takeIf { it.isNotBlank() }
    }

    private fun findFirstKeyObject(node: Any?, targetKey: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has(targetKey)) {
                    node.optJSONObject(targetKey)?.let { return it }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    findFirstKeyObject(node.opt(k), targetKey)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findFirstKeyObject(node.opt(i), targetKey)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractSongsRecursively(node: Any?, out: MutableList<OnlineSong>, maxCount: Int) {
        if (out.size >= maxCount || node == null) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                    parseResponsiveSongItem(renderer)?.let { song ->
                        out.add(song)
                        if (out.size >= maxCount) return
                    }
                }
                node.optJSONObject("musicTwoRowItemRenderer")?.let { renderer ->
                    parseTwoRowSongItem(renderer)?.let { song ->
                        out.add(song)
                        if (out.size >= maxCount) return
                    }
                }
                node.optJSONObject("playlistPanelVideoRenderer")?.let { renderer ->
                    parsePanelVideoSongItem(renderer)?.let { song ->
                        out.add(song)
                        if (out.size >= maxCount) return
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "musicResponsiveListItemRenderer" ||
                        key == "musicTwoRowItemRenderer" ||
                        key == "playlistPanelVideoRenderer"
                    ) continue
                    extractSongsRecursively(node.opt(key), out, maxCount)
                    if (out.size >= maxCount) return
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractSongsRecursively(node.opt(i), out, maxCount)
                    if (out.size >= maxCount) return
                }
            }
        }
    }

    private fun extractPlaylistsRecursively(node: Any?, out: MutableList<YouTubePlaylistSummary>) {
        if (node == null) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicTwoRowItemRenderer")?.let { renderer ->
                    parseTwoRowPlaylistItem(renderer)?.let { out.add(it) }
                }
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                    parseResponsivePlaylistItem(renderer)?.let { out.add(it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "musicTwoRowItemRenderer" || key == "musicResponsiveListItemRenderer") continue
                    extractPlaylistsRecursively(node.opt(key), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractPlaylistsRecursively(node.opt(i), out)
                }
            }
        }
    }

    private fun parseResponsiveSongItem(renderer: JSONObject): OnlineSong? {
        val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
        val title = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() } ?: return null

        val subtitleRuns = flexColumns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        var artist = "Unknown Artist"
        var durationText = ""
        if (subtitleRuns != null && subtitleRuns.length() > 0) {
            artist = subtitleRuns.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            for (i in subtitleRuns.length() - 1 downTo 0) {
                val txt = subtitleRuns.optJSONObject(i)?.optString("text") ?: continue
                if (txt.contains(":") && txt.first().isDigit()) {
                    durationText = txt
                    break
                }
            }
        }

        if (durationText.isBlank()) {
            val fixedCols = renderer.optJSONArray("fixedColumns")
            val fixedTxt = fixedCols?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
            if (!fixedTxt.isNullOrBlank() && fixedTxt.contains(":")) {
                durationText = fixedTxt
            }
        }

        val thumbnails = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbUrl = extractHighResThumb(thumbnails, videoId)

        return OnlineSong(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbUrl,
            durationMs = parseDurationMs(durationText),
            durationText = durationText
        )
    }

    private fun parseTwoRowSongItem(renderer: JSONObject): OnlineSong? {
        val navWatch = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        val overlayWatch = renderer.optJSONObject("thumbnailOverlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")

        val videoId = navWatch?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: overlayWatch?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: return null

        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() } ?: return null

        val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
        val artist = buildString {
            if (subtitleRuns != null) {
                for (i in 0 until subtitleRuns.length()) {
                    val t = subtitleRuns.optJSONObject(i)?.optString("text") ?: continue
                    if (t == " • " || t.equals("Song", ignoreCase = true) || t.equals("Video", ignoreCase = true)) continue
                    if (isNotEmpty()) break
                    append(t)
                }
            }
        }.ifBlank { "YouTube Music" }

        val thumbnails = renderer.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbUrl = extractHighResThumb(thumbnails, videoId)

        return OnlineSong(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbUrl,
            durationMs = 0L
        )
    }

    private fun parsePanelVideoSongItem(renderer: JSONObject): OnlineSong? {
        val videoId = renderer.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() } ?: return null
        val artist = renderer.optJSONObject("shortBylineText")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?: renderer.optJSONObject("longBylineText")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
            ?: "Unknown Artist"
        val durationText = renderer.optJSONObject("lengthText")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: ""
        val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbUrl = extractHighResThumb(thumbnails, videoId)

        return OnlineSong(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbUrl,
            durationMs = parseDurationMs(durationText),
            durationText = durationText
        )
    }

    private fun parseTwoRowPlaylistItem(renderer: JSONObject): YouTubePlaylistSummary? {
        val browseId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.takeIf { it.isNotBlank() } ?: return null

        if (!browseId.startsWith("VL") && !browseId.startsWith("PL") && !browseId.startsWith("OLAK")) return null
        val playlistId = browseId.removePrefix("VL")

        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() } ?: return null

        val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
        val subtitle = buildString {
            if (subtitleRuns != null) {
                for (i in 0 until subtitleRuns.length()) {
                    append(subtitleRuns.optJSONObject(i)?.optString("text") ?: "")
                }
            }
        }

        val thumbnails = renderer.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbUrl = extractHighResThumb(thumbnails, "")

        return YouTubePlaylistSummary(
            playlistId = playlistId,
            title = title,
            subtitle = subtitle.ifBlank { null },
            thumbnailUrl = thumbUrl.ifBlank { null },
            trackCount = 0
        )
    }

    private fun parseResponsivePlaylistItem(renderer: JSONObject): YouTubePlaylistSummary? {
        val browseId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.takeIf { it.isNotBlank() } ?: return null

        if (!browseId.startsWith("VL") && !browseId.startsWith("PL")) return null
        val playlistId = browseId.removePrefix("VL")

        val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
        val title = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() } ?: return null

        val thumbnails = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbUrl = extractHighResThumb(thumbnails, "")

        return YouTubePlaylistSummary(
            playlistId = playlistId,
            title = title,
            subtitle = null,
            thumbnailUrl = thumbUrl.ifBlank { null },
            trackCount = 0
        )
    }

    private fun parseNextPanelTracks(root: JSONObject, excludeVideoId: String?): List<OnlineSong> {
        val out = mutableListOf<OnlineSong>()
        extractSongsRecursively(root, out, 40)
        return out.filter { it.videoId.isNotBlank() && it.videoId != excludeVideoId }.distinctBy { it.videoId }
    }

    private fun extractHighResThumb(thumbnails: JSONArray?, fallbackVideoId: String): String {
        val raw = if (thumbnails != null && thumbnails.length() > 0) {
            thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url") ?: ""
        } else ""
        return when {
            raw.contains("googleusercontent.com") -> {
                val dimRegex = Regex("""=w\d+-h\d+[^=]*$""")
                if (dimRegex.containsMatchIn(raw)) {
                    raw.replace(dimRegex, "=w800-h800-l90-rj")
                } else {
                    "$raw=w800-h800-l90-rj"
                }
            }
            raw.isNotBlank() -> raw.replace(Regex("""w\d+-h\d+"""), "w800-h800")
            fallbackVideoId.length == 11 -> "https://img.youtube.com/vi/$fallbackVideoId/hqdefault.jpg"
            else -> ""
        }
    }

    private fun parseDurationMs(durationText: String): Long {
        if (durationText.isBlank()) return 0L
        return try {
            val parts = durationText.trim().split(":").map { it.trim().toLong() }
            when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                2 -> (parts[0] * 60 + parts[1]) * 1000L
                1 -> parts[0] * 1000L
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun buildClientContextJson(): JSONObject {
        val locale = Locale.getDefault()
        val lang = locale.language.ifBlank { "en" }
        val country = locale.country.ifBlank { "IN" }
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", enc?.clientWebName ?: "WEB_REMIX")
                    put("clientVersion", enc?.clientWebVersion ?: "1.20241111.01.00")
                    put("hl", lang)
                    put("gl", country)
                })
            })
        }
    }

    private fun innertubePost(
        endpoint: String,
        bodyJson: JSONObject,
        cookies: String?,
        sapisid: String?
    ): String {
        val client = okHttpClient ?: OkHttpClient()
        val reqBuilder = Request.Builder()
            .url("$BASE_URL/$endpoint?key=$API_KEY&prettyPrint=false")
            .post(bodyJson.toString().toRequestBody(MEDIA_TYPE_JSON))
            .addHeader("User-Agent", USER_AGENT_DESKTOP)
            .addHeader("Content-Type", "application/json")
            .addHeader("Origin", YTM_ORIGIN)
            .addHeader("X-Origin", YTM_ORIGIN)
            .addHeader("Referer", "$YTM_ORIGIN/")
            .addHeader("X-Goog-AuthUser", "0")
            .addHeader("X-Youtube-Client-Name", "67")
            .addHeader("X-Youtube-Client-Version", enc?.clientWebVersion ?: "1.20241111.01.00")

        if (!cookies.isNullOrBlank() && !sapisid.isNullOrBlank()) {
            reqBuilder.addHeader("Cookie", cookies)
            reqBuilder.addHeader("Authorization", buildSapisidHashHeader(sapisid, YTM_ORIGIN))
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("YouTube Innertube $endpoint HTTP ${response.code}")
            }
            return response.body?.string() ?: "{}"
        }
    }
}
