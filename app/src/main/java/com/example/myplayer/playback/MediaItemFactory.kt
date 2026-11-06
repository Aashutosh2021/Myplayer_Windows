package com.example.myplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaItemFactory @Inject constructor() {
    @OptIn(UnstableApi::class)
    fun createMediaItem(
        songId: String,
        path: String,
        title: String,
        artist: String,
        albumArt: String?
    ): MediaItem {
        val cleanCacheKey = songId
            .removePrefix("online://")
            .removePrefix("spotify:track:")
            .removePrefix("spotify:")
            .trim()
            .takeIf { it.isNotBlank() }

        if (cleanCacheKey != null && (path.startsWith("http://") || path.startsWith("https://"))) {
            DesktopChunkStreamProxy.registerSongStream(cleanCacheKey, path)
        }

        val normalizedLocalPath = if (
            path.startsWith("/") ||
            path.startsWith("file:", ignoreCase = true) ||
            path.matches(Regex("^[a-zA-Z]:[\\\\/].*"))
        ) {
            Uri.normalizeToFilePath(path) ?: path
        } else {
            null
        }

        val uri = if (normalizedLocalPath != null) {
            Uri.fromFile(File(normalizedLocalPath))
        } else {
            Uri.parse(path)
        }

        val resolvedArtUri = when {
            !albumArt.isNullOrBlank() && (
                albumArt.startsWith("/") ||
                albumArt.startsWith("file:", ignoreCase = true) ||
                albumArt.matches(Regex("^[a-zA-Z]:[\\\\/].*"))
            ) -> {
                val cleanArtPath = Uri.normalizeToFilePath(albumArt) ?: albumArt
                Uri.fromFile(File(cleanArtPath))
            }
            !albumArt.isNullOrBlank() -> Uri.parse(albumArt)
            else -> null
        }

        return MediaItem.Builder()
            .setMediaId(songId)
            .setUri(uri)
            .apply {
                if (cleanCacheKey != null && (path.startsWith("http://") || path.startsWith("https://"))) {
                    setCustomCacheKey("yt_audio_$cleanCacheKey")
                }
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .apply {
                        if (resolvedArtUri != null) {
                            setArtworkUri(resolvedArtUri)
                        }
                    }
                    .build()
            )
            .build()
    }
}
