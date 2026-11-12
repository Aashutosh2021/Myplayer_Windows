package com.example.myplayer.data.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.online.model.OnlineSong
import com.example.myplayer.data.repository.DownloadRepository
import com.example.myplayer.di.AppContainer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One downloaded song that needs its audio re-fetched after a restore. */
@Serializable
data class RedownloadItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val durationMs: Long,
    val localPath: String
)

/**
 * Sequential redownload queue for restored downloads on both Android and Windows Desktop.
 * On Windows Desktop, [enqueue] and [resumeIfPending] automatically launch a background
 * coroutine loop using [AppContainer] dependencies so restored backups re-download missing
 * songs sequentially even without Android WorkManager.
 */
@HiltWorker
class RedownloadQueueWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadRepository: DownloadRepository,
    private val innertubeApi: InnertubeApi
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        processNextItem(appContext, downloadRepository, innertubeApi)
        return Result.success()
    }

    companion object {
        private const val TAG = "RedownloadQueueWorker"
        private const val WORK_NAME = "com.example.myplayer.REDOWNLOAD_QUEUE"
        private val desktopQueueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var activeDesktopJob: Job? = null

        private fun queueFile(context: Context) = File(context.filesDir, "redownload_queue.json")

        private fun readQueue(context: Context): List<RedownloadItem> = runCatching {
            val file = queueFile(context)
            if (!file.exists()) return emptyList()
            Json.decodeFromString<List<RedownloadItem>>(file.readText())
        }.getOrDefault(emptyList())

        private fun writeQueue(context: Context, items: List<RedownloadItem>) = runCatching {
            val file = queueFile(context)
            if (items.isEmpty()) file.delete() else file.writeText(Json.encodeToString(items))
        }

        private suspend fun processNextItem(
            context: Context,
            downloadRepository: DownloadRepository,
            innertubeApi: InnertubeApi
        ): Boolean {
            val pending = readQueue(context).ifEmpty { return false }
            val todo = pending.filter { item ->
                val candidate = BackupRestoreManager.resolveLocalPath(
                    context, item.localPath, "${item.title}.m4a"
                )
                !BackupRestoreManager.checkFileExists(context, candidate)
            }
            if (todo.isEmpty()) {
                writeQueue(context, emptyList())
                Log.i(TAG, "Redownload queue complete — nothing pending")
                return false
            }

            val current = todo.first()
            val remaining = todo.drop(1)

            try {
                val streamUrl = innertubeApi.getStreamUrl(current.videoId)
                if (!streamUrl.isNullOrBlank()) {
                    val started = downloadRepository.startDownload(
                        OnlineSong(
                            videoId = current.videoId,
                            title = current.title,
                            artist = current.artist,
                            thumbnailUrl = current.thumbnailUrl,
                            durationMs = current.durationMs,
                            streamUrl = streamUrl
                        )
                    )
                    if (started) {
                        writeQueue(context, remaining)
                        // Wait until DownloadRepository completes this song (up to 5 minutes)
                        var waitedMs = 0L
                        while (downloadRepository.isDownloading(current.videoId) && waitedMs < 300_000L) {
                            delay(500L)
                            waitedMs += 500L
                        }
                        Log.i(TAG, "Redownloaded '${current.title}' — ${remaining.size} left in queue")
                        return remaining.isNotEmpty()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Redownload failed for '${current.title}' — continuing with next", e)
            }

            return true
        }

        private fun startDesktopQueueLoop(context: Context) {
            if (activeDesktopJob?.isActive == true) return
            activeDesktopJob = desktopQueueScope.launch {
                val downloadRepo = AppContainer.resolveDependency(DownloadRepository::class.java) as? DownloadRepository
                    ?: return@launch
                val innertube = AppContainer.resolveDependency(InnertubeApi::class.java) as? InnertubeApi
                    ?: return@launch
                while (processNextItem(context, downloadRepo, innertube)) {
                    delay(400L)
                }
            }
        }

        /** Starts a fresh sequential redownload queue (replaces any running one). */
        suspend fun enqueue(context: Context, items: List<RedownloadItem>) = withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            val file = queueFile(context)
            file.writeText(Json.encodeToString(items))
            Log.i(TAG, "Queued ${items.size} songs for sequential redownload")
            startDesktopQueueLoop(context)
        }

        /** True while a redownload queue file is waiting to be processed. */
        fun isQueuePending(context: Context): Boolean =
            queueFile(context).let { it.exists() && it.length() > 0 }

        /** Revives an interrupted queue on app start. */
        fun resumeIfPending(context: Context) {
            runCatching {
                if (!isQueuePending(context)) return@runCatching
                startDesktopQueueLoop(context)
            }
        }
    }
}
