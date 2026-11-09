package com.example.myplayer.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
     * 2. APPLE MUSIC STYLE EQUAL-POWER S-CURVE CROSSFADE ENGINE
     * Works seamlessly across BOTH local multi-item queues AND online 1-item queues!
     * Uses equal-power cosine/sine loudness curves (cos(t * PI/2) fade-out, sin(t * PI/2) fade-in)
     * so perceived loudness never dips in the middle of the transition.
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
                delay(80L)
                val xfadeActive = isEnabled() || MyPlayerAudioFxDspBridge.crossfadeEnabled
                if (!xfadeActive) {
                    if (player.volume < 1f) player.volume = 1f
                    isRampingInNewTrack = false
                    continue
                }

                if (!player.isPlaying || player.repeatMode == Player.REPEAT_MODE_ONE) {
                    continue
                }

                val duration = player.duration
                val position = player.currentPosition
                val currentMediaId = player.currentMediaItem?.mediaId ?: ""

                if (duration > 12_000L && position > 0L) {
                    val fadeSec = crossfadeSecondsProvider().coerceIn(1f, 12f)
                    val fadeWindowMs = (fadeSec * 1000f).toLong().coerceIn(1000L, (duration / 2).coerceAtLeast(1500L))
                    val remainingMs = duration - position

                    if (remainingMs in 1..fadeWindowMs) {
                        // Apple Music Equal-Power Cosine Fade-Out: progress goes 0.0 (start of fade) -> 1.0 (end of track)
                        val progress = (1f - (remainingMs.toFloat() / fadeWindowMs.toFloat())).coerceIn(0f, 1f)
                        val equalPowerOut = cos(progress * (PI / 2.0)).toFloat().coerceIn(0.04f, 1.0f)
                        player.volume = equalPowerOut

                        // Trigger next track smoothly right before track end (works for BOTH local and online queues!)
                        if (remainingMs <= 320L && lastTriggeredMediaId != currentMediaId) {
                            lastTriggeredMediaId = currentMediaId
                            isRampingInNewTrack = true

                            if (player.mediaItemCount > 1 && player.hasNextMediaItem()) {
                                player.seekToNextMediaItem()
                            } else if (onTriggerNextTrack != null) {
                                onTriggerNextTrack.invoke()
                            }

                            // Apple Music Equal-Power Sine Fade-In on the incoming track
                            val rampInSteps = 18
                            val stepDelayMs = (fadeWindowMs / (rampInSteps * 2)).coerceIn(45L, 140L)
                            for (step in 1..rampInSteps) {
                                if (!isActive) break
                                val inProgress = step.toFloat() / rampInSteps.toFloat()
                                val equalPowerIn = sin(inProgress * (PI / 2.0)).toFloat().coerceIn(0.06f, 1.0f)
                                player.volume = equalPowerIn
                                delay(stepDelayMs)
                            }
                            player.volume = 1.0f
                            isRampingInNewTrack = false
                        }
                    } else if (!isRampingInNewTrack && player.volume < 1f && position < duration - fadeWindowMs) {
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
            val req = Request.Builder().url(url).header("User-Agent", "MyPlayer/6.2").build()

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