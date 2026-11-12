package com.example.myplayer.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.myplayer.myplayerfx.DesktopMyPlayerDspPipeline
import com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(UnstableApi::class)
@Singleton
class AudioFeatureManagers @Inject constructor(
    private val context: Context = Context(),
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    constructor(okHttpClient: OkHttpClient) : this(Context(), okHttpClient)
    private var crossfadeJob: Job? = null

    /**
     * 1. SYSTEM AUDIO EFFECTS (Let Dolby / equalizer apps process MyPlayer audio)
     */
    fun toggleSystemAudioEffects(audioSessionId: Int, enable: Boolean) {
        // Desktop Windows: Audio FX handled natively via MasterAudioDspProcessor & DesktopAudioBackend
    }

    /**
     * 2. APPLE MUSIC STYLE DUAL-DECK EQUAL-POWER S-CURVE CROSSFADE ENGINE (Windows)
     * - Works seamlessly across BOTH local multi-item queues AND online 1-item queues!
     * - Hands off the outgoing VLC deck at the start of the crossfade window so Track A
     *   continues playing and fading out via cos(t * PI/2) while Track B starts immediately
     *   and ramps in via sin(t * PI/2) with 110Hz sub-bass mud-guard active!
     */
    fun attachCrossfadeMonitor(
        scope: CoroutineScope,
        player: Player,
        isEnabled: () -> Boolean,
        crossfadeDurationSec: Int = 5,
        crossfadeSecondsProvider: () -> Float = {
            if (MyPlayerAudioFxDspBridge.crossfadeEnabled) MyPlayerAudioFxDspBridge.crossfadeSeconds
            else crossfadeDurationSec.toFloat()
        },
        onTriggerNextTrack: (() -> Unit)? = null
    ) {
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch(Dispatchers.Main) {
            var lastTriggeredMediaId: String? = null
            var isRampingInNewTrack = false

            while (isActive) {
                delay(75L)
                val xfadeActive = isEnabled() || MyPlayerAudioFxDspBridge.crossfadeEnabled
                if (!xfadeActive) {
                    if (player.volume < 1f) player.volume = 1f
                    DesktopMyPlayerDspPipeline.crossfadeBassCutFactor = 0f
                    isRampingInNewTrack = false
                    continue
                }

                if (!player.isPlaying || player.repeatMode == Player.REPEAT_MODE_ONE) {
                    continue
                }

                val duration = player.duration
                val position = player.currentPosition
                val currentMediaId = player.currentMediaItem?.mediaId ?: ""

                if (duration > 10_000L && position > 1500L) {
                    val fadeSec = crossfadeSecondsProvider().coerceIn(1f, 12f)
                    val fadeWindowMs = (fadeSec * 1000f).toLong().coerceIn(1000L, (duration / 2).coerceAtLeast(1500L))
                    val remainingMs = duration - position

                    if (remainingMs in 350L..fadeWindowMs && lastTriggeredMediaId != currentMediaId) {
                        lastTriggeredMediaId = currentMediaId
                        isRampingInNewTrack = true

                        val actualOverlapMs = remainingMs.coerceIn(1000L, fadeWindowMs)
                        // Hand off outgoing track to secondary VLC deck so it keeps playing & fading out
                        val handedOff = DesktopAudioBackend.beginCrossfadeHandoff(actualOverlapMs)
                        if (!handedOff) {
                            // Single-deck fallback S-curve fade-out
                            val outSteps = 12
                            val outDelay = (actualOverlapMs / (outSteps * 2)).coerceIn(35L, 120L)
                            for (s in 1..outSteps) {
                                if (!isActive) break
                                val p = s.toFloat() / outSteps.toFloat()
                                player.volume = cos(p * (PI / 2.0)).toFloat().coerceIn(0.05f, 1.0f)
                                delay(outDelay)
                            }
                        }

                        player.volume = 0.04f
                        if (player.mediaItemCount > 1 && player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                        } else if (onTriggerNextTrack != null) {
                            onTriggerNextTrack.invoke()
                        }

                        // Wait for incoming track to start playing (handles online YouTube URL resolution)
                        var waitedMs = 0L
                        while (isActive && waitedMs < 3500L) {
                            val newId = player.currentMediaItem?.mediaId ?: ""
                            if (newId != currentMediaId && player.isPlaying) break
                            delay(60L)
                            waitedMs += 60L
                        }

                        // Apple Music Equal-Power Sine Fade-In + 110Hz Sub-Bass Mud-Guard
                        val rampInSteps = 28
                        val stepDelayMs = (actualOverlapMs / rampInSteps).coerceIn(35L, 180L)
                        for (step in 1..rampInSteps) {
                            if (!isActive) break
                            val inProgress = step.toFloat() / rampInSteps.toFloat()
                            val equalPowerIn = sin(inProgress * (PI / 2.0)).toFloat().coerceIn(0.04f, 1.0f)
                            DesktopMyPlayerDspPipeline.crossfadeBassCutFactor =
                                (sin(inProgress * PI).toFloat() * 0.65f).coerceIn(0f, 0.75f)
                            player.volume = equalPowerIn
                            delay(stepDelayMs)
                        }
                        DesktopMyPlayerDspPipeline.crossfadeBassCutFactor = 0f
                        player.volume = 1.0f
                        isRampingInNewTrack = false
                    } else if (!isRampingInNewTrack && !DesktopAudioBackend.isCrossfading && player.volume < 1f && remainingMs > fadeWindowMs) {
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
        runCatching {
            val t = URLEncoder.encode(trackTitle, "UTF-8")
            val a = URLEncoder.encode(artistName, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$t&artist_name=$a&duration=$durationSeconds"
            val req = Request.Builder().url(url).header("User-Agent", "MyPlayer/6.5").build()

            okHttpClient.newCall(req).execute().use { resp ->
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
    fun isBatteryUnrestricted(): Boolean = true
    fun buildBatteryExemptionIntent(): Intent = Intent()
}