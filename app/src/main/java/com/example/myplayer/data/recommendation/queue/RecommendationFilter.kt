package com.example.myplayer.data.recommendation.queue

import com.example.myplayer.data.recommendation.model.RecommendationSeed
import com.example.myplayer.data.recommendation.model.RecommendationSong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationFilter @Inject constructor(
    private val history: RecommendationHistory,
    private val recentPlaybackWindow: RecentPlaybackWindow,
    private val metrics: QueueMetrics
) {
    fun filter(
        candidates: List<RecommendationSong>,
        seed: RecommendationSeed?,
        currentlyQueuedIds: Set<String>
    ): List<RecommendationSong> {
        val filtered = mutableListOf<RecommendationSong>()
        val seenIds = mutableSetOf<String>()
        val seedVideoId = seed?.songId?.removePrefix("online://").orEmpty()

        for (song in candidates) {
            val normalizedId = song.videoId.removePrefix("online://")
            val onlineId = "online://$normalizedId"

            // Duplicate within candidates
            if (!seenIds.add(normalizedId)) {
                metrics.recordDuplicateFiltered()
                continue
            }

            // Already queued
            if (currentlyQueuedIds.contains(song.videoId) || currentlyQueuedIds.contains(normalizedId) || currentlyQueuedIds.contains(onlineId)) {
                metrics.recordDuplicateFiltered()
                continue
            }

            // Current seed song
            if (seedVideoId.isNotBlank() && (normalizedId == seedVideoId || song.videoId == seedVideoId || song.videoId == "online://$seedVideoId")) {
                metrics.recordRejected()
                continue
            }

            // Recently played in this continuous session
            if (recentPlaybackWindow.contains(normalizedId) || recentPlaybackWindow.contains(song.videoId) || recentPlaybackWindow.contains(onlineId)) {
                metrics.recordRejected()
                continue
            }

            // Previously served
            if (history.hasBeenServed(normalizedId) || history.hasBeenServed(song.videoId) || history.hasBeenServed(onlineId)) {
                metrics.recordRejected()
                continue
            }

            // Previously rejected by user
            if (history.hasBeenRejected(normalizedId) || history.hasBeenRejected(song.videoId) || history.hasBeenRejected(onlineId)) {
                metrics.recordRejected()
                continue
            }

            filtered.add(song)
        }

        return filtered
    }
}
