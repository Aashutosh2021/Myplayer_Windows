package com.example.myplayer.data.repository

import android.content.Context
import android.util.Log
import com.example.myplayer.data.local.dao.FolderDao
import com.example.myplayer.data.local.dao.SongDao
import com.example.myplayer.data.local.entity.FolderEntity
import com.example.myplayer.data.local.entity.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Singleton
class MediaScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val folderDao: FolderDao
) {
    private val supportedExtensions = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "wma")

    suspend fun scanAllFolders() = withContext(Dispatchers.IO) {
        val folders = folderDao.getFoldersSync()
        if (folders.isEmpty()) {
            // Default to user's standard Windows Music directory if no folders configured
            val userMusic = File(System.getProperty("user.home"), "Music")
            if (userMusic.exists() && userMusic.isDirectory) {
                val defaultFolder = FolderEntity(userMusic.absolutePath, "Music")
                folderDao.addFolder(defaultFolder)
                scanFolder(defaultFolder)
            }
        } else {
            folders.forEach { scanFolder(it) }
        }
    }

    suspend fun scanFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        val dir = File(folder.uri)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w("MediaScanner", "Folder does not exist or is not a directory: ${folder.uri}")
            return@withContext
        }

        val discoveredSongs = mutableListOf<SongEntity>()
        scanDirectoryRecursively(dir, discoveredSongs)

        if (discoveredSongs.isNotEmpty()) {
            songDao.insertSongs(discoveredSongs)
            Log.i("MediaScanner", "Scanned ${discoveredSongs.size} songs from ${folder.uri}")
        }
    }

    private fun scanDirectoryRecursively(dir: File, out: MutableList<SongEntity>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectoryRecursively(file, out)
            } else if (file.isFile && file.extension.lowercase() in supportedExtensions) {
                out.add(createSongEntityFromFile(file))
            }
        }
    }

    private fun createSongEntityFromFile(file: File): SongEntity {
        val fileNameWithoutExt = file.nameWithoutExtension
        val parts = fileNameWithoutExt.split(" - ", limit = 2)
        val (artist, title) = if (parts.size == 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            (file.parentFile?.name ?: "Unknown Artist") to fileNameWithoutExt
        }

        val album = file.parentFile?.name ?: "Unknown Album"
        val songId = generateIdForPath(file.absolutePath)
        val estimatedDuration = (file.length() / (128 * 1024 / 8)) * 1000 // Approximate duration for 128kbps audio

        return SongEntity(
            id = songId,
            title = title,
            artist = artist,
            album = album,
            duration = estimatedDuration.coerceAtLeast(30_000L),
            path = file.absolutePath,
            albumArt = null,
            dateAdded = file.lastModified(),
            playCount = 0,
            videoId = null
        )
    }

    private fun generateIdForPath(path: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(path.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun pickFolderDialog(): File? {
        var selectedFile: File? = null
        if (SwingUtilities.isEventDispatchThread()) {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Select Music Folder"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selectedFile = chooser.selectedFile
            }
        } else {
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Select Music Folder"
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    selectedFile = chooser.selectedFile
                }
            }
        }
        return selectedFile
    }
}
