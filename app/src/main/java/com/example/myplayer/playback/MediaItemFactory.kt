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

        val uri = if (path.startsWith("/") || path.matches(Regex("^[a-zA-Z]:\\\\.*"))) {
            Uri.fromFile(File(path))
        } else {
            Uri.parse(path)
        }

        val resolvedArtUri = when {
            !albumArt.isNullOrBlank() && (albumArt.startsWith("/") || albumArt.matches(Regex("^[a-zA-Z]:\\\\.*"))) ->
                Uri.fromFile(File(albumArt))
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
