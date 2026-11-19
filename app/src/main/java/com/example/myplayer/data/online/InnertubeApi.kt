package com.example.myplayer.data.online

import android.util.Log
import com.example.myplayer.data.online.model.OnlineSong
import com.example.myplayer.security.StringEncryptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean

class YouTubeStreamBlockedException(message: String) : Exception(message)

/**
 * Implements the YouTube Music Innertube API protocol.
 * This is the same API used by NewPipe, yt-dlp, and music.youtube.com.
 * No API key is required — uses the public web client credentials.
 */
@Singleton
class InnertubeApi(
    private val okHttpClient: OkHttpClient,
    private val enc: StringEncryptionManager  // Phase 3: runtime key decryption
) {
    init {
        // Initialize NewPipe exactly once with the Hilt-injected OkHttpClient.
        // This MUST happen here (not in Application.onCreate) so that the same
        // singleton client is used by both InnertubeApi and NewPipe internals.
        if (newPipeInitialized.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloader(okHttpClient))
            Log.d(TAG, "NewPipe initialized with injected OkHttpClient")
        }
    }
    companion object {
        private const val TAG = "InnertubeApi"
        private val newPipeInitialized = AtomicBoolean(false)
        // BASE_URL and API_KEY are no longer hardcoded constants.
        // They are retrieved at runtime from StringEncryptionManager (Phase 2/3).
        private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Robust extractor for YouTube video IDs from URLs (youtube.com, youtu.be, music.youtube.com,
         * shorts, embed, live) or raw 11-char video IDs.
         */
        fun extractYouTubeVideoId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                return trimmed
            }
            val pattern = Regex(
                """(?:https?://)?(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?(?:.*&)?v=|v/|embed/|shorts/|live/)|youtu\.be/)([a-zA-Z0-9_-]{11})""",
                RegexOption.IGNORE_CASE
            )
            return pattern.find(trimmed)?.groupValues?.getOrNull(1)
        }
    }

    fun extractYouTubeVideoId(input: String): String? = Companion.extractYouTubeVideoId(input)

    // Lazy-resolved via enc to avoid plaintext in DEX string table
    private val BASE_URL: String by lazy { enc.baseUrl }
    private val API_KEY: String by lazy { enc.ytmApiKey }

    // YouTube Music web client context (Device Locale Auto-Detected - Zero Hardcoding)
    private val CLIENT_CONTEXT: String by lazy {
        val locale = java.util.Locale.getDefault()
        val lang = locale.language.ifBlank { "en" }
        val country = locale.country.ifBlank { "US" }
        JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", enc.clientWebName)
                    put("clientVersion", enc.clientWebVersion)
                    put("hl", lang)
                    put("gl", country)
                })
            })
        }.toString()
    }


    data class SearchPage(
        val songs: List<OnlineSong>,
        val continuationToken: String?
    )

    /**
     * Search YouTube Music for songs matching [query].
     * @param continuationToken Pass the previous page's token to load more results.
     * @param filtered When true (default), filters strictly for YouTube Music curated "Songs" catalog.
     *                 When false, searches YouTube Music top results without params filter (broadened fallback).
     */
    suspend fun search(
        query: String,
        continuationToken: String? = null,
        filtered: Boolean = true
    ): SearchPage = withContext(Dispatchers.IO) {
        val directVideoId = extractYouTubeVideoId(query)
        if (directVideoId != null && continuationToken == null) {
            return@withContext searchByVideoId(directVideoId)
        }
        try {
            val bodyJson = JSONObject(CLIENT_CONTEXT).apply {
                if (continuationToken != null) {
                    put("continuation", continuationToken)
                } else {
                    put("query", query)
                    if (filtered) {
                        put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D") // filter: songs only
                    }
                }
            }

            val request = Request.Builder()
                .url("$BASE_URL/search?key=$API_KEY&prettyPrint=false")
                .post(bodyJson.toString().toRequestBody(MEDIA_TYPE_JSON))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .build()

            val body = okHttpClient.newCall(request).execute().use { response ->
                response.body?.string()
            } ?: return@withContext SearchPage(emptyList(), null)

            parseSearchResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query' (filtered=$filtered)", e)
            SearchPage(emptyList(), null)
        }
    }

    /**
     * Retrieves related/radio recommendation tracks for a given YouTube video ID
     * using YouTube Music's 'next' endpoint.
     */
    suspend fun getUpNext(videoId: String): List<OnlineSong> = getNextRadioTracks(videoId)
    suspend fun getRecommendations(videoId: String): List<OnlineSong> = getUpNext(videoId)

    /**
     * Searches specifically by YouTube video ID or link.
     * Places the exact song at position 0, followed by matching radio tracks.
     */
    suspend fun searchByVideoId(videoId: String): SearchPage = withContext(Dispatchers.IO) {
        val cleanVideoId = extractYouTubeVideoId(videoId) ?: videoId.trim()
        if (cleanVideoId.isBlank()) return@withContext SearchPage(emptyList(), null)

        val foundSongs = mutableListOf<OnlineSong>()
        try {
            // 1. Fetch next queue with RDAMVM radio (seed video included when excludeVideoId == null)
            val tracks = fetchNextEndpoint(cleanVideoId, playlistId = "RDAMVM$cleanVideoId", excludeVideoId = null)
            val targetIndex = tracks.indexOfFirst { it.videoId == cleanVideoId }
            if (targetIndex >= 0) {
                foundSongs.add(tracks[targetIndex])
                foundSongs.addAll(tracks.filterIndexed { index, _ -> index != targetIndex })
            } else {
                val directSong = getSongByVideoId(cleanVideoId)
                if (directSong != null) {
                    foundSongs.add(directSong)
                }
                foundSongs.addAll(tracks)
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchByVideoId radio fetch failed for $cleanVideoId", e)
        }

        // 2. Fallback: if radio returned empty, resolve directly via oEmbed / NewPipe
        if (foundSongs.isEmpty()) {
            val directSong = getSongByVideoId(cleanVideoId)
            if (directSong != null) {
                foundSongs.add(directSong)
            }
        }

        if (foundSongs.isNotEmpty()) {
            prefetchVisibleStreams(foundSongs.take(3).map { it.videoId })
        }

        SearchPage(foundSongs, null)
    }

    /**
     * Resolves metadata for a single YouTube track using official oEmbed (<50ms)
     * with NewPipe extractor fallback.
     */
    suspend fun getSongByVideoId(videoId: String): OnlineSong? = withContext(Dispatchers.IO) {
        val cleanId = extractYouTubeVideoId(videoId) ?: videoId.trim()
        if (cleanId.length != 11) return@withContext null

        // 1. YouTube official oEmbed (<50ms, open endpoint)
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$cleanId&format=json"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    if (!json.isNullOrBlank()) {
                        val obj = JSONObject(json)
                        val rawTitle = obj.optString("title", "").ifBlank { "YouTube Track" }
                        val author = obj.optString("author_name", "").ifBlank { "YouTube" }
                        val thumb = obj.optString("thumbnail_url", "").ifBlank { "https://img.youtube.com/vi/$cleanId/hqdefault.jpg" }
                        return@withContext OnlineSong(
                            videoId = cleanId,
                            title = rawTitle,
                            artist = author,
                            thumbnailUrl = thumb,
                            durationMs = 0L
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "oEmbed failed for $cleanId", e)
        }

        // 2. Fallback to NewPipe extractor
        try {
            val url = "https://www.youtube.com/watch?v=$cleanId"
            val extractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor(url)
            extractor.fetchPage()
            val title = extractor.name?.ifBlank { "YouTube Track" } ?: "YouTube Track"
            val artist = extractor.uploaderName?.ifBlank { "YouTube" } ?: "YouTube"
            val durationMs = extractor.length * 1000L
            val thumb = extractor.thumbnails?.lastOrNull()?.url ?: "https://img.youtube.com/vi/$cleanId/hqdefault.jpg"
            return@withContext OnlineSong(
                videoId = cleanId,
                title = title,
                artist = artist,
                thumbnailUrl = thumb,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe extractor failed for $cleanId", e)
        }

        // 3. Last fallback: minimal OnlineSong with thumbnail
        OnlineSong(
            videoId = cleanId,
            title = "YouTube Track ($cleanId)",
            artist = "YouTube",
            thumbnailUrl = "https://img.youtube.com/vi/$cleanId/hqdefault.jpg",
            durationMs = 0L
        )
    }

    suspend fun getNextRadioTracks(videoId: String): List<OnlineSong> = withContext(Dispatchers.IO) {
        val cleanVideoId = videoId.removePrefix("online://").trim()
        if (cleanVideoId.isBlank()) return@withContext emptyList()

        try {
            Log.d(TAG, "YOUTUBE_RADIO_REQUEST: Requesting radio tracks for videoId: $cleanVideoId")
            // 1. Try with radio playlist ID RDAMVM{videoId}
            val radioTracks = fetchNextEndpoint(cleanVideoId, playlistId = "RDAMVM$cleanVideoId", excludeVideoId = cleanVideoId)
            if (radioTracks.isNotEmpty()) {
                Log.d(TAG, "YOUTUBE_RADIO_RESULT: Found ${radioTracks.size} radio tracks with RDAMVM for $cleanVideoId")
                return@withContext radioTracks
            }

            // 2. Fallback without playlist ID (direct watch next)
            Log.d(TAG, "RDAMVM radio returned empty, attempting direct next for $cleanVideoId")
            val nextTracks = fetchNextEndpoint(cleanVideoId, playlistId = null, excludeVideoId = cleanVideoId)
            Log.d(TAG, "YOUTUBE_RADIO_RESULT: Found ${nextTracks.size} tracks from direct next for $cleanVideoId")
            nextTracks
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get radio tracks for $cleanVideoId", e)
            emptyList()
        }
    }

    private fun fetchNextEndpoint(videoId: String, playlistId: String?, excludeVideoId: String? = videoId): List<OnlineSong> {
        try {
            val bodyJson = JSONObject(CLIENT_CONTEXT).apply {
                put("videoId", videoId)
                if (!playlistId.isNullOrBlank()) {
                    put("playlistId", playlistId)
                }
                put("isAudioOnly", true)
            }

            val request = Request.Builder()
                .url("$BASE_URL/next?key=$API_KEY&prettyPrint=false")
                .post(bodyJson.toString().toRequestBody(MEDIA_TYPE_JSON))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .build()

            val body = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Next endpoint HTTP error ${response.code} for $videoId")
                    return emptyList()
                }
                response.body?.string()
            } ?: return emptyList()
            return parseNextResponse(body, excludeVideoId = excludeVideoId)
        } catch (e: Exception) {
            Log.w(TAG, "fetchNextEndpoint failed for $videoId (playlistId: $playlistId)", e)
            return emptyList()
        }
    }

    private fun parseNextResponse(json: String, excludeVideoId: String?): List<OnlineSong> {
        val songs = mutableListOf<OnlineSong>()
        try {
            val root = JSONObject(json)
            val tabs = root
                .optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs") ?: return emptyList()

            for (i in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
                val panel = tab.optJSONObject("content")
                    ?.optJSONObject("musicQueueRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("playlistPanelRenderer") ?: continue

                val contents = panel.optJSONArray("contents") ?: continue

                for (j in 0 until contents.length()) {
                    val item = contents.optJSONObject(j) ?: continue
                    val renderer = item.optJSONObject("playlistPanelVideoRenderer") ?: continue

                    val videoId = renderer.optString("videoId")
                    if (videoId.isBlank() || (!excludeVideoId.isNullOrBlank() && videoId == excludeVideoId)) continue

                    val title = renderer.optJSONObject("title")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text") ?: ""
                    if (title.isBlank()) continue

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
                    val durationMs = parseDurationToMs(durationText)

                    val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val rawThumbnailUrl = if (thumbnails != null && thumbnails.length() > 0) {
                        thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url") ?: ""
                    } else ""

                    val highResThumbnailUrl = formatThumbnailUrl(rawThumbnailUrl)

                    songs.add(
                        OnlineSong(
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            thumbnailUrl = highResThumbnailUrl,
                            durationMs = durationMs,
                            durationText = durationText
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseNextResponse failed", e)
        }
        return songs
    }

    private fun formatThumbnailUrl(rawUrl: String): String {
        return when {
            rawUrl.contains("googleusercontent.com") -> {
                val googleDimRegex = Regex("""=w\d+-h\d+[^=]*$""")
                if (googleDimRegex.containsMatchIn(rawUrl)) {
                    rawUrl.replace(googleDimRegex, "=w800-h800-l90-rj")
                } else if (rawUrl.contains("=")) {
                    rawUrl.substringBeforeLast("=") + "=w800-h800-l90-rj"
                } else {
                    "$rawUrl=w800-h800-l90-rj"
                }
            }
            rawUrl.isNotBlank() -> {
                rawUrl.replace(Regex("""w\d+-h\d+"""), "w800-h800")
            }
            else -> ""
        }
    }

    private fun parseSearchResponse(json: String): SearchPage {
        val songs = mutableListOf<OnlineSong>()
        var continuationToken: String? = null

        try {
            val root = JSONObject(json)

            // Try to extract continuation token
            continuationToken = root
                .optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
                ?.optJSONArray("continuations")
                ?.optJSONObject(0)
                ?.optJSONObject("nextContinuationData")
                ?.optString("continuation")
                ?: root
                    .optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("continuations")
                    ?.optJSONObject(0)
                    ?.optJSONObject("nextContinuationData")
                    ?.optString("continuation")

            // Find musicShelfRenderer contents
            val contents = findMusicShelfContents(root) ?: return SearchPage(songs, null)

            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue
                val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                val song = parseMusicItem(renderer) ?: continue
                songs.add(song)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed", e)
        }

        return SearchPage(songs, continuationToken?.takeIf { it.isNotBlank() })
    }

    private fun findMusicShelfContents(root: JSONObject): JSONArray? {
        // Normal search response path
        val tabs = root
            .optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")

        if (tabs != null) {
            for (i in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
                val sections = tab.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (j in 0 until sections.length()) {
                    val shelf = sections.optJSONObject(j)
                        ?.optJSONObject("musicShelfRenderer") ?: continue
                    val shelfContents = shelf.optJSONArray("contents")
                    if (shelfContents != null && shelfContents.length() > 0) {
                        return shelfContents
                    }
                }
            }
        }

        // Continuation response path
        return root
            .optJSONObject("continuationContents")
            ?.optJSONObject("musicShelfContinuation")
            ?.optJSONArray("contents")
    }

    private fun parseMusicItem(renderer: JSONObject): OnlineSong? {
        return try {
            // Extract video ID from navigation endpoint
            val videoId = renderer
                .optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?: renderer
                    .optJSONObject("flexColumns")
                    ?.let { null } // try from flex columns below
                ?: return null

            if (videoId.isBlank()) return null

            // Extract title and artist from flexColumns
            val flexColumns = renderer.optJSONArray("flexColumns") ?: return null

            val title = flexColumns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text") ?: return null

            // Artist is usually in the second flex column
            val subtitleRuns = flexColumns.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")

            val artist = subtitleRuns?.optJSONObject(0)?.optString("text") ?: "Unknown Artist"
            val durationText = subtitleRuns?.let { runs ->
                // Duration is usually the last run
                var d = ""
                for (k in runs.length() - 1 downTo 0) {
                    val text = runs.optJSONObject(k)?.optString("text") ?: continue
                    if (text.contains(":")) { d = text; break }
                }
                d
            } ?: ""

            val durationMs = parseDurationToMs(durationText)

            // Get highest quality thumbnail
            val thumbnails = renderer
                .optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")

            val rawThumbnailUrl = if (thumbnails != null && thumbnails.length() > 0) {
                thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url") ?: ""
            } else ""

            val highResThumbnailUrl = when {
                rawThumbnailUrl.contains("googleusercontent.com") -> {
                    val googleDimRegex = Regex("""=w\d+-h\d+[^=]*$""")
                    if (googleDimRegex.containsMatchIn(rawThumbnailUrl)) {
                        rawThumbnailUrl.replace(googleDimRegex, "=w800-h800-l90-rj")
                    } else if (rawThumbnailUrl.contains("=")) {
                        rawThumbnailUrl.substringBeforeLast("=") + "=w800-h800-l90-rj"
                    } else {
                        "$rawThumbnailUrl=w800-h800-l90-rj"
                    }
                }
                rawThumbnailUrl.isNotBlank() -> {
                    rawThumbnailUrl.replace(Regex("""w\d+-h\d+"""), "w800-h800")
                }
                else -> ""
            }

            OnlineSong(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = highResThumbnailUrl,
                durationMs = durationMs,
                durationText = durationText
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse music item", e)
            null
        }
    }

    data class CachedStream(
        val url: String,
        val expiryTimeMs: Long
    )

    private val streamUrlCache = java.util.concurrent.ConcurrentHashMap<String, CachedStream>()
    private val inFlightResolutions = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    private val extractionScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val prefetchJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val prefetchSemaphore = Semaphore(3)
    private val CACHE_TTL_MS = 2 * 60 * 60 * 1000L // 2 hours

    /**
     * Prefetches audio stream URLs for visible song list items (capped at 3 concurrent extractions).
     * Automatically cancels in-flight prefetches for items that have scrolled off-screen.
     * Reuses existing cache, TTL, and single-flight resolution in getStreamUrl().
     */
    fun prefetchVisibleStreams(videoIds: List<String>) {
        val cleanIds = videoIds
            .map { it.removePrefix("online://").trim() }
            .filter { it.isNotBlank() && !it.startsWith("http") }
            .distinct()
            .take(5)

        // Cancel prefetches for songs that scrolled off-screen
        val toCancel = prefetchJobs.keys - cleanIds.toSet()
        for (offScreenId in toCancel) {
            prefetchJobs.remove(offScreenId)?.cancel()
        }

        // Trigger prefetch for visible items not yet cached or running
        for (videoId in cleanIds) {
            if (getCachedStreamUrl(videoId) != null) continue
            if (prefetchJobs[videoId]?.isActive == true) continue

            val job = extractionScope.launch {
                try {
                    prefetchSemaphore.withPermit {
                        if (getCachedStreamUrl(videoId) == null) {
                            Log.d(TAG, "Prefetching stream for visible item: $videoId")
                            getStreamUrl(videoId, forceRefresh = false)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore cancellations or transient prefetch failures
                } finally {
                    prefetchJobs.remove(videoId)
                }
            }
            prefetchJobs[videoId] = job
        }
    }

    fun cancelAllPrefetches() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
    }

    /**
     * Returns the cached stream URL if available and not expired.
     */
    fun getCachedStreamUrl(videoId: String): String? {
        val cached = streamUrlCache[videoId] ?: return null
        if (System.currentTimeMillis() < cached.expiryTimeMs) {
            return cached.url
        }
        streamUrlCache.remove(videoId)
        return null
    }

    /**
     * Caches a stream URL for a given video ID with standard TTL.
     */
    fun cacheStreamUrl(videoId: String, url: String) {
        if (videoId.isNotBlank() && url.isNotBlank()) {
            streamUrlCache[videoId] = CachedStream(url, System.currentTimeMillis() + CACHE_TTL_MS)
        }
    }

    fun findVideoIdForUrl(url: String): String? =
        streamUrlCache.entries.firstOrNull { it.value.url == url }?.key

    /**
     * Invalidates any cached stream URL for the given video ID.
     */
    fun invalidateCachedStreamUrl(videoId: String) {
        if (videoId.isNotBlank()) {
            streamUrlCache.remove(videoId)
            Log.d(TAG, "invalidateCachedStreamUrl: Invalidated cache for $videoId")
        }
    }

    /**
     * Resolves the actual audio stream URL for a given YouTube Music video ID.
     * Uses NewPipeExtractor to bypass bot detection and poToken issues.
     * Employs single-flight deduplication: multiple concurrent requests for the same
     * videoId share a single extraction task.
     */
    suspend fun getStreamUrl(
        videoId: String,
        targetQuality: com.example.myplayer.data.preferences.StreamQuality = com.example.myplayer.data.preferences.StreamQuality.HIGH,
        forceRefresh: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        if (forceRefresh) {
            invalidateCachedStreamUrl(videoId)
        } else {
            getCachedStreamUrl(videoId)?.let { cachedUrl ->
                Log.i(TAG, "getStreamUrl: Returning cached stream URL for $videoId (<1ms)")
                return@withContext cachedUrl
            }
        }

        // Single-flight deduplication: Join existing in-flight extraction if one is running
        val existingDeferred = inFlightResolutions[videoId]
        if (existingDeferred != null) {
            Log.d(TAG, "getStreamUrl: Joining in-flight resolution for $videoId")
            return@withContext try {
                existingDeferred.await()
            } catch (e: Exception) {
                null
            }
        }

        val deferred = extractionScope.async {
            try {
                resolveStreamUrlInternal(videoId, targetQuality)
            } finally {
                inFlightResolutions.remove(videoId)
            }
        }

        val winner = inFlightResolutions.putIfAbsent(videoId, deferred)
        if (winner != null) {
            deferred.cancel()
        }
        val targetDeferred = winner ?: deferred

        try {
            targetDeferred.await()
        } catch (e: Exception) {
            Log.e(TAG, "getStreamUrl failed for $videoId", e)
            null
        }
    }

    private suspend fun resolveStreamUrlInternal(
        videoId: String,
        targetQuality: com.example.myplayer.data.preferences.StreamQuality = com.example.myplayer.data.preferences.StreamQuality.HIGH
    ): String? {
        return kotlinx.coroutines.withTimeoutOrNull(35_000L) { // 35s timeout for slow mobile data
            try {
                Log.i(TAG, "getStreamUrl: Starting resolution for $videoId (quality=${targetQuality.name})")
                val url = "https://www.youtube.com/watch?v=$videoId"
                val extractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()

                val audioStreams = extractor.audioStreams
                if (audioStreams.isEmpty()) {
                    throw YouTubeStreamBlockedException("YouTube stream extraction failed: No audio streams found.")
                }

                // Prefer WebM/Opus streams because they have smaller container headers and start faster on 9s chunks
                val opusStreams = audioStreams.filter {
                    it.getCodec()?.contains("opus", ignoreCase = true) == true ||
                        it.getFormat()?.name?.contains("WEB", ignoreCase = true) == true
                }.ifEmpty { audioStreams }

                val optimalStream = when (targetQuality) {
                    com.example.myplayer.data.preferences.StreamQuality.LOW -> {
                        opusStreams.minByOrNull { it.getBitrate() }
                            ?: audioStreams.minByOrNull { it.getBitrate() }
                    }
                    com.example.myplayer.data.preferences.StreamQuality.MEDIUM -> {
                        opusStreams.minByOrNull { kotlin.math.abs(it.getBitrate() - 70_000) }
                            ?: opusStreams.minByOrNull { kotlin.math.abs(it.getBitrate() - 96_000) }
                            ?: opusStreams.minByOrNull { it.getBitrate() }
                    }
                    com.example.myplayer.data.preferences.StreamQuality.HIGH -> {
                        opusStreams.maxByOrNull { it.getBitrate() }
                            ?: audioStreams.maxByOrNull { it.getBitrate() }
                    }
                }

                val contentUrl = optimalStream?.getContent()
                if (!contentUrl.isNullOrBlank()) {
                    cacheStreamUrl(videoId, contentUrl)
                }
                contentUrl
            } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                Log.e(TAG, "NewPipe Extraction failed", e)
                throw YouTubeStreamBlockedException("YouTube blocked this stream: ${e.message}")
            } catch (e: YouTubeStreamBlockedException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "getStreamUrl failed for $videoId", e)
                null
            }
        }
    }

    private fun parseDurationToMs(durationText: String): Long {
        if (durationText.isBlank()) return 0L
        return try {
            val parts = durationText.split(":").map { it.toLong() }
            when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                1 -> parts[0] * 1000
                else -> 0L
            }
        } catch (e: Exception) { 0L }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OPENTUNE EXPLORE: MOODS & GENRES BROWSE, MULTI-SECTION SHELVES & PLAYLISTS
    // ═════════════════════════════════════════════════════════════════════════

    data class ExploreMoodGenre(
        val title: String,
        val subtitle: String = "",
        val browseId: String = "",
        val params: String? = null,
        val isGenre: Boolean = false,
        val coverUrl: String? = null
    )

    data class ExploreShelfItem(
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String,
        val browseId: String? = null,
        val playlistId: String? = null,
        val videoId: String? = null,
        val isExplicit: Boolean = false,
        val seedQuery: String? = null,
        val preloadedSongs: List<OnlineSong> = emptyList()
    ) {
        fun toOnlineSongOrNull(): OnlineSong? {
            val vid = videoId?.takeIf { it.isNotBlank() } ?: return null
            return OnlineSong(
                videoId = vid,
                title = title,
                artist = subtitle.substringBefore("•").trim().ifBlank { "YouTube Music" },
                thumbnailUrl = thumbnailUrl,
                durationMs = 0L
            )
        }
    }

    data class ExploreGenreShelf(
        val title: String,
        val subtitle: String = "",
        val items: List<ExploreShelfItem>
    )

    data class ExploreCollectionPage(
        val browseId: String,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String,
        val songs: List<OnlineSong>
    )

    private val moodsAndGenresCache = java.util.concurrent.CopyOnWriteArrayList<ExploreMoodGenre>()
    private val genreShelvesCache = java.util.concurrent.ConcurrentHashMap<String, List<ExploreGenreShelf>>()
    private val collectionPageCache = java.util.concurrent.ConcurrentHashMap<String, ExploreCollectionPage>()
    private val moodCoverCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun buildGenreTasteCacheKey(
        genreTitle: String,
        preferredLanguages: Set<String> = emptySet(),
        preferredGenres: Set<String> = emptySet(),
        preferredArtists: Set<String> = emptySet()
    ): String {
        val base = genreTitle.trim().lowercase()
        if (preferredLanguages.isEmpty() && preferredGenres.isEmpty() && preferredArtists.isEmpty()) {
            return "${base}__in_mix"
        }
        val langKey = preferredLanguages.map { it.trim().lowercase() }.sorted().joinToString(",")
        val genreKey = preferredGenres.map { it.trim().lowercase() }.sorted().joinToString(",")
        val artistKey = preferredArtists.map { it.trim().lowercase() }.sorted().take(4).joinToString(",")
        return "${base}__l=${langKey}__g=${genreKey}__a=${artistKey}"
    }

    fun getCachedGenreShelves(
        genreTitle: String,
        preferredLanguages: Set<String> = emptySet(),
        preferredGenres: Set<String> = emptySet(),
        preferredArtists: Set<String> = emptySet()
    ): List<ExploreGenreShelf>? =
        genreShelvesCache[buildGenreTasteCacheKey(genreTitle, preferredLanguages, preferredGenres, preferredArtists)]

    fun getCachedCollectionPage(item: ExploreShelfItem): ExploreCollectionPage? {
        val key = item.browseId?.takeIf { it.isNotBlank() }
            ?: item.playlistId?.takeIf { it.isNotBlank() }
            ?: item.seedQuery?.takeIf { it.isNotBlank() }
            ?: item.title.lowercase()
        return collectionPageCache[key]
    }

    /**
     * Calls YouTube Music's `browse` endpoint (`https://music.youtube.com/youtubei/v1/browse`).
     */
    private fun postBrowseRequest(browseId: String, params: String? = null): String? {
        return try {
            val bodyJson = JSONObject(CLIENT_CONTEXT).apply {
                put("browseId", browseId)
                if (!params.isNullOrBlank()) {
                    put("params", params)
                }
            }
            val request = Request.Builder()
                .url("$BASE_URL/browse?key=$API_KEY&prettyPrint=false")
                .post(bodyJson.toString().toRequestBody(MEDIA_TYPE_JSON))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "postBrowseRequest failed for browseId=$browseId", e)
            null
        }
    }

    /**
     * Fetches YouTube Music's `FEmusic_moods_and_genres` navigation grid (like OpenTune's `moodsAndGenres()`).
     */
    suspend fun fetchMoodsAndGenres(): List<ExploreMoodGenre> = withContext(Dispatchers.IO) {
        if (moodsAndGenresCache.isNotEmpty()) return@withContext moodsAndGenresCache.toList()
        val parsed = mutableListOf<ExploreMoodGenre>()
        try {
            val body = postBrowseRequest("FEmusic_moods_and_genres")
            if (!body.isNullOrBlank()) {
                val root = JSONObject(body)
                val sections = root.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                if (sections != null) {
                    for (i in 0 until sections.length()) {
                        val grid = sections.optJSONObject(i)?.optJSONObject("gridRenderer") ?: continue
                        val secTitle = extractRunsText(grid.optJSONObject("header")?.optJSONObject("gridHeaderRenderer")?.optJSONObject("title"))
                        val isGenreSec = secTitle.contains("genre", ignoreCase = true)
                        val items = grid.optJSONArray("items") ?: continue
                        for (j in 0 until items.length()) {
                            val btn = items.optJSONObject(j)?.optJSONObject("musicNavigationButtonRenderer") ?: continue
                            val label = extractRunsText(btn.optJSONObject("buttonText")).trim()
                            val endpoint = btn.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint")
                                ?: btn.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                            val bId = endpoint?.optString("browseId").orEmpty()
                            val prm = endpoint?.optString("params")?.takeIf { it.isNotBlank() }
                            if (label.isNotBlank() && bId.isNotBlank()) {
                                parsed.add(
                                    ExploreMoodGenre(
                                        title = label,
                                        subtitle = if (isGenreSec) "Genre • YouTube Music" else "Mood • YouTube Music",
                                        browseId = bId,
                                        params = prm,
                                        isGenre = isGenreSec
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchMoodsAndGenres failed", e)
        }
        if (parsed.isNotEmpty()) {
            moodsAndGenresCache.clear()
            moodsAndGenresCache.addAll(parsed.distinctBy { "${it.title.lowercase()}_${it.params}" })
        }
        moodsAndGenresCache.toList()
    }

    /**
     * Fetches multi-section horizontal playlist/album carousels for a Mood or Genre
     * (matching OpenTune's `MoodScreen` / `MusicRepository.shelves(browseId, params)`).
     */
    suspend fun fetchGenreOrMoodShelves(
        genreTitle: String,
        browseId: String? = null,
        params: String? = null,
        forceRefresh: Boolean = false,
        preferredLanguages: Set<String> = emptySet(),
        preferredGenres: Set<String> = emptySet(),
        preferredArtists: Set<String> = emptySet()
    ): List<ExploreGenreShelf> = withContext(Dispatchers.IO) {
        val cacheKey = buildGenreTasteCacheKey(genreTitle, preferredLanguages, preferredGenres, preferredArtists)
        val coverKey = genreTitle.trim().lowercase()
        if (!forceRefresh) {
            genreShelvesCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }

        // Run native YouTube Music browse AND personalized Bollywood/Indian + User Taste shelves in parallel
        val (globalShelves, personalizedShelves) = kotlinx.coroutines.coroutineScope {
            val nativeDeferred = async {
                val out = mutableListOf<ExploreGenreShelf>()
                var resolvedBrowseId = browseId?.takeIf { it.isNotBlank() }
                var resolvedParams = params?.takeIf { it.isNotBlank() }
                if (resolvedBrowseId == null || resolvedParams == null) {
                    val allMoods = fetchMoodsAndGenres()
                    val matched = allMoods.firstOrNull { it.title.equals(genreTitle.trim(), ignoreCase = true) }
                        ?: allMoods.firstOrNull {
                            it.title.contains(genreTitle.trim(), ignoreCase = true) ||
                                genreTitle.trim().contains(it.title, ignoreCase = true)
                        }
                    if (matched != null) {
                        resolvedBrowseId = matched.browseId
                        resolvedParams = matched.params
                    }
                }
                if (!resolvedBrowseId.isNullOrBlank()) {
                    try {
                        val raw = postBrowseRequest(resolvedBrowseId, resolvedParams)
                        if (!raw.isNullOrBlank()) {
                            val parsed = parseBrowseShelves(JSONObject(raw))
                            if (parsed.isNotEmpty()) {
                                out.addAll(parsed)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Native mood browse failed for $genreTitle", e)
                    }
                }
                if (out.isEmpty()) {
                    val sectionSpecs = getCuratedSectionSpecsForGenre(genreTitle)
                    val built = kotlinx.coroutines.coroutineScope {
                        sectionSpecs.take(2).map { (sectionTitle, queryList) ->
                            async { buildCuratedPlaylistShelf(genreTitle, sectionTitle, queryList) }
                        }.mapNotNull { it.await() }
                    }
                    out.addAll(built)
                }
                out
            }

            val tasteAndIndianDeferred = async {
                buildPersonalizedAndIndianShelvesForGenre(
                    genreTitle = genreTitle,
                    preferredLanguages = preferredLanguages,
                    preferredGenres = preferredGenres,
                    preferredArtists = preferredArtists
                )
            }

            nativeDeferred.await() to tasteAndIndianDeferred.await()
        }

        // Interleave 50% User Taste & Bollywood/Indian shelves with 50% Global/YouTube Music shelves
        val merged = mutableListOf<ExploreGenreShelf>()
        val seenTitles = HashSet<String>()
        val maxLen = maxOf(personalizedShelves.size, globalShelves.size)
        for (i in 0 until maxLen) {
            personalizedShelves.getOrNull(i)?.let { shelf ->
                if (shelf.items.isNotEmpty() && seenTitles.add(shelf.title.lowercase())) {
                    merged.add(shelf)
                }
            }
            globalShelves.getOrNull(i)?.let { shelf ->
                if (shelf.items.isNotEmpty() && seenTitles.add(shelf.title.lowercase())) {
                    merged.add(shelf)
                }
            }
        }

        // Cache first cover for mood tile
        merged.firstNotNullOfOrNull { s -> s.items.firstNotNullOfOrNull { it.thumbnailUrl.takeIf { u -> u.isNotBlank() } } }
            ?.let { moodCoverCache[coverKey] = it }

        if (merged.isNotEmpty()) {
            genreShelvesCache[cacheKey] = merged
        }
        merged
    }

    /**
     * Builds Bollywood/Indian + 50% User Music Taste shelves for any Mood/Genre (e.g., Chill, Workout, Commute)
     * so opening a genre never shows only Hollywood/English playlists.
     */
    private suspend fun buildPersonalizedAndIndianShelvesForGenre(
        genreTitle: String,
        preferredLanguages: Set<String>,
        preferredGenres: Set<String>,
        preferredArtists: Set<String>
    ): List<ExploreGenreShelf> = kotlinx.coroutines.coroutineScope {
        val cleanGenre = genreTitle.trim()
        val lower = cleanGenre.lowercase()

        // 1. User Music Taste shelf specs (uses user's selected languages, artists & genres)
        val userLangs = preferredLanguages.map { it.trim() }.filter { it.isNotBlank() }
        val userArtists = preferredArtists.map { it.trim() }.filter { it.isNotBlank() }
        val userGenres = preferredGenres.map { it.trim() }.filter { it.isNotBlank() }

        val tasteSpecs = mutableListOf<Pair<String, String>>()
        userArtists.take(2).forEach { artist ->
            tasteSpecs.add("$artist • $cleanGenre" to "$artist $cleanGenre songs")
        }
        userLangs.take(2).forEach { lang ->
            tasteSpecs.add("$lang $cleanGenre Mix" to "$lang $cleanGenre hits songs")
        }
        userGenres.firstOrNull { !it.equals(cleanGenre, ignoreCase = true) }?.let { favGenre ->
            val langPrefix = userLangs.firstOrNull() ?: "Hindi"
            tasteSpecs.add("$favGenre x $cleanGenre" to "$langPrefix $favGenre $cleanGenre songs")
        }
        // Ensure at least 3 rich cards in the Taste shelf even if user hasn't picked many preferences yet
        if (tasteSpecs.size < 3) {
            val primaryLang = userLangs.firstOrNull() ?: "Bollywood Hindi"
            tasteSpecs.add("$primaryLang $cleanGenre Picks" to "$primaryLang $cleanGenre playlist songs")
        }
        if (tasteSpecs.size < 4) {
            tasteSpecs.add("Desi $cleanGenre Essentials" to "Indian $cleanGenre songs mix")
        }

        // 2. Bollywood & Indian shelf specs tailored to the exact mood/genre
        val bollywoodSpecs: List<Pair<String, String>> = when {
            "workout" in lower || "gym" in lower -> listOf(
                "Bollywood Gym Beast" to "Bollywood workout gym high energy hindi songs",
                "Punjabi Pump & Power" to "Punjabi gym workout motivation songs",
                "Desi Cardio & Running" to "Hindi fast beat running workout songs",
                "Indian Bass & Phonk" to "Indian hip hop rap workout gym songs"
            )
            "chill" in lower || "lo-fi" in lower || "lofi" in lower -> listOf(
                "Bollywood Lo-Fi Chill" to "Bollywood lofi chill hindi songs",
                "Hindi Acoustic Unplugged" to "Bollywood acoustic unplugged chill songs",
                "Indian Late Night Vibe" to "Hindi late night chill relaxing songs",
                "Indie India Chill" to "Indian indie chill peaceful songs"
            )
            "commute" in lower || "drive" in lower -> listOf(
                "Bollywood Long Drive" to "Bollywood long drive road trip hindi songs",
                "Punjabi Highway Vibes" to "Punjabi car drive bass songs",
                "Hindi Morning Commute" to "Hindi feel good morning drive songs",
                "Desi Night Drive" to "Bollywood night drive lofi songs"
            )
            "energize" in lower || "party" in lower -> listOf(
                "Bollywood Party Anthems" to "Bollywood dance party hit songs",
                "Punjabi Club Bangers" to "Punjabi party dance club songs",
                "Desi High Energy" to "Hindi upbeat energetic feel good songs",
                "Indian EDM & Remixes" to "Bollywood dj remix party songs"
            )
            "romance" in lower || "love" in lower -> listOf(
                "Bollywood Love Classics" to "Bollywood romantic love hindi songs",
                "Arijit & Romantic Hits" to "Arijit Singh romantic hindi songs",
                "Punjabi Romantic Vibes" to "Punjabi romantic love songs",
                "Indie Indian Romance" to "Indian indie love acoustic songs"
            )
            "sad" in lower -> listOf(
                "Bollywood Heartbreak" to "Bollywood sad emotional hindi songs",
                "Hindi Sad Lo-Fi" to "Hindi sad lofi slow reverb songs",
                "Punjabi Sad Ballads" to "Punjabi sad emotional songs",
                "Soulful Indian Sad" to "Arijit Singh Jubin Nautiyal sad songs"
            )
            "focus" in lower || "study" in lower || "sleep" in lower -> listOf(
                "Bollywood Instrumental" to "Bollywood piano guitar instrumental peaceful",
                "Hindi Soft Lo-Fi Study" to "Hindi soft lofi study focus beats",
                "Indian Flute & Ambient" to "Indian flute bansuri relaxing peaceful music",
                "Calm Bollywood Unplugged" to "Bollywood calm soothing acoustic songs"
            )
            else -> listOf(
                "Bollywood $cleanGenre" to "Bollywood Hindi $cleanGenre hit songs",
                "Punjabi $cleanGenre" to "Punjabi $cleanGenre trending songs",
                "Indian Indie $cleanGenre" to "Indian indie $cleanGenre songs",
                "Desi $cleanGenre Mix" to "Hindi Indian $cleanGenre playlist songs"
            )
        }

        val tasteLabel = if (userLangs.isNotEmpty() || userArtists.isNotEmpty() || userGenres.isNotEmpty()) {
            "Matched to Your Taste • $cleanGenre"
        } else {
            "For You • Indian $cleanGenre"
        }

        val shelf1Deferred = async {
            buildCuratedPlaylistShelf(cleanGenre, tasteLabel, tasteSpecs.distinctBy { it.first }.take(4))
        }
        val shelf2Deferred = async {
            buildCuratedPlaylistShelf(cleanGenre, "Bollywood & Indian $cleanGenre", bollywoodSpecs)
        }

        listOfNotNull(shelf1Deferred.await(), shelf2Deferred.await())
    }

    /**
     * Lazily loads the first playlist cover art for a mood/genre tile on the Explore board
     * (matching OpenTune's `MoodCovers.load(mood)`).
     */
    suspend fun fetchFirstCoverForMood(
        genreTitle: String,
        browseId: String? = null,
        params: String? = null,
        avoidUrls: Set<String> = emptySet()
    ): String? =
        withContext(Dispatchers.IO) {
            val key = genreTitle.trim().lowercase()
            moodCoverCache[key]?.takeIf { it !in avoidUrls }?.let { return@withContext it }
            val shelves = fetchGenreOrMoodShelves(genreTitle, browseId, params)
            val allCandidates = shelves.flatMap { s -> s.items }
                .mapNotNull { it.thumbnailUrl.takeIf { u -> u.isNotBlank() } }
                .distinct()
            val picked = allCandidates.firstOrNull { it !in avoidUrls }
                ?: allCandidates.firstOrNull()
            picked?.also { moodCoverCache[key] = it }
        }

    /**
     * Parses `musicCarouselShelfRenderer` and `musicShelfRenderer` from a YouTube Music `/browse` response
     * (matching OpenTune's `InnertubeParser.parseHome` & `parseTwoRowItem`).
     */
    private fun parseBrowseShelves(root: JSONObject): List<ExploreGenreShelf> {
        val out = mutableListOf<ExploreGenreShelf>()
        val carousels = mutableListOf<JSONObject>()
        collectJsonObjectsByKey(root, "musicCarouselShelfRenderer", carousels)

        for (carousel in carousels) {
            val header = carousel.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            val title = extractRunsText(header?.optJSONObject("title")).ifBlank { "Featured playlists" }
            val strapline = extractRunsText(header?.optJSONObject("strapline"))
            if (title.contains("video", ignoreCase = true) && !title.contains("game", ignoreCase = true)) continue

            val contents = carousel.optJSONArray("contents") ?: continue
            val items = mutableListOf<ExploreShelfItem>()
            for (i in 0 until contents.length()) {
                val entry = contents.optJSONObject(i) ?: continue
                val twoRow = entry.optJSONObject("musicTwoRowItemRenderer")
                if (twoRow != null) {
                    parseTwoRowShelfItem(twoRow)?.let { items.add(it) }
                    continue
                }
                val listRow = entry.optJSONObject("musicResponsiveListItemRenderer")
                if (listRow != null) {
                    parseMusicItem(listRow)?.let { song ->
                        items.add(
                            ExploreShelfItem(
                                title = song.title,
                                subtitle = "${song.artist} • Song",
                                thumbnailUrl = song.thumbnailUrl,
                                videoId = song.videoId,
                                preloadedSongs = listOf(song)
                            )
                        )
                    }
                }
            }
            if (items.isNotEmpty()) {
                out.add(ExploreGenreShelf(title = title, subtitle = strapline, items = items.distinctBy { "${it.title}_${it.browseId ?: it.videoId}" }))
            }
        }
        return out
    }

    private fun parseTwoRowShelfItem(renderer: JSONObject): ExploreShelfItem? {
        val title = extractRunsText(renderer.optJSONObject("title")).trim()
        if (title.isBlank()) return null
        val subtitle = extractRunsText(renderer.optJSONObject("subtitle")).trim()
            .ifBlank { "Playlist • YouTube Music" }

        val navEndpoint = renderer.optJSONObject("navigationEndpoint")
        val rawBrowseId = navEndpoint?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        val watchEndpoint = navEndpoint?.optJSONObject("watchEndpoint")
            ?: renderer.optJSONObject("thumbnailOverlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
            ?: renderer.optJSONObject("thumbnailOverlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchPlaylistEndpoint")

        val videoId = watchEndpoint?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: rawBrowseId.takeIf { it.startsWith("MPED") }?.removePrefix("MPED")
        val playlistId = watchEndpoint?.optString("playlistId")?.takeIf { it.isNotBlank() }
        val browseId = rawBrowseId.takeUnless { it.startsWith("MPED") || it.isBlank() }

        val thumbs = renderer.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val rawThumb = if (thumbs != null && thumbs.length() > 0) {
            thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
        } else ""
        val highResThumb = formatThumbnailUrl(rawThumb)

        val isExplicit = renderer.optJSONArray("subtitleBadges")?.toString()?.contains("MUSIC_EXPLICIT_BADGE") == true

        return ExploreShelfItem(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = highResThumb,
            browseId = browseId,
            playlistId = playlistId,
            videoId = videoId,
            isExplicit = isExplicit,
            seedQuery = "$title ${subtitle.substringBefore("•").trim()}"
        )
    }

    /**
     * Curated multi-section carousel definitions matching the exact structure in OpenTune / YouTube Music
     * (e.g. Focus -> Flow state, Classical focus, Quiet songs, Intense + invigorating, Jazz focus;
     *  Gaming -> Popular gaming playlists, Gaming moods, Gaming soundtracks).
     */
    private fun getCuratedSectionSpecsForGenre(genreTitle: String): List<Pair<String, List<Pair<String, String>>>> {
        val g = genreTitle.trim()
        return when (g.lowercase()) {
            "focus", "deep focus" -> listOf(
                "Flow state" to listOf(
                    "Lofi Loft" to "lofi beats study relax",
                    "deep chill" to "deep chill ambient electronic focus",
                    "Lofi Flow" to "chillhop lofi flow study beats",
                    "Coding & Flow" to "synthwave coding focus instrumental",
                    "Binaural Focus" to "binaural beats deep concentration"
                ),
                "Classical focus" to listOf(
                    "Classical Focus" to "classical music for studying piano strings",
                    "Mellow cello" to "yo-yo ma mellow cello solo classical",
                    "Gentle Piano" to "ludovico einaudi yiruma gentle piano solo",
                    "Baroque Study" to "bach vivaldi baroque study music",
                    "Modern Classical" to "max richter olafur arnalds modern classical"
                ),
                "Quiet songs" to listOf(
                    "All-Time Easy Acoustics" to "soft acoustic guitar chill songs",
                    "Easy Modern Soul" to "mellow neo soul acoustic r&b",
                    "Peaceful Indie" to " Novo Amor Bon Iver peaceful indie folk",
                    "Soft Hindi Unplugged" to "arijit singh MTV unplugged acoustic calm",
                    "Warm Strings" to "calm acoustic guitar instrumental"
                ),
                "Intense + invigorating" to listOf(
                    "Classic Rock Instrumentals" to "post rock instrumental explosions in the sky",
                    "Left Brain, Right Beat" to "upbeat electronic focus tycho bonobo",
                    "Electronic Focus" to "ambient techno Jon Hopkins Christian Loffler",
                    "Cinematic Scores" to "hans zimmer interstellar soundtrack focus"
                ),
                "Jazz focus" to listOf(
                    "Coffee Table Jazz" to "bill evans chet baker instrumental jazz",
                    "Late Night Sax" to "smooth jazz saxophone instrumental study",
                    "Jazz Hop Cafe" to "jazzhop instrumental beats study",
                    "Piano Trio Sessions" to "oscar peterson keith jarrett jazz piano"
                )
            )
            "gaming" -> listOf(
                "Popular gaming playlists" to listOf(
                    "Gaming Hits" to "top gaming music hits NCS Alan Walker Marshmello",
                    "Pop Gaming Hits" to "upbeat pop gaming montage songs Imagine Dragons",
                    "Hip-Hop Gaming" to "travis scott metro boomin gaming hip hop beats",
                    "Phonk Drift & FPS" to "brazilian phonk drift KORDHELL DVRST gaming",
                    "EDM Arena" to "riot games valorant league of legends music"
                ),
                "Gaming moods" to listOf(
                    "Adrenaline Gaming" to "cyberpunk 2077 doom eternal high energy gaming",
                    "Cozy Gaming" to "stardew valley animal crossing lofi cozy gaming",
                    "Sports Gaming" to "EA FC FIFA soundtrack hits upbeat",
                    "Late Night Grind" to "synthwave darkwave night gaming mix"
                ),
                "Gaming soundtracks" to listOf(
                    "Hi-Scores" to "retro 8-bit chiptune undertale soundtrack",
                    "Video Game Soundtracks" to "witcher 3 skyrim halo god of war ost",
                    "Pokémon & Nintendo Vibes" to "pokemon lofi zelda breath of the wild ost",
                    "Final Fantasy & JRPG" to "persona 5 nier automata final fantasy ost"
                )
            )
            "workout" -> listOf(
                "High-energy workout" to listOf(
                    "Beast Mode Gym" to "gym workout motivation hardstyle phonk",
                    "Workout Hits" to "high energy pop edm workout hits",
                    "Desi Gym Pump" to "sidhu moose wala karan aujlaraftaar gym workout",
                    "Power Lifting" to "eminem till i collapse neffex workout",
                    "HIIT & Tabata" to "140 bpm high tempo cardio workout edm"
                ),
                "Workout mixes" to listOf(
                    "Hip-Hop Workout" to "drake travis scott kanye west workout rap",
                    "EDM Cardio" to "david guetta calvin harris martin garrix workout",
                    "Punjabi Power" to "ap dhillon diljit dosanjh shubh gym punjabi",
                    "Rock Adrenaline" to "linkin park acdc metallica workout rock"
                ),
                "Cardio & Running" to listOf(
                    "Morning Run 160 BPM" to "upbeat running songs 160 bpm",
                    "Marathon Stride" to "the weeknd blinding lights dua lipa running",
                    "Outdoor Cycling" to "progressive house avicii swedish house mafia"
                )
            )
            "chill", "acoustic chill" -> listOf(
                "Coffee shop & lofi" to listOf(
                    "Coffee Shop Blend" to "coffee shop acoustic chill songs",
                    "Lofi Beats" to "chilledcow lofi hip hop radio beats to relax",
                    "Rainy Day Lofi" to "rainy day lofi chillhop beats",
                    "Desi Lofi Vibes" to "bollywood hindi lofi chill slowed reverb"
                ),
                "Chill vibes" to listOf(
                    "Chill Hits" to "lauv jeremy zucker keshi chill pop",
                    "Sunset Lounge" to "kygo petit biscuit tropical house chill",
                    "Indie Chillout" to "cigarettes after sex clairo men i trust",
                    "Punjabi Chill" to "prophec talwiinder mitraz chill punjabi"
                ),
                "Acoustic & unwind" to listOf(
                    "Acoustic Unplugged" to "ed sheeran john mayer acoustic chill",
                    "Sukoon Hindi" to "prateek kuhad anuv jain zaeden indie hindi",
                    "Evening Breeze" to "norah jones jack johnson easy listening"
                )
            )
            else -> listOf(
                "Popular $g playlists" to listOf(
                    "$g Hits" to "$g top hits songs",
                    "Best of $g" to "best $g music playlist",
                    "$g Viral Charts" to "trending $g viral songs",
                    "$g Classics" to "all time classic $g hits"
                ),
                "$g moods & mixes" to listOf(
                    "$g Vibes" to "$g chill upbeat vibe songs",
                    "Late Night $g" to "$g late night mix",
                    "$g Drive" to "$g road trip car mix",
                    "$g Non-Stop" to "$g mashup mix songs"
                ),
                "$g essentials" to listOf(
                    "$g Anthems" to "$g anthem popular tracks",
                    "Fresh $g" to "new $g latest release songs",
                    "$g Unplugged & Acoustic" to "$g acoustic lofi version"
                )
            )
        }
    }

    private suspend fun buildCuratedPlaylistShelf(
        genreTitle: String,
        sectionTitle: String,
        playlistSpecs: List<Pair<String, String>>
    ): ExploreGenreShelf? = kotlinx.coroutines.coroutineScope {
        val gate = Semaphore(3)
        val cards = playlistSpecs.map { (playlistName, query) ->
            async {
                gate.withPermit {
                    runCatching {
                        val page = search(query, filtered = true)
                        val songs = page.songs
                        val cover = songs.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl.orEmpty()
                        if (songs.isNotEmpty()) {
                            val item = ExploreShelfItem(
                                title = playlistName,
                                subtitle = "Playlist • YouTube Music",
                                thumbnailUrl = cover,
                                seedQuery = query,
                                preloadedSongs = songs
                            )
                            collectionPageCache[query] = ExploreCollectionPage(
                                browseId = query,
                                title = playlistName,
                                subtitle = "$genreTitle • Playlist • YouTube Music",
                                thumbnailUrl = cover,
                                songs = songs
                            )
                            item
                        } else null
                    }.getOrNull()
                }
            }
        }.mapNotNull { it.await() }

        if (cards.isEmpty()) null else ExploreGenreShelf(title = sectionTitle, items = cards)
    }

    /**
     * Fetches the full tracklist for a playlist/album card (`ExploreShelfItem`) clicked inside
     * the Genre/Mood Detail Subscreen (matching OpenTune's `MusicRepository.collection(browseId)`).
     */
    suspend fun fetchCollectionSongs(item: ExploreShelfItem): ExploreCollectionPage = withContext(Dispatchers.IO) {
        val cacheKey = item.browseId?.takeIf { it.isNotBlank() }
            ?: item.playlistId?.takeIf { it.isNotBlank() }
            ?: item.seedQuery?.takeIf { it.isNotBlank() }
            ?: item.title.lowercase()

        collectionPageCache[cacheKey]?.takeIf { it.songs.isNotEmpty() }?.let { cached ->
            prefetchVisibleStreams(cached.songs.take(3).map { it.videoId })
            return@withContext cached
        }

        if (item.preloadedSongs.isNotEmpty() && item.browseId.isNullOrBlank()) {
            val page = ExploreCollectionPage(
                browseId = cacheKey,
                title = item.title,
                subtitle = item.subtitle.ifBlank { "Playlist • YouTube Music" },
                thumbnailUrl = item.thumbnailUrl.ifBlank { item.preloadedSongs.first().thumbnailUrl },
                songs = item.preloadedSongs
            )
            collectionPageCache[cacheKey] = page
            prefetchVisibleStreams(page.songs.take(3).map { it.videoId })
            return@withContext page
        }

        val songs = mutableListOf<OnlineSong>()
        var resolvedTitle = item.title
        var resolvedSubtitle = item.subtitle.ifBlank { "Playlist • YouTube Music" }
        var resolvedThumb = item.thumbnailUrl

        // 1. Try YouTube Music /browse endpoint if browseId or playlistId is present
        val rawId = item.browseId?.takeIf { it.isNotBlank() } ?: item.playlistId?.takeIf { it.isNotBlank() }
        if (!rawId.isNullOrBlank()) {
            val browseTarget = when {
                rawId.startsWith("VL") || rawId.startsWith("MPRE") || rawId.startsWith("UC") || rawId.startsWith("FE") -> rawId
                rawId.startsWith("PL") || rawId.startsWith("OLAK") || rawId.startsWith("RD") -> "VL$rawId"
                else -> rawId
            }
            try {
                val body = postBrowseRequest(browseTarget)
                if (!body.isNullOrBlank()) {
                    val root = JSONObject(body)
                    // Parse header if present
                    val headerList = mutableListOf<JSONObject>()
                    collectJsonObjectsByKey(root, "musicResponsiveHeaderRenderer", headerList)
                    collectJsonObjectsByKey(root, "musicDetailHeaderRenderer", headerList)
                    val header = headerList.firstOrNull()
                    if (header != null) {
                        extractRunsText(header.optJSONObject("title")).takeIf { it.isNotBlank() }?.let { resolvedTitle = it }
                        extractRunsText(header.optJSONObject("subtitle")).takeIf { it.isNotBlank() }?.let { resolvedSubtitle = it }
                    }

                    val rowRenderers = mutableListOf<JSONObject>()
                    collectJsonObjectsByKey(root, "musicResponsiveListItemRenderer", rowRenderers)
                    for (r in rowRenderers) {
                        parseBrowseTrackItem(r, fallbackThumb = resolvedThumb)?.let { songs.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchCollectionSongs /browse failed for $browseTarget", e)
            }

            // 2. If it's a radio/mix or /browse returned empty, try /next endpoint with playlistId
            if (songs.isEmpty()) {
                val cleanPlaylistId = rawId.removePrefix("VL")
                val nextSongs = runCatching {
                    fetchNextEndpoint(videoId = item.videoId.orEmpty(), playlistId = cleanPlaylistId, excludeVideoId = null)
                }.getOrDefault(emptyList())
                songs.addAll(nextSongs)
            }
        }

        // 3. Fallback: search YouTube Music for the playlist's songs so a playlist never opens empty
        if (songs.size < 3) {
            val fallbackQuery = item.seedQuery?.takeIf { it.isNotBlank() }
                ?: "${item.title} ${item.subtitle.replace("Playlist", "").replace("YouTube Music", "").replace("•", "").trim()}".trim()
            val searched = runCatching { search(fallbackQuery, filtered = true).songs }.getOrDefault(emptyList())
            val existingIds = songs.map { it.videoId }.toSet()
            songs.addAll(searched.filter { it.videoId !in existingIds })
        }

        val uniqueSongs = songs.distinctBy { it.videoId }
        if (resolvedThumb.isBlank()) {
            resolvedThumb = uniqueSongs.firstOrNull()?.thumbnailUrl.orEmpty()
        }

        val page = ExploreCollectionPage(
            browseId = cacheKey,
            title = resolvedTitle,
            subtitle = resolvedSubtitle,
            thumbnailUrl = resolvedThumb,
            songs = uniqueSongs
        )
        if (uniqueSongs.isNotEmpty()) {
            collectionPageCache[cacheKey] = page
            prefetchVisibleStreams(uniqueSongs.take(3).map { it.videoId })
        }
        page
    }

    private fun parseBrowseTrackItem(renderer: JSONObject, fallbackThumb: String): OnlineSong? {
        val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: return null

        val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
        val title = extractRunsText(
            flexColumns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        ).trim()
        if (title.isBlank()) return null

        val subRuns = flexColumns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        var artist = "YouTube Music"
        var durationText = ""
        if (subRuns != null) {
            val parts = mutableListOf<String>()
            for (k in 0 until subRuns.length()) {
                val t = subRuns.optJSONObject(k)?.optString("text")?.trim().orEmpty()
                if (t.isNotBlank() && t != "•") parts.add(t)
            }
            artist = parts.firstOrNull { !it.contains(":") && !it.equals("Song", ignoreCase = true) } ?: "YouTube Music"
            durationText = parts.lastOrNull { it.contains(":") }.orEmpty()
        }

        if (durationText.isBlank()) {
            val fixedCols = renderer.optJSONArray("fixedColumns")
            if (fixedCols != null && fixedCols.length() > 0) {
                durationText = extractRunsText(
                    fixedCols.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                        ?.optJSONObject("text")
                ).trim()
            }
        }

        val thumbs = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val rawThumb = if (thumbs != null && thumbs.length() > 0) {
            thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
        } else fallbackThumb

        return OnlineSong(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = formatThumbnailUrl(rawThumb).ifBlank { fallbackThumb },
            durationMs = parseDurationToMs(durationText),
            durationText = durationText
        )
    }

    private fun extractRunsText(textObj: JSONObject?): String {
        if (textObj == null) return ""
        textObj.optString("simpleText")?.takeIf { it.isNotBlank() }?.let { return it }
        val runs = textObj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    private fun collectJsonObjectsByKey(node: Any?, targetKey: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(targetKey)?.let { out.add(it) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != targetKey) {
                        collectJsonObjectsByKey(node.opt(k), targetKey, out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectJsonObjectsByKey(node.opt(i), targetKey, out)
                }
            }
        }
    }
}