package com.example.myplayer.ui.screens.nowplaying

/**
 * Uniform representation for songs displayed in the NowPlaying screen side list
 * (supports Recommendations, Downloaded Songs, and Playlist tracks).
 */
data class SideTrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val albumArt: String?,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val originalItem: Any? = null
)
