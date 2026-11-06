package com.example.myplayer.playback

import android.content.Intent
import androidx.media3.common.Player
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFeatureManagers internal constructor(
    private val okHttpClient: OkHttpClient?,
    @Suppress("UNUSED_PARAMETER") isTest: Boolean
) {
    // DI parity secondary constructor (see SettingsDataStore/AudioPlaybackStore); the
    // no-arg constructor lets desktop code build this without any DI graph.
    @Inject
    constructor(okHttpClient: OkHttpClient) : this(okHttpClient, false)

    constructor() : this(null, true)

    private var crossfadeJob: Job? = null

    /**
     * 1. SYSTEM AUDIO EFFECTS (Let Dolby / equalizer apps process MyPlayer audio)
     */
    fun toggleSystemAudioEffects(audioSessionId: Int, enable: Boolean) {
        // shortcut: desktop has no audiofx control-session broadcast (Dolby/EQ apps are Android platform components); kept as a no-op so callers stay Android-identical.
    }

    /**
     * 2. CROSSFADE ENGINE (Smoothly ramps volume down at track end and ramps up on next track)
     */
    fun attachCrossfadeMonitor(
        scope: CoroutineScope,
        player: Player,
        isEnabled: () -> Boolean,
        crossfadeDurationSec: Int = 6
    ) {
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(250L)
                if (!isEnabled() || !player.isPlaying || !player.hasNextMediaItem()) {
                    if (player.volume < 1f && !isEnabled()) player.volume = 1f
                    continue
                }
                val duration = player.duration
                val position = player.currentPosition
                if (duration > 15_000L && position > 0L) {
                    val remainingMs = duration - position
                    val fadeWindowMs = crossfadeDurationSec * 1000L
                    if (remainingMs in 1..fadeWindowMs) {
                        // Ramp down smoothly
                        val fraction = (remainingMs.toFloat() / fadeWindowMs).coerceIn(0.05f, 1.0f)
                        player.volume = fraction
                        if (remainingMs <= 350L) {
                            player.seekToNextMediaItem()
                            // Ramp up new track
                            for (step in 1..10) {
                                player.volume = step / 10f
                                delay(120L)
                            }
                        }
                    } else if (player.volume < 1f) {
                        player.volume = 1f
                    }
                }
            }
        }
    }

    /**
     * 3. DOWNLOAD SYNCED LYRICS (.lrc companion file from LRCLIB next to downloaded audio)
     */
    suspend fun downloadLrcCompanionFile(
        trackTitle: String,
        artistName: String,
        durationSeconds: Long,
        targetAudioFile: File
    ): File? = withContext(Dispatchers.IO) {
        val client = okHttpClient ?: return@withContext null
        runCatching {
            val t = URLEncoder.encode(trackTitle, "UTF-8")
            val a = URLEncoder.encode(artistName, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$t&artist_name=$a&duration=$durationSeconds"
            val req = Request.Builder().url(url).header("User-Agent", "MyPlayer/4.4").build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                    ?: json.optString("plainLyrics").takeIf { it.isNotBlank() }
                    ?: return@withContext null

                val lrcFile = File(targetAudioFile.parentFile, "${targetAudioFile.nameWithoutExtension}.lrc")
                lrcFile.writeText(syncedLyrics)
                lrcFile
            }
        }.getOrNull()
    }

    /**
     * 4. BACKGROUND PLAYBACK (Check & Request Battery Optimization Exemption)
     */
    fun isBatteryUnrestricted(): Boolean {
        // shortcut: desktop has no battery-optimization/Doze regime; background audio is never throttled.
        return true
    }

    fun buildBatteryExemptionIntent(): Intent {
        // shortcut: desktop has no battery-optimization dialog; the intent is inert so ported UI call sites stay Android-identical.
        return Intent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
