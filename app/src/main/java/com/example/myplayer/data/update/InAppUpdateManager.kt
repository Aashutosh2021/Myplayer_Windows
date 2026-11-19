package com.example.myplayer.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface InAppDownloadState {
    object Idle : InAppDownloadState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val versionName: String
    ) : InAppDownloadState
    data class ReadyToInstall(
        val installerFile: File,
        val versionName: String
    ) : InAppDownloadState
    data class Error(val message: String) : InAppDownloadState
}

/**
 * Windows Desktop In-App Updater & Native MSI/EXE Installer Manager.
 *
 * - Downloads the latest `.msi` / `.exe` / `.zip` directly inside MyPlayer Windows with real-time progress.
 * - Automatically logs the download event to the MyPlayer website's Firestore (`myplayer-660ee`) via
 *   [InAppDownloadTracker] so in-app updates increment the website's Total Downloads & Windows count.
 * - Launches the native Windows installer (`msiexec /i` for `.msi` or `Desktop.open()` for `.exe`).
 */
@Singleton
class InAppUpdateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes <= 0L -> "Official Installer"
            bytes >= 1_000_000L -> String.format("%.1f MB", bytes / (1024f * 1024f))
            else -> String.format("%.0f KB", bytes / 1024f)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null

    private val _downloadState = MutableStateFlow<InAppDownloadState>(InAppDownloadState.Idle)
    val downloadState: StateFlow<InAppDownloadState> = _downloadState.asStateFlow()

    init {
        autoCleanOldInstallers()
    }

    fun getUpdatesDir(): File {
        val userHome = System.getProperty("user.home").orEmpty()
        val downloadsDir = File(userHome, "Downloads")
        val base = if (downloadsDir.exists() && downloadsDir.canWrite()) {
            File(downloadsDir, "MyPlayer_Updates")
        } else {
            File(context.filesDir, "myplayer_updates")
        }
        return base.apply { if (!exists()) mkdirs() }
    }

    fun installerFileFor(update: UpdateInfo): File {
        val safeVer = update.tagName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val rawName = update.apkName?.takeIf {
            it.endsWith(".msi", true) || it.endsWith(".exe", true)
        } ?: "MyPlayer-$safeVer.msi"
        return File(getUpdatesDir(), rawName)
    }

    fun isDownloaded(update: UpdateInfo): Boolean {
        val target = installerFileFor(update)
        if (!target.exists() || target.length() <= 500_000L) return false
        return update.apkSizeBytes <= 0L || target.length() == update.apkSizeBytes
    }

    fun syncExistingDownloadState(update: UpdateInfo) {
        if (activeDownloadJob?.isActive == true) return
        if (isDownloaded(update)) {
            _downloadState.value = InAppDownloadState.ReadyToInstall(
                installerFile = installerFileFor(update),
                versionName = update.versionName
            )
        } else {
            _downloadState.value = InAppDownloadState.Idle
        }
    }

    /**
     * Launches the downloaded Windows `.msi` or `.exe` installer natively.
     */
    fun launchInstaller(installerFile: File, versionName: String = ""): Boolean {
        if (!installerFile.exists()) {
            _downloadState.value = InAppDownloadState.Error("Installer file not found. Click to download again.")
            return false
        }
        return try {
            val lower = installerFile.name.lowercase()
            when {
                lower.endsWith(".msi") -> {
                    ProcessBuilder("msiexec", "/i", installerFile.absolutePath).start()
                }
                lower.endsWith(".exe") -> {
                    ProcessBuilder(installerFile.absolutePath).start()
                }
                Desktop.isDesktopSupported() -> {
                    Desktop.getDesktop().open(installerFile)
                }
                else -> {
                    ProcessBuilder("cmd", "/c", "start", "", installerFile.absolutePath).start()
                }
            }
            _downloadState.value = InAppDownloadState.ReadyToInstall(installerFile, versionName)
            true
        } catch (e: Exception) {
            // Fallback to opening folder so user can double-click installer
            runCatching {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(installerFile.parentFile)
            }
            _downloadState.value = InAppDownloadState.Error(
                "Could not auto-launch installer (${e.message ?: "error"}). Saved in ${installerFile.parentFile?.absolutePath}"
            )
            false
        }
    }

    fun startDownloadAndInstall(update: UpdateInfo, autoInstallOnFinish: Boolean = true) {
        if (activeDownloadJob?.isActive == true) return

        val downloadUrl = update.apkUrl?.takeIf { it.isNotBlank() }
        val targetFile = installerFileFor(update)

        if (isDownloaded(update)) {
            _downloadState.value = InAppDownloadState.ReadyToInstall(targetFile, update.versionName)
            if (autoInstallOnFinish) launchInstaller(targetFile, update.versionName)
            return
        }

        if (downloadUrl == null) {
            _downloadState.value = InAppDownloadState.Error(
                "No direct Windows installer (.msi/.exe) attached to this release. Use 'Open Website' instead."
            )
            return
        }

        // Sync download count with MyPlayer website (Firestore myplayer-660ee)
        InAppDownloadTracker.trackInAppUpdateDownload(
            context = context,
            okHttpClient = okHttpClient,
            update = update,
            assetName = update.apkName ?: targetFile.name,
            downloadUrl = downloadUrl
        )

        activeDownloadJob = scope.launch {
            val isZip = downloadUrl.endsWith(".zip", ignoreCase = true)
            val tempFile = File(getUpdatesDir(), if (isZip) "temp_update.zip" else "${targetFile.name}.part")
            try {
                if (tempFile.exists()) tempFile.delete()
                _downloadState.value = InAppDownloadState.Downloading(
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = update.apkSizeBytes,
                    versionName = update.versionName
                )

                val req = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "MyPlayer-Desktop-InAppUpdater")
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Server returned HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    val totalBytes = update.apkSizeBytes.takeIf { it > 0L }
                        ?: body.contentLength().takeIf { it > 0L }
                        ?: -1L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                written += read
                                val progress = if (totalBytes > 0L) {
                                    (written.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                _downloadState.value = InAppDownloadState.Downloading(
                                    progress = progress,
                                    downloadedBytes = written,
                                    totalBytes = totalBytes,
                                    versionName = update.versionName
                                )
                            }
                            output.flush()
                        }
                    }
                }

                if (!isActive) return@launch

                if (isZip) {
                    var extracted = false
                    ZipInputStream(tempFile.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && (entry.name.endsWith(".msi", true) || entry.name.endsWith(".exe", true))) {
                                if (targetFile.exists()) targetFile.delete()
                                FileOutputStream(targetFile).use { fos -> zis.copyTo(fos) }
                                extracted = true
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                    tempFile.delete()
                    if (!extracted) throw IllegalStateException("No .msi or .exe installer found inside archive.")
                } else {
                    if (targetFile.exists()) targetFile.delete()
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                }

                withContext(Dispatchers.Main) {
                    _downloadState.value = InAppDownloadState.ReadyToInstall(targetFile, update.versionName)
                    if (autoInstallOnFinish) {
                        launchInstaller(targetFile, update.versionName)
                    }
                }
            } catch (_: CancellationException) {
                tempFile.delete()
                _downloadState.value = InAppDownloadState.Idle
            } catch (e: Exception) {
                tempFile.delete()
                _downloadState.value = InAppDownloadState.Error(
                    "Download failed: ${e.message ?: "Please check your internet connection and try again."}"
                )
            }
        }
    }

    fun cancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        _downloadState.value = InAppDownloadState.Idle
    }

    private fun autoCleanOldInstallers() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 5L * 24L * 60L * 60L * 1000L
            getUpdatesDir().listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }
}
