package com.example.myplayer.data.sponsorblock

import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
    val uuid: String,
    val videoDurationMs: Long
)

/**
 * Privacy-preserving SponsorBlock engine for MyPlayer Windows Desktop.
 * Queries sponsor.ajay.app using only the first 4 hex chars of SHA-256(videoId).
 */
@Singleton
class SponsorBlockManager @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "SponsorBlockManager"
        private const val API_URL = "https://sponsor.ajay.app/api/skipSegments"
        private const val CACHE_SIZE = 200
        val DEFAULT_CATEGORIES = setOf("music_offtopic", "sponsor", "intro", "outro", "selfpromo")
    }

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<SponsorSegment>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SponsorSegment>>?) =
                size > CACHE_SIZE
        }
    )

    private val _activeSegments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val activeSegments: StateFlow<List<SponsorSegment>> = _activeSegments.asStateFlow()

    private val _lastSkippedCategory = MutableStateFlow<String?>(null)
    val lastSkippedCategory: StateFlow<String?> = _lastSkippedCategory.asStateFlow()

    @Volatile
    var isEnabled: Boolean = true

    private var monitorJob: Job? = null
    private var lastSkippedUuid: String? = null

    suspend fun fetchSegments(
        videoId: String,
        categories: Set<String> = DEFAULT_CATEGORIES
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("online://").trim()
        if (cleanId.length != 11 || categories.isEmpty() || !isEnabled) return@withContext emptyList()

        val cacheKey = "$cleanId:${categories.sorted().joinToString(",")}"
        cache[cacheKey]?.let { return@withContext it }

        val shaPrefix = sha256Hex(cleanId).take(4)
        val urlBuilder = "$API_URL/$shaPrefix".toHttpUrlOrNull()?.newBuilder() ?: return@withContext emptyList()
        val catsJson = JSONArray(categories.toList()).toString()
        urlBuilder.addQueryParameter("categories", catsJson)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "MyPlayer-Desktop/6.8 (https://myplayer-660ee.web.app)")
            .build()

        val parsed = runCatching {
            okHttpClient.newCall(request).execute().use { resp ->
                if (resp.code == 404) return@use emptyList<SponsorSegment>()
                if (!resp.isSuccessful) return@use null
                parseResponse(resp.body?.string().orEmpty(), cleanId)
            }
        }.getOrNull() ?: return@withContext emptyList()

        cache[cacheKey] = parsed
        parsed
    }

    fun attachToPlayer(
        scope: CoroutineScope,
        player: Player,
        currentVideoIdProvider: () -> String?,
        onSegmentSkipped: (SponsorSegment) -> Unit = {}
    ) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.Main) {
            var loadedVideoId: String? = null
            while (isActive) {
                if (isEnabled && player.isPlaying) {
                    val vid = currentVideoIdProvider()?.removePrefix("online://")?.trim()
                    if (vid != null && vid.length == 11) {
                        if (vid != loadedVideoId) {
                            loadedVideoId = vid
                            lastSkippedUuid = null
                            _activeSegments.value = fetchSegments(vid)
                        }
                        val pos = player.currentPosition
                        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        val hit = _activeSegments.value.firstOrNull { seg ->
                            pos in seg.startMs until (seg.endMs - 250L) &&
                                seg.endMs < duration - 500L &&
                                seg.uuid != lastSkippedUuid
                        }
                        if (hit != null) {
                            lastSkippedUuid = hit.uuid
                            Log.d(TAG, "Skipping SponsorBlock segment [${hit.category}] ${hit.startMs}ms -> ${hit.endMs}ms")
                            player.seekTo(hit.endMs)
                            _lastSkippedCategory.value = hit.category
                            onSegmentSkipped(hit)
                        }
                    } else {
                        _activeSegments.value = emptyList()
                    }
                }
                delay(400L)
            }
        }
    }

    internal fun parseResponse(body: String, targetVideoId: String): List<SponsorSegment> {
        if (body.isBlank()) return emptyList()
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        for (i in 0 until array.length()) {
            val videoObj = array.optJSONObject(i) ?: continue
            if (videoObj.optString("videoID") != targetVideoId) continue
            val segArray = videoObj.optJSONArray("segments") ?: return emptyList()
            val out = mutableListOf<SponsorSegment>()
            for (j in 0 until segArray.length()) {
                val segObj = segArray.optJSONObject(j) ?: continue
                if (segObj.optInt("locked", 0) == 0 && segObj.optInt("votes", 0) < 0) continue
                val pair = segObj.optJSONArray("segment") ?: continue
                if (pair.length() < 2) continue
                val startSec = pair.optDouble(0, -1.0)
                val endSec = pair.optDouble(1, -1.0)
                if (startSec < 0 || endSec <= startSec) continue
                val cat = segObj.optString("category", "")
                val uuid = segObj.optString("UUID", "$j")
                val videoDurSec = segObj.optDouble("videoDuration", 0.0)
                out.add(
                    SponsorSegment(
                        startMs = (startSec * 1000.0).toLong(),
                        endMs = (endSec * 1000.0).toLong(),
                        category = cat,
                        uuid = uuid,
                        videoDurationMs = (videoDurSec * 1000.0).toLong()
                    )
                )
            }
            return out.sortedBy { it.startMs }
        }
        return emptyList()
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
