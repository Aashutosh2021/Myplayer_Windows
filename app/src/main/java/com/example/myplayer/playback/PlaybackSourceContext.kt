package com.example.myplayer.playback

/**
 * Context in which the active song was initiated.
 * Determines the source of songs displayed in the side track list of Now Playing.
 */
enum class PlaybackSourceContext {
    RECOMMENDED, // Single online song / search / home -> displays Up Next recommendations
    DOWNLOADS,   // Started from Downloads section -> displays all downloaded songs
    PLAYLIST,    // Started from a Playlist -> displays playlist songs
    QUEUE        // Generic queue
}
