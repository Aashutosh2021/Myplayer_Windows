package com.example.myplayer.data.lyrics

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.example.myplayer.data.local.dao.CachedLyricsDao
import com.example.myplayer.data.local.entity.CachedLyricsEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class WordTimestamp(
    val word: String,
    val startMs: Long,
    val endMs: Long
)

data class RichLyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<WordTimestamp> = emptyList(),
    val sectionTag: String? = null // e.g., "Intro", "Verse 1", "Chorus", "Bridge", "Outro"
)

data class LyricsResult(
    val plainLyrics: String?,
    val syncedLyrics: String?,   // LRC format: "[mm:ss.xx] line" (may include <mm:ss.xx> word tags)
    val trackName: String,
    val artistName: String,
    val sourceProvider: String = "LRCLIB",
    val richLines: List<RichLyricLine> = emptyList()
)

sealed class LyricsState {
    object Idle : LyricsState()
    object Loading : LyricsState()
    data class Found(val result: LyricsResult) : LyricsState()
    data class NotFound(val reason: String) : LyricsState()
}

/**
 * Upgraded 8-Provider Multi-Source Lyrics Repository (OpenTune Engine Port for MyPlayer):
 * Waterfall + Parallel Race across:
 * 1. Room SQLite Cache (CachedLyricsDao)
 * 2. Local Companion .lrc & Embedded ID3/MP4 Tags (100% Offline)
 * 3. BetterLyrics (Word-by-word TTML / Apple Music timing)
 * 4. LyricsPlus (Community Servers with Word-by-Word JSON timing)
 * 5. SimpMusic (Exact 11-char YouTube Video ID matched LRC)
 * 6. LRCLIB (Primary synced + plain LRC database)
 * 7. NetEase Cloud Music (High-coverage Asian + Global LRC)
 * 8. KuGou Music (Base64 LRC fallback)
 */
@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val cachedLyricsDao: CachedLyricsDao
) {
    companion object {
        private const val TAG = "LyricsRepository"
        private const val LRCLIB_BASE_URL = "https://lrclib.net/api"
        private const val BETTER_LYRICS_API = "https://lyrics-api.boidu.dev/getLyrics"
        private const val SIMPMUSIC_API = "https://api-lyrics.simpmusic.org/v1"
        private const val NETEASE_API = "https://music.163.com/api"
        private const val KUGOU_API = "http://lyrics.kugou.com"
        private val LYRICS_PLUS_SERVERS = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app"
        )
        private const val USER_AGENT = "MyPlayer/6.9 (https://myplayer-660ee.web.app)"

        private val LRC_LINE_REGEX = Regex("""^\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]\s*(.*)$""")
        private val WORD_TAG_REGEX = Regex("""<(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?>\s*([^<]+)""")
        private val SECTION_HEADER_REGEX = Regex("""^\[(verse|chorus|pre-chorus|post-chorus|bridge|intro|outro|hook|interlude|refrain|solo|instrumental)[^\]]*\]$""", RegexOption.IGNORE_CASE)
        private val BRACKET_NOISE_REGEX = Regex("""[\(\[].*?[\)\]]""")
        private val KEYWORD_NOISE_REGEX = Regex(
            """(?i)\b(official|video|audio|lyrics?|lyrical|full song|song|hd|4k|mv|m/v|remix|reprise|cover|live|version|visualizer|color coded)\b"""
        )
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val FEAT_REGEX = Regex("""(?i)\b(feat\.?|ft\.?)\s+.*$""")
        private val YT_VIDEO_ID_REGEX = Regex("""^[A-Za-z0-9_-]{11}$""")
    }

    suspend fun fetchLyrics(
        songId: String,
        trackName: String,
        artistName: String,
        durationSeconds: Int = 0,
        albumName: String? = null,
        localPath: String? = null
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val cleanId = songId.removePrefix("online://").removePrefix("spotify:track:").trim()
        val cleanArtist = cleanArtistName(artistName)
        val cleanTrack = cleanTrackTitle(trackName, cleanArtist)
        val durationMs = durationSeconds * 1000L

        // 1. Check Room SQLite cache first
        try {
            val cached = cachedLyricsDao.getLyricsForSong(songId)
                ?: cachedLyricsDao.getLyricsForSong(cleanId)
                ?: cachedLyricsDao.getLyricsForSongOrMetadata(cleanId, trackName, artistName)
                ?: cachedLyricsDao.getLyricsForSongOrMetadata(cleanId, cleanTrack, cleanArtist)

            if (cached != null && (!cached.syncedLyrics.isNullOrBlank() || !cached.plainLyrics.isNullOrBlank())) {
                val rich = cached.syncedLyrics?.let { parseRichSyncedLyrics(it) } ?: emptyList()
                return@withContext LyricsResult(
                    plainLyrics = cached.plainLyrics,
                    syncedLyrics = cached.syncedLyrics,
                    trackName = cached.trackName,
                    artistName = cached.artistName,
                    sourceProvider = "Offline Cache",
                    richLines = rich
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading Room lyrics cache for $songId", e)
        }

        // 2. Offline Fallback: Companion .lrc files & Embedded ID3/MP4 USLT tags
        readLocalOfflineLyrics(songId, trackName, artistName, localPath)?.let { return@withContext it }

        // 3. Multi-Provider Online Race + Quality Waterfall (OpenTune Architecture)
        val bestOnline = coroutineScope {
            val betterDeferred = async {
                withTimeoutOrNull(4_500L) { fetchFromBetterLyrics(cleanTrack, cleanArtist, durationMs) }
            }
            val lyricsPlusDeferred = async {
                withTimeoutOrNull(5_500L) { fetchFromLyricsPlus(cleanTrack, cleanArtist, durationMs, albumName) }
            }
            val simpDeferred = async {
                if (YT_VIDEO_ID_REGEX.matches(cleanId)) {
                    withTimeoutOrNull(5_000L) { fetchFromSimpMusic(cleanId, trackName, artistName, durationMs) }
                } else null
            }
            val lrcLibDeferred = async {
                withTimeoutOrNull(6_500L) { fetchLyricsFromLrcLib(trackName, artistName, durationSeconds, albumName) }
            }

            betterDeferred.await()
                ?: lyricsPlusDeferred.await()
                ?: simpDeferred.await()
                ?: lrcLibDeferred.await()
        } ?: fetchFromNetEase(cleanTrack, cleanArtist, durationMs)
          ?: fetchFromKuGou(cleanTrack, cleanArtist, durationMs)

        if (bestOnline != null) {
            cacheResultInRoom(songId, cleanId, trackName, artistName, bestOnline)
        }

        return@withContext bestOnline
    }

    private suspend fun cacheResultInRoom(
        songId: String,
        cleanId: String,
        trackName: String,
        artistName: String,
        result: LyricsResult
    ) {
        try {
            val entity = CachedLyricsEntity(
                songId = songId,
                plainLyrics = result.plainLyrics,
                syncedLyrics = result.syncedLyrics,
                trackName = trackName.ifBlank { result.trackName },
                artistName = artistName.ifBlank { result.artistName }
            )
            cachedLyricsDao.insertLyrics(entity)
            if (cleanId.isNotBlank() && cleanId != songId) {
                cachedLyricsDao.insertLyrics(entity.copy(songId = cleanId))
            }
            val lrcText = result.syncedLyrics?.takeIf { it.isNotBlank() }
                ?: result.plainLyrics?.takeIf { it.isNotBlank() }
            if (!lrcText.isNullOrBlank()) {
                saveOfflineLrcFile(cleanId.ifBlank { songId }, lrcText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed caching lyrics for $songId", e)
        }
    }

    fun saveOfflineLrcFile(songId: String, lrcText: String): File? {
        if (songId.isBlank() || lrcText.isBlank()) return null
        val cleanId = songId.removePrefix("online://").removePrefix("spotify:track:").removePrefix("spotify:").trim()
        if (cleanId.isBlank()) return null
        return try {
            val lyricsDir = File(context.filesDir, "lyrics").apply { mkdirs() }
            val outFile = File(lyricsDir, "$cleanId.lrc")
            outFile.writeText(lrcText)
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving offline .lrc for $cleanId", e)
            null
        }
    }

    private fun fetchFromBetterLyrics(title: String, artist: String, durationMs: Long): LyricsResult? {
        if (title.isBlank()) return null
        try {
            val urlBuilder = BETTER_LYRICS_API.toHttpUrlOrNull()?.newBuilder() ?: return null
            urlBuilder.addQueryParameter("s", title)
            urlBuilder.addQueryParameter("a", artist)
            if (durationMs > 0) urlBuilder.addQueryParameter("d", (durationMs / 1000).toString())

            val req = Request.Builder().url(urlBuilder.build()).header("User-Agent", USER_AGENT).build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val ttml = JSONObject(body).optString("ttml", "").takeIf { it.isNotBlank() } ?: return null
                val lrcLines = parseTtmlToEnhancedLrc(ttml)
                if (lrcLines.isEmpty()) return null
                val syncedText = lrcLines.joinToString("\n")
                val rich = parseRichSyncedLyrics(syncedText)
                val plainText = rich.joinToString("\n") { it.text }
                return LyricsResult(
                    plainLyrics = plainText,
                    syncedLyrics = syncedText,
                    trackName = title,
                    artistName = artist,
                    sourceProvider = "BetterLyrics (Word-Sync)",
                    richLines = rich
                )
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchFromLyricsPlus(title: String, artist: String, durationMs: Long, album: String?): LyricsResult? {
        if (title.isBlank()) return null
        for (server in LYRICS_PLUS_SERVERS) {
            try {
                val urlBuilder = "$server/v2/lyrics/get".toHttpUrlOrNull()?.newBuilder() ?: continue
                urlBuilder.addQueryParameter("title", title)
                urlBuilder.addQueryParameter("artist", artist)
                if (durationMs > 0) urlBuilder.addQueryParameter("duration", (durationMs / 1000).toString())
                if (!album.isNullOrBlank()) urlBuilder.addQueryParameter("album", album)

                val req = Request.Builder().url(urlBuilder.build()).header("User-Agent", USER_AGENT).build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val lyricsArray = json.optJSONArray("lyrics") ?: return@use
                    if (lyricsArray.length() == 0) return@use

                    val sbSynced = StringBuilder()
                    val sbPlain = StringBuilder()
                    for (i in 0 until lyricsArray.length()) {
                        val item = lyricsArray.optJSONObject(i) ?: continue
                        val startTimeMs = item.optLong("time", -1L)
                        val text = item.optString("text", "").trim()
                        if (text.isBlank()) continue
                        sbPlain.append(text).append("\n")
                        if (startTimeMs >= 0L) {
                            sbSynced.append(formatLrcTimestamp(startTimeMs)).append(" ").append(text).append("\n")
                        }
                    }
                    val syncedStr = sbSynced.toString().trim().takeIf { it.isNotBlank() }
                    val plainStr = sbPlain.toString().trim().takeIf { it.isNotBlank() }
                    if (syncedStr != null || plainStr != null) {
                        return LyricsResult(
                            plainLyrics = plainStr,
                            syncedLyrics = syncedStr,
                            trackName = title,
                            artistName = artist,
                            sourceProvider = "LyricsPlus",
                            richLines = syncedStr?.let { parseRichSyncedLyrics(it) } ?: emptyList()
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun fetchFromSimpMusic(videoId: String, title: String, artist: String, durationMs: Long): LyricsResult? {
        try {
            val req = Request.Builder()
                .url("$SIMPMUSIC_API/$videoId")
                .header("User-Agent", "SimpMusicLyrics/1.0")
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val dataArr = root.optJSONArray("data") ?: return null
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val synced = item.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
                    val plain = item.optString("plainLyrics", "").takeIf { it.isNotBlank() }
                    if (synced != null || plain != null) {
                        return LyricsResult(
                            plainLyrics = plain,
                            syncedLyrics = synced,
                            trackName = title,
                            artistName = artist,
                            sourceProvider = "SimpMusic",
                            richLines = synced?.let { parseRichSyncedLyrics(it) } ?: emptyList()
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun fetchFromNetEase(title: String, artist: String, durationMs: Long): LyricsResult? {
        if (title.isBlank()) return null
        try {
            val searchUrl = "$NETEASE_API/search/get".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("s", "$title $artist".trim())
                ?.addQueryParameter("type", "1")
                ?.addQueryParameter("limit", "6")
                ?.build() ?: return null
            val searchReq = Request.Builder().url(searchUrl).header("User-Agent", USER_AGENT).build()
            val neteaseSongId = okHttpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string() ?: return null)
                val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
                if (songs.length() == 0) return null
                songs.optJSONObject(0)?.optLong("id", -1L)?.takeIf { it > 0 }
            } ?: return null

            val lrcUrl = "$NETEASE_API/song/lyric?id=$neteaseSongId&lv=1"
            okHttpClient.newCall(Request.Builder().url(lrcUrl).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val lrc = JSONObject(resp.body?.string() ?: return null)
                    .optJSONObject("lrc")
                    ?.optString("lyric", "")
                    ?.takeIf { it.isNotBlank() } ?: return null
                val rich = parseRichSyncedLyrics(lrc)
                return LyricsResult(
                    plainLyrics = rich.joinToString("\n") { it.text }.ifBlank { lrc },
                    syncedLyrics = lrc,
                    trackName = title,
                    artistName = artist,
                    sourceProvider = "NetEase",
                    richLines = rich
                )
            }
        } catch (_: Exception) {}
        return null
    }

    private fun fetchFromKuGou(title: String, artist: String, durationMs: Long): LyricsResult? {
        if (title.isBlank()) return null
        try {
            val keyword = if (artist.isNotBlank()) "$artist - $title" else title
            val searchUrl = "$KUGOU_API/search".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("ver", "1")
                ?.addQueryParameter("man", "yes")
                ?.addQueryParameter("client", "mobi")
                ?.addQueryParameter("keyword", keyword)
                ?.apply { if (durationMs > 0) addQueryParameter("duration", durationMs.toString()) }
                ?.build() ?: return null
            val candidate = okHttpClient.newCall(Request.Builder().url(searchUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val candidates = JSONObject(resp.body?.string() ?: return null).optJSONArray("candidates") ?: return null
                if (candidates.length() == 0) return null
                candidates.optJSONObject(0)
            } ?: return null

            val id = candidate.optString("id", "")
            val accessKey = candidate.optString("accesskey", "")
            if (id.isBlank() || accessKey.isBlank()) return null

            val dlUrl = "$KUGOU_API/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
            okHttpClient.newCall(Request.Builder().url(dlUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val b64 = JSONObject(resp.body?.string() ?: return null).optString("content", "")
                if (b64.isBlank()) return null
                val decodedLrc = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                val rich = parseRichSyncedLyrics(decodedLrc)
                if (rich.isEmpty()) return null
                return LyricsResult(
                    plainLyrics = rich.joinToString("\n") { it.text },
                    syncedLyrics = decodedLrc,
                    trackName = title,
                    artistName = artist,
                    sourceProvider = "KuGou",
                    richLines = rich
                )
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseTtmlToEnhancedLrc(ttml: String): List<String> {
        val pRegex = Regex("""<p\b[^>]*\bbegin="([^"]+)"[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val spanRegex = Regex("""<span\b[^>]*\bbegin="([^"]+)"(?:[^>]*\bend="([^"]+)")?[^>]*>(.*?)</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tagStripRegex = Regex("""<[^>]+>""")
        val out = mutableListOf<String>()

        for (match in pRegex.findAll(ttml)) {
            val beginMs = parseTtmlTimeMs(match.groupValues[1]) ?: continue
            val innerHtml = match.groupValues[2]
            val linePrefix = formatLrcTimestamp(beginMs)
            val spans = spanRegex.findAll(innerHtml).toList()
            if (spans.isNotEmpty()) {
                val wordBuilder = StringBuilder(linePrefix).append(" ")
                for (sp in spans) {
                    val wStart = parseTtmlTimeMs(sp.groupValues[1]) ?: beginMs
                    val wText = sp.groupValues[3].replace(tagStripRegex, "").trim()
                    if (wText.isNotEmpty()) {
                        wordBuilder.append(formatWordTimestamp(wStart)).append(wText).append(" ")
                    }
                }
                out.add(wordBuilder.toString().trim())
            } else {
                val cleanLine = innerHtml.replace(tagStripRegex, "").trim()
                if (cleanLine.isNotEmpty()) {
                    out.add("$linePrefix $cleanLine")
                }
            }
        }
        return out
    }

    private fun parseTtmlTimeMs(raw: String): Long? {
        val s = raw.trim().removeSuffix("s")
        val parts = s.split(":")
        return try {
            when (parts.size) {
                3 -> ((parts[0].toLong() * 3600 + parts[1].toLong() * 60) * 1000 + (parts[2].toDouble() * 1000).toLong())
                2 -> (parts[0].toLong() * 60 * 1000 + (parts[1].toDouble() * 1000).toLong())
                1 -> (parts[0].toDouble() * 1000).toLong()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun formatLrcTimestamp(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val min = totalSec / 60
        val sec = totalSec % 60
        val hundredths = (ms % 1000) / 10
        return String.format("[%02d:%02d.%02d]", min, sec, hundredths)
    }

    private fun formatWordTimestamp(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val min = totalSec / 60
        val sec = totalSec % 60
        val hundredths = (ms % 1000) / 10
        return String.format("<%02d:%02d.%02d>", min, sec, hundredths)
    }

    private suspend fun readLocalOfflineLyrics(
        songId: String,
        trackName: String,
        artistName: String,
        localPath: String?
    ): LyricsResult? {
        try {
            val cleanId = songId.removePrefix("online://").removePrefix("spotify:track:").removePrefix("spotify:").trim()
            val candidateLrcFiles = mutableListOf<File>()
            val lyricsDir = File(context.filesDir, "lyrics")
            if (cleanId.isNotBlank()) {
                candidateLrcFiles.add(File(lyricsDir, "$cleanId.lrc"))
            }
            if (songId.isNotBlank() && songId != cleanId) {
                candidateLrcFiles.add(File(lyricsDir, "$songId.lrc"))
            }
            if (!localPath.isNullOrBlank() && !localPath.startsWith("http") && !localPath.startsWith("online://") && !localPath.startsWith("content://")) {
                val cleanFilePath = if (localPath.startsWith("file://")) {
                    android.net.Uri.parse(localPath).path ?: localPath
                } else localPath
                val audioFile = File(cleanFilePath)
                if (audioFile.exists()) {
                    audioFile.parentFile?.let { parent ->
                        candidateLrcFiles.add(File(parent, "${audioFile.nameWithoutExtension}.lrc"))
                    }
                }
            }
            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val safeTitle = trackName.replace(Regex("[^\\p{L}\\p{N} _\\-]"), "").replace(Regex(" {2,}"), " ").trim().take(60)
            if (safeTitle.isNotBlank()) {
                if (cleanId.isNotBlank()) {
                    candidateLrcFiles.add(File(musicDir, "$safeTitle [$cleanId].lrc"))
                }
                candidateLrcFiles.add(File(musicDir, "$safeTitle.lrc"))
            }
            if (cleanId.isNotBlank() && musicDir.exists()) {
                musicDir.listFiles()?.firstOrNull { f ->
                    f.isFile && f.name.endsWith("[$cleanId].lrc", ignoreCase = true)
                }?.let { candidateLrcFiles.add(it) }
            }
            for (lrcFile in candidateLrcFiles) {
                if (lrcFile.exists() && lrcFile.length() > 0L) {
                    val lrcText = lrcFile.readText()
                    if (lrcText.isNotBlank()) {
                        return saveCustomLyrics(songId, trackName, artistName, lrcText)
                    }
                }
            }
            if (!localPath.isNullOrBlank() && !localPath.startsWith("http") && !localPath.startsWith("content://")) {
                val cleanFilePath = if (localPath.startsWith("file://")) {
                    android.net.Uri.parse(localPath).path ?: localPath
                } else localPath
                val audioFile = File(cleanFilePath)
                if (audioFile.exists()) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(audioFile.absolutePath)
                        val embedded = try { retriever.extractMetadata(1000) } catch (_: Exception) { null }
                        if (!embedded.isNullOrBlank()) {
                            return saveCustomLyrics(songId, trackName, artistName, embedded)
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading local offline lyrics", e)
        }
        return null
    }

    suspend fun fetchAndCacheLyrics(
        songId: String,
        trackName: String,
        artistName: String,
        durationSeconds: Int = 0,
        albumName: String? = null
    ): Boolean = fetchLyrics(songId, trackName, artistName, durationSeconds, albumName) != null

    suspend fun saveCustomLyrics(
        songId: String,
        trackName: String,
        artistName: String,
        lyricsText: String
    ): LyricsResult = withContext(Dispatchers.IO) {
        val trimmed = lyricsText.trim()
        val parsedSynced = parseSyncedLyrics(trimmed)
        val hasSynced = parsedSynced.isNotEmpty()
        val syncedLyrics = if (hasSynced) trimmed else null
        val plainLyrics = if (hasSynced) {
            parsedSynced.joinToString("\n") { it.second }.trim()
        } else {
            trimmed
        }

        val entity = CachedLyricsEntity(
            songId = songId,
            plainLyrics = plainLyrics,
            syncedLyrics = syncedLyrics,
            trackName = trackName,
            artistName = artistName,
            cachedAt = System.currentTimeMillis()
        )
        try {
            cachedLyricsDao.insertLyrics(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom lyrics for $songId", e)
        }
        LyricsResult(
            plainLyrics = plainLyrics,
            syncedLyrics = syncedLyrics,
            trackName = trackName,
            artistName = artistName,
            sourceProvider = "Custom / Local",
            richLines = syncedLyrics?.let { parseRichSyncedLyrics(it) } ?: emptyList()
        )
    }

    suspend fun deleteLyrics(songId: String) = withContext(Dispatchers.IO) {
        try {
            cachedLyricsDao.deleteLyricsForSong(songId)
            val cleanId = songId.removePrefix("online://").removePrefix("spotify:track:").removePrefix("spotify:").trim()
            if (cleanId.isNotBlank()) {
                File(File(context.filesDir, "lyrics"), "$cleanId.lrc").takeIf { it.exists() }?.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete lyrics for $songId", e)
        }
    }

    private suspend fun fetchLyricsFromLrcLib(
        trackName: String,
        artistName: String,
        durationSeconds: Int = 0,
        albumName: String? = null
    ): LyricsResult? {
        val cleanArtist = cleanArtistName(artistName)
        val cleanTrack = cleanTrackTitle(trackName, cleanArtist)
        val coreTrack = cleanTrack.split(" - ", " – ", " — ", ":").first().trim().ifBlank { cleanTrack }
        val validAlbum = albumName?.takeIf {
            !it.equals("YouTube Music", ignoreCase = true) &&
                !it.equals("Online", ignoreCase = true) &&
                !it.equals("Recommended", ignoreCase = true) &&
                !it.equals("Downloads", ignoreCase = true) &&
                !it.equals("Unknown Album", ignoreCase = true)
        }

        if (cleanTrack.isNotBlank() && cleanArtist.isNotBlank() && durationSeconds > 0) {
            executeGetRequest(cleanTrack, cleanArtist, validAlbum, durationSeconds)?.let { return it }
        }
        if (cleanTrack.isNotBlank() && cleanArtist.isNotBlank()) {
            executeGetRequest(cleanTrack, cleanArtist, validAlbum, 0)?.let { return it }
            executeSearchWithParams(cleanTrack, cleanArtist, durationSeconds)?.let { return it }
        }
        val query = listOf(cleanArtist, coreTrack).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isNotBlank()) {
            executeSearchQuery(query, coreTrack, cleanArtist, durationSeconds)?.let { return it }
        }
        return null
    }

    private fun executeGetRequest(trackName: String, artistName: String, albumName: String?, durationSeconds: Int): LyricsResult? {
        try {
            val params = mutableListOf("track_name=${encode(trackName)}", "artist_name=${encode(artistName)}")
            if (!albumName.isNullOrBlank()) params.add("album_name=${encode(albumName)}")
            if (durationSeconds > 0) params.add("duration=$durationSeconds")
            val url = "$LRCLIB_BASE_URL/get?${params.joinToString("&")}"
            val request = Request.Builder().url(url).addHeader("User-Agent", USER_AGENT).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    val json = JSONObject(body)
                    val plain = json.optString("plainLyrics", "").takeIf { it.isNotBlank() }
                    val synced = json.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
                    if (plain != null || synced != null) {
                        return LyricsResult(
                            plainLyrics = plain,
                            syncedLyrics = synced,
                            trackName = json.optString("trackName", trackName),
                            artistName = json.optString("artistName", artistName),
                            sourceProvider = "LRCLIB",
                            richLines = synced?.let { parseRichSyncedLyrics(it) } ?: emptyList()
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun executeSearchWithParams(trackName: String, artistName: String, targetDurationSec: Int): LyricsResult? {
        try {
            val url = "$LRCLIB_BASE_URL/search?track_name=${encode(trackName)}&artist_name=${encode(artistName)}"
            val request = Request.Builder().url(url).addHeader("User-Agent", USER_AGENT).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    return pickBestCandidate(JSONArray(body), trackName, artistName, targetDurationSec)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun executeSearchQuery(query: String, targetTrack: String, targetArtist: String, targetDurationSec: Int): LyricsResult? {
        try {
            val url = "$LRCLIB_BASE_URL/search?q=${encode(query)}"
            val request = Request.Builder().url(url).addHeader("User-Agent", USER_AGENT).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    return pickBestCandidate(JSONArray(body), targetTrack, targetArtist, targetDurationSec)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun pickBestCandidate(array: JSONArray, targetTrack: String, targetArtist: String, targetDurationSec: Int): LyricsResult? {
        if (array.length() == 0) return null
        var bestCandidate: LyricsResult? = null
        var highestScore = -1.0
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val plain = item.optString("plainLyrics", "").takeIf { it.isNotBlank() }
            val synced = item.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
            if (plain == null && synced == null) continue
            val candTrack = item.optString("trackName", item.optString("name", ""))
            val candArtist = item.optString("artistName", "")
            val candDuration = item.optDouble("duration", 0.0).toInt()
            var score = 0.0
            val titleSim = if (targetTrack.isNotBlank()) normalizedSimilarity(targetTrack, candTrack) else 0.5
            val artistSim = if (targetArtist.isNotBlank()) normalizedSimilarity(targetArtist, candArtist) else 0.5
            score += (titleSim * 0.5) + (artistSim * 0.3)
            if (synced != null) score += 0.15
            if (targetDurationSec > 0 && candDuration > 0 && abs(targetDurationSec - candDuration) <= 8) score += 0.10
            if (score > highestScore && score >= 0.45) {
                highestScore = score
                bestCandidate = LyricsResult(
                    plainLyrics = plain,
                    syncedLyrics = synced,
                    trackName = candTrack.ifBlank { targetTrack },
                    artistName = candArtist.ifBlank { targetArtist },
                    sourceProvider = "LRCLIB",
                    richLines = synced?.let { parseRichSyncedLyrics(it) } ?: emptyList()
                )
            }
        }
        return bestCandidate
    }

    fun cleanArtistName(raw: String): String {
        if (raw.isBlank()) return ""
        val labelRegex = Regex(
            """(?i)^(t-series|sony music.*|zee music.*|yrf|saregama.*|tips official.*|speed records.*|desi melodies.*|universal music.*|warner music.*|various artists|unknown artist)$"""
        )
        val primary = raw
            .replace(Regex("""(?i)\s*-\s*Topic\b"""), "")
            .replace(Regex("""(?i)VEVO\b"""), "")
            .replace(Regex("""(?i)\bOfficial\b"""), "")
            .split(",", "&", " feat.", " ft.", " x ", " X ")
            .firstOrNull()
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim() ?: ""
        return if (labelRegex.matches(primary)) "" else primary
    }

    fun cleanTrackTitle(raw: String, cleanArtist: String): String {
        if (raw.isBlank()) return ""
        var title = raw.replace(BRACKET_NOISE_REGEX, " ").split('|', '•', '/').first()
        title = title.replace(KEYWORD_NOISE_REGEX, " ")
        for (sep in listOf(" - ", " – ", " — ")) {
            if (title.contains(sep)) {
                val parts = title.split(sep, limit = 2)
                val prefix = parts[0].trim().lowercase()
                val cleanA = cleanArtist.lowercase()
                if (cleanA.isNotBlank() && (prefix == cleanA || cleanA.contains(prefix) || prefix.contains(cleanA))) {
                    title = parts[1]
                    break
                }
            }
        }
        return title.replace(FEAT_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun parseSyncedLyrics(lrc: String): List<Pair<Long, String>> {
        return parseRichSyncedLyrics(lrc).map { it.timeMs to it.text }
    }

    fun parseRichSyncedLyrics(lrc: String): List<RichLyricLine> {
        var pendingSection: String? = null
        val results = mutableListOf<RichLyricLine>()

        for (rawLine in lrc.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) continue

            if (SECTION_HEADER_REGEX.matches(trimmed)) {
                pendingSection = trimmed.removePrefix("[").removeSuffix("]").trim()
                continue
            }

            val match = LRC_LINE_REGEX.matchEntire(trimmed) ?: continue
            val mins = match.groupValues[1].toLongOrNull() ?: continue
            val secs = match.groupValues[2].toLongOrNull() ?: continue
            val fracStr = match.groupValues[3]
            val fracMs = when (fracStr.length) {
                1 -> fracStr.toLong() * 100
                2 -> fracStr.toLong() * 10
                3 -> fracStr.toLong()
                else -> 0L
            }
            val lineStartMs = (mins * 60 + secs) * 1000 + fracMs
            val rawBody = match.groupValues[4].trim()

            if (SECTION_HEADER_REGEX.matches(rawBody)) {
                pendingSection = rawBody.removePrefix("[").removeSuffix("]").trim()
                continue
            }

            val wordMatches = WORD_TAG_REGEX.findAll(rawBody).toList()
            val words = mutableListOf<WordTimestamp>()
            val cleanText: String

            if (wordMatches.isNotEmpty()) {
                val sb = StringBuilder()
                for (i in wordMatches.indices) {
                    val wm = wordMatches[i]
                    val wMin = wm.groupValues[1].toLongOrNull() ?: 0L
                    val wSec = wm.groupValues[2].toLongOrNull() ?: 0L
                    val wFracStr = wm.groupValues[3]
                    val wFrac = when (wFracStr.length) {
                        1 -> wFracStr.toLong() * 100
                        2 -> wFracStr.toLong() * 10
                        3 -> wFracStr.toLong()
                        else -> 0L
                    }
                    val wStart = (wMin * 60 + wSec) * 1000 + wFrac
                    val wNextStart = if (i + 1 < wordMatches.size) {
                        val nw = wordMatches[i + 1]
                        val nMin = nw.groupValues[1].toLongOrNull() ?: 0L
                        val nSec = nw.groupValues[2].toLongOrNull() ?: 0L
                        (nMin * 60 + nSec) * 1000
                    } else wStart + 450L
                    val wStr = wm.groupValues[4].trim()
                    if (wStr.isNotEmpty()) {
                        words.add(WordTimestamp(wStr, wStart, wNextStart.coerceAtLeast(wStart + 120L)))
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(wStr)
                    }
                }
                cleanText = sb.toString()
            } else {
                cleanText = rawBody
            }

            results.add(
                RichLyricLine(
                    timeMs = lineStartMs,
                    text = cleanText,
                    words = words,
                    sectionTag = pendingSection
                )
            )
            pendingSection = null
        }
        return results.sortedBy { it.timeMs }
    }

    private fun normalizedSimilarity(s1: String, s2: String): Double {
        val a = s1.lowercase().trim()
        val b = s2.lowercase().trim()
        if (a == b) return 1.0
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return 1.0
        val distance = levenshteinDistance(a, b)
        return 1.0 - (distance.toDouble() / maxLength)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        var dp = IntArray(len2 + 1) { it }
        var nextDp = IntArray(len2 + 1)
        for (i in 1..len1) {
            nextDp[0] = i
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                nextDp[j] = minOf(dp[j] + 1, nextDp[j - 1] + 1, dp[j - 1] + cost)
            }
            val temp = dp
            dp = nextDp
            nextDp = temp
        }
        return dp[len2]
    }
}
