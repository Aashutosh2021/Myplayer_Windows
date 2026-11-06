package com.example.myplayer.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.myplayer.data.local.dao.CachedLyricsDao
import com.example.myplayer.data.local.dao.DownloadedSongDao
import com.example.myplayer.data.local.dao.SongDao
import com.example.myplayer.data.local.entity.DownloadedSongEntity
import com.example.myplayer.data.lyrics.LyricsRepository
import com.example.myplayer.data.online.model.OnlineSong
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadedSongDao: DownloadedSongDao,
    private val songDao: SongDao,
    private val cachedLyricsDao: CachedLyricsDao,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _manualProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val lyricsRepository by lazy { LyricsRepository(context, okHttpClient, cachedLyricsDao) }

    val downloadProgress: StateFlow<Map<String, Int>> = _manualProgress.asStateFlow()

    fun setDownloadProgress(videoId: String, progress: Int) {
        _manualProgress.update { it + (videoId to progress) }
    }

    fun removeDownload(videoId: String) {
        _manualProgress.update { it - videoId }
    }

    fun isDownloading(videoId: String): Boolean {
        return downloadProgress.value.containsKey(videoId)
    }

    fun getAllDownloads(): Flow<List<DownloadedSongEntity>> = downloadedSongDao.getAllDownloads()

    suspend fun isDownloaded(videoId: String): Boolean = withContext(Dispatchers.IO) {
        downloadedSongDao.existsById(videoId)
    }

    fun getDownloadedById(id: String): Flow<List<DownloadedSongEntity>> =
        downloadedSongDao.searchDownloads("")

    fun startDownload(song: OnlineSong): Boolean {
        val streamUrl = song.streamUrl
        if (streamUrl.isNullOrBlank()) {
            Log.e("DownloadRepository", "[DOWNLOAD] No stream URL for ${song.videoId}")
            return false
        }

        if (activeJobs.containsKey(song.videoId)) {
            Log.i("DownloadRepository", "[DOWNLOAD] Already downloading ${song.videoId}")
            return true
        }

        Log.i("DownloadRepository", "[DOWNLOAD] Starting desktop download for: ${song.title} (${song.videoId})")
        setDownloadProgress(song.videoId, 0)

        val job = scope.launch {
            var tempFileToClean: File? = null
            try {
                val musicDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir
                musicDir.mkdirs()

                val safeTitle = song.title
                    .replace(Regex("[^a-zA-Z0-9 _\\-]"), "")
                    .replace(Regex(" {2,}"), " ")
                    .trim()
                    .take(60)
                    .ifBlank { song.videoId }

                val finalFileName = "$safeTitle.m4a"
                val tmpFileName = "$safeTitle.tmp"
                val destFile = File(musicDir, finalFileName)
                val tempFile = File(musicDir, tmpFileName)
                tempFileToClean = tempFile

                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                val chunkSize = 2L * 1024 * 1024 // 2 MB chunks with retry support for slow internet

                var totalSize = -1L
                try {
                    val headReq = Request.Builder()
                        .url(streamUrl)
                        .addHeader("User-Agent", userAgent)
                        .addHeader("Range", "bytes=0-0")
                        .build()
                    okHttpClient.newCall(headReq).execute().use { response ->
                        val cr = response.header("Content-Range")
                        if (cr != null) {
                            totalSize = cr.substringAfterLast("/").toLongOrNull() ?: -1L
                        } else if (response.isSuccessful) {
                            totalSize = response.body?.contentLength() ?: -1L
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DownloadRepository", "Could not probe size for ${song.videoId}: ${e.message}")
                }

                tempFile.outputStream().use { out ->
                    var downloaded = 0L
                    if (totalSize > 0) {
                        while (downloaded < totalSize) {
                            if (!isActive) throw CancellationException("Download cancelled")
                            val endOffset = minOf(downloaded + chunkSize - 1, totalSize - 1)
                            var chunkSuccess = false
                            var attempt = 0
                            while (attempt < 4 && !chunkSuccess) {
                                try {
                                    val req = Request.Builder()
                                        .url(streamUrl)
                                        .addHeader("User-Agent", userAgent)
                                        .addHeader("Range", "bytes=$downloaded-$endOffset")
                                        .build()

                                    val bytesReadThisChunk = okHttpClient.newCall(req).execute().use { response ->
                                        if (response.code == 416) return@use -1L
                                        if (!response.isSuccessful && response.code != 206) {
                                            throw IOException("HTTP ${response.code}")
                                        }
                                        val body = response.body ?: throw IOException("Empty body")
                                        val stream = body.byteStream()
                                        val buf = ByteArray(16384)
                                        var chunkBytes = 0L
                                        while (true) {
                                            if (!isActive) throw CancellationException("Download cancelled")
                                            val r = stream.read(buf)
                                            if (r == -1) break
                                            out.write(buf, 0, r)
                                            chunkBytes += r
                                        }
                                        chunkBytes
                                    }

                                    if (bytesReadThisChunk <= 0L) {
                                        chunkSuccess = true
                                        downloaded = totalSize
                                    } else {
                                        downloaded += bytesReadThisChunk
                                        chunkSuccess = true
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    attempt++
                                    if (attempt >= 4) throw e
                                    delay(500L * attempt)
                                }
                            }
                            val prog = ((downloaded.toFloat() / totalSize) * 100).toInt().coerceIn(0, 99)
                            setDownloadProgress(song.videoId, prog)
                        }
                    } else {
                        val req = Request.Builder()
                            .url(streamUrl)
                            .addHeader("User-Agent", userAgent)
                            .build()
                        okHttpClient.newCall(req).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Empty body")
                            val stream = body.byteStream()
                            val len = body.contentLength()
                            val buf = ByteArray(16384)
                            while (true) {
                                if (!isActive) throw CancellationException("Download cancelled")
                                val r = stream.read(buf)
                                if (r == -1) break
                                out.write(buf, 0, r)
                                downloaded += r
                                if (len > 0) {
                                    val prog = ((downloaded.toFloat() / len) * 100).toInt().coerceIn(0, 99)
                                    setDownloadProgress(song.videoId, prog)
                                } else {
                                    setDownloadProgress(song.videoId, 50)
                                }
                            }
                        }
                    }
                }

                val fileSize = tempFile.length()
                if (fileSize <= 0L) {
                    throw IOException("Downloaded file is empty")
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }

                // 1. Download and persist HD Poster locally so it works 100% offline on Windows!
                val localPosterUri = downloadAndSaveLocalArtwork(
                    videoId = song.videoId,
                    thumbnailUrl = song.thumbnailUrl,
                    audioDestFile = destFile
                )
                val effectiveThumbnail = localPosterUri ?: song.thumbnailUrl

                val entity = DownloadedSongEntity(
                    id = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    thumbnailUrl = effectiveThumbnail,
                    durationMs = song.durationMs,
                    localPath = destFile.absolutePath,
                    fileSizeBytes = fileSize,
                    downloadedAt = System.currentTimeMillis()
                )
                downloadedSongDao.insert(entity)

                try {
                    songDao.getSongById(song.videoId)?.let {
                        songDao.updateSongPath(song.videoId, destFile.absolutePath)
                        if (localPosterUri != null) {
                            songDao.updateSongArtwork(song.videoId, localPosterUri)
                        }
                    }
                } catch (_: Exception) {}

                // 2. Automatically download and cache lyrics + write companion .lrc file next to .m4a!
                try {
                    val lyricsResult = lyricsRepository.fetchLyrics(
                        songId = song.videoId,
                        trackName = song.title,
                        artistName = song.artist,
                        durationSeconds = (song.durationMs / 1000L).toInt(),
                        localPath = destFile.absolutePath
                    )
                    if (lyricsResult != null) {
                        val lrcText = lyricsResult.syncedLyrics?.takeIf { it.isNotBlank() }
                            ?: lyricsResult.plainLyrics?.takeIf { it.isNotBlank() }
                        if (!lrcText.isNullOrBlank()) {
                            val lrcFile = File(destFile.parentFile, "${destFile.nameWithoutExtension}.lrc")
                            lrcFile.writeText(lrcText, Charsets.UTF_8)
                            Log.i("DownloadRepository", "[DOWNLOAD] Saved offline companion .lrc: ${lrcFile.absolutePath}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DownloadRepository", "Could not auto-cache lyrics for ${song.videoId}: ${e.message}")
                }

                Log.i("DownloadRepository", "[DOWNLOAD] Completed successfully: ${song.title}")
            } catch (e: Exception) {
                Log.e("DownloadRepository", "[DOWNLOAD] Failed for ${song.videoId}: ${e.message}")
            } finally {
                activeJobs.remove(song.videoId)
                removeDownload(song.videoId)
                try {
                    tempFileToClean?.let {
                        if (it.exists()) it.delete()
                    }
                } catch (_: Exception) {}
            }
        }
        activeJobs[song.videoId] = job
        return true
    }

    /**
     * Downloads the song's poster image and saves it to both `~/.myplayer/files/artwork/<videoId>.jpg`
     * and `<audioFileWithoutExtension>.jpg` so posters render immediately when internet is off.
     */
    private fun downloadAndSaveLocalArtwork(
        videoId: String,
        thumbnailUrl: String,
        audioDestFile: File
    ): String? {
        val candidateUrls = buildList {
            if (videoId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                add("https://i.ytimg.com/vi/$videoId/hq720.jpg")
                add("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
            }
            if (thumbnailUrl.startsWith("http")) {
                add(thumbnailUrl)
            }
        }.distinct()

        for (url in candidateUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.size > 512) {
                            val artworkDir = File(context.filesDir, "artwork").apply { mkdirs() }
                            val artFile = File(artworkDir, "$videoId.jpg")
                            artFile.writeBytes(bytes)
                            audioDestFile.parentFile?.let { parent ->
                                val companionJpg = File(parent, "${audioDestFile.nameWithoutExtension}.jpg")
                                runCatching { companionJpg.writeBytes(bytes) }
                            }
                            return Uri.fromFile(artFile).toString()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun deleteDownload(entity: DownloadedSongEntity) {
        activeJobs.remove(entity.id)?.cancel()
        removeDownload(entity.id)
        try {
            if (entity.localPath.startsWith("content://")) {
                val uri = Uri.parse(entity.localPath)
                val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                doc?.delete()
            } else {
                val file = File(entity.localPath)
                file.parentFile?.let { parent ->
                    File(parent, "${file.nameWithoutExtension}.lrc").delete()
                    File(parent, "${file.nameWithoutExtension}.jpg").delete()
                }
                file.delete()
            }
            File(File(context.filesDir, "artwork"), "${entity.id}.jpg").delete()
        } catch (e: Exception) {
            Log.e("DownloadRepository", "Could not delete file", e)
        }

        try {
            cachedLyricsDao.deleteLyricsForSong(entity.id)
        } catch (e: Exception) {
            Log.e("DownloadRepository", "Could not delete cached lyrics for ${entity.id}", e)
        }

        downloadedSongDao.deleteById(entity.id)
        Log.i("DownloadRepository", "Deleted download record for ${entity.id}")
    }

    suspend fun deleteDownloadById(videoId: String) = withContext(Dispatchers.IO) {
        activeJobs.remove(videoId)?.cancel()
        removeDownload(videoId)

        val entity = downloadedSongDao.getById(videoId)
        if (entity != null) {
            deleteDownload(entity)
        } else {
            downloadedSongDao.deleteById(videoId)
            try {
                cachedLyricsDao.deleteLyricsForSong(videoId)
            } catch (e: Exception) {
                Log.e("DownloadRepository", "Could not delete cached lyrics for $videoId", e)
            }
        }
    }

    suspend fun insertDownload(song: DownloadedSongEntity) {
        downloadedSongDao.insert(song)
    }

    suspend fun performIntegrityCheck() {
        val downloads = withContext(Dispatchers.IO) {
            downloadedSongDao.getAllDownloadsSync()
        }

        withContext(Dispatchers.IO) {
            val toDelete = mutableListOf<String>()
            val seenFilePaths = mutableSetOf<String>()

            for (download in downloads) {
                val path = download.localPath
                if (path in seenFilePaths) {
                    toDelete.add(download.id)
                    continue
                }
                seenFilePaths.add(path)

                if (activeJobs.containsKey(download.id) || isDownloading(download.id)) {
                    continue
                }

                val fileExists = try {
                    if (path.startsWith("content://") || path.startsWith("file://")) {
                        val uri = Uri.parse(path)
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                            it.length > 0
                        } ?: false
                    } else {
                        val file = File(path)
                        file.exists() && file.length() > 0
                    }
                } catch (e: Exception) {
                    false
                }

                if (!fileExists) {
                    toDelete.add(download.id)
                }
            }

            if (toDelete.isNotEmpty()) {
                for (id in toDelete) {
                    downloadedSongDao.deleteById(id)
                }
                Log.i("DownloadRepository", "Cleaned up ${toDelete.size} invalid download records.")
            }
        }
    }

    fun searchDownloads(query: String): Flow<List<DownloadedSongEntity>> =
        downloadedSongDao.searchDownloads(query)
}
