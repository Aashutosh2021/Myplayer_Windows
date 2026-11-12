package com.example.myplayer.data.recommendation.engine

import com.example.myplayer.data.recommendation.api.RecommendationSource
import com.example.myplayer.data.recommendation.logging.RecommendationLogger
import com.example.myplayer.data.recommendation.logging.RecommendationMetrics
import com.example.myplayer.data.recommendation.mapper.RecommendationMapper
import com.example.myplayer.data.recommendation.model.RecommendationResult
import com.example.myplayer.data.recommendation.model.RecommendationSeed
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

@Singleton
class RecommendationEngine @Inject constructor(
    private val source: RecommendationSource,
    private val mapper: RecommendationMapper,
    private val logger: RecommendationLogger,
    private val metrics: RecommendationMetrics
) {
    suspend fun generateRecommendations(seed: RecommendationSeed): RecommendationResult {
        logger.log("Starting recommendation generation for seed: '${seed.songId}' via ${source.sourceName}")
        var result: RecommendationResult = RecommendationResult.Error("Not initialized", Exception("Not initialized"))
        
        val timeTaken = measureTimeMillis {
            try {
                // 1. Fetch raw candidates
                logger.log("Executing fetch from source: ${source.sourceName}")
                val rawCandidates = source.fetchRawRecommendations(seed)
                
                // 2. Map to Domain Models
                val mappedCandidates = rawCandidates.map { 
                    mapper.toRecommendationSong(it, source = source.sourceName)
                }

                // Return raw unranked/unfiltered mapped candidates
                result = RecommendationResult.Success(mappedCandidates)
                
            } catch (e: Exception) {
                logger.error("Exception during recommendation generation", e)
                metrics.recordFetchFailure()
                result = RecommendationResult.Error(e.message ?: "Unknown error", e)
            }
        }
        
        if (result is RecommendationResult.Success) {
            val songs = (result as RecommendationResult.Success).songs
            if (songs.isNotEmpty()) {
                metrics.recordFetchSuccess(timeTaken)
                logger.log("Completed generation in ${timeTaken}ms. Fetched: ${songs.size}")
            } else {
                metrics.recordEmptyFetch()
            }
        }
        
        return result
    }
}
