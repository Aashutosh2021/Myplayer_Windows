package com.example.myplayer.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audioPlaybackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_playback_master_prefs"
)

enum class AudioQualityTier(
    val title: String,
    val badge: String,
    val subtitle: String,
    val qobuzFormatId: Int?
) {
    DOLBY_ATMOS("Dolby Atmos", "ATMOS", "Spatial Immersive Audio • Master Audio", 28),
    HI_RES_MAX("Hi-Res Audio (Max)", "24-BIT / 192k", "Up to 24-bit / 192 kHz • Lossless Studio FLAC", 27),
    HI_RES_96("Hi-Res Audio", "24-BIT / 96k", "24-bit / 96 kHz • Lossless Studio FLAC", 7),
    CD_LOSSLESS("CD Lossless", "16-BIT / 44.1k", "16-bit / 44.1 kHz • Lossless CD FLAC", 6),
    STANDARD_320("Standard Quality", "320 kbps", "320 kbps • MP3 / AAC", 5),
    DATA_SAVER("Data Saver", "96 kbps", "96 kbps • High Efficiency AAC", 4),
    YOUTUBE_MUSIC("YouTube Music", "YOUTUBE", "128-256 kbps • YouTube Music AAC / Opus stream", -1);

    val bitrateKbps: Int
        get() = when (this) {
            DATA_SAVER -> 96
            STANDARD_320 -> 320
            CD_LOSSLESS -> 1411
            HI_RES_96 -> 4608
            HI_RES_MAX -> 9216
            DOLBY_ATMOS -> 4608
            YOUTUBE_MUSIC -> 256
        }

    companion object {
        val LOW get() = DATA_SAVER
        val NORMAL get() = STANDARD_320
        val HIGH get() = CD_LOSSLESS
        val VERY_HIGH get() = STANDARD_320
        val LOSSLESS get() = CD_LOSSLESS
        val HI_RES_LOSSLESS get() = HI_RES_MAX
    }
}

enum class LoudnessMode(val id: String, val title: String, val description: String) {
    OFF("off", "Off", "Play tracks at their original level"),
    TRACK("track", "Track", "Match every track to -14 LUFS (needs ReplayGain tags)"),
    ALBUM("album", "Album", "Keep intentional album dynamics (falls back to track tags)");

    val targetLufs: Float
        get() = when (this) {
            OFF -> 0f
            TRACK -> -14f
            ALBUM -> -14f
        }
}

enum class SpatialMode(val title: String, val description: String) {
    SPEAKERS("Virtual 3D Speakers", "Simulates external stereo speakers ±30° in front of you"),
    CROSSFEED("BS2B Crossfeed", "Blends binaural channels to eliminate headphone fatigue")
}

enum class ClarityOutputPreset(
    val index: Int,
    val key: String,
    val title: String,
    val description: String,
    val trimsDb: FloatArray
) {
    REFERENCE(0, "reference", "Reference", "Unmodified shipping curve; every stage trim is 0 dB.", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    SPEAKER(1, "speaker", "Speaker", "Reduced sub-bass lift (-1.5 dB) and gentler air (-0.5 dB) for small drivers.", floatArrayOf(0f, -1.5f, 0f, 0f, 0f, -0.5f, 0f, 0f)),
    HEADPHONE(2, "headphone", "Headphone", "Lighter bass (-0.5 dB) and softer presence (-1.0 dB) for close-coupled drivers.", floatArrayOf(0f, -0.5f, 0f, 0f, -1.0f, 0f, 0f, 0f)),
    DAC(3, "dac", "DAC", "Gentler air (-1.5 dB shelf, -1.0 dB exciter) for revealing chains.", floatArrayOf(0f, 0f, 0f, 0f, 0f, -1.5f, 0f, -1.0f))
}

enum class Eq15Preset(val label: String, val gainsDb: FloatArray) {
    DEFAULT("Default", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    STUDIO_MASTER("Studio Master", floatArrayOf(2.6f, 2.8f, 2.2f, 0.6f, -1.8f, -2.6f, -1.2f, 0.0f, 1.2f, 2.4f, 3.6f, 4.0f, 4.2f, 4.5f, 4.8f)),
    ACOUSTIC("Acoustic", floatArrayOf(3.0f, 2.5f, 2.0f, 1.5f, 0.5f, 1.0f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f, 1.0f, 1.0f, 1.5f, 1.5f)),
    VOCAL_CLARITY("Vocal Clarity", floatArrayOf(-1.5f, -1.0f, -0.5f, 0f, 0.5f, 1.0f, 2.0f, 3.0f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.5f, 1.0f)),
    CLASSICAL("Classical", floatArrayOf(3.0f, 2.5f, 2.0f, 1.5f, 0.5f, 0f, 0f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.0f)),
    JAZZ("Jazz", floatArrayOf(2.5f, 2.0f, 1.5f, 1.0f, 0f, -0.5f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.0f, 2.5f, 2.5f, 2.5f)),
    ROCK("Rock", floatArrayOf(4.0f, 3.5f, 3.0f, 1.5f, 0f, -1.0f, -1.0f, -0.5f, 0.5f, 1.5f, 2.5f, 3.0f, 3.5f, 4.0f, 4.0f)),
    POP("Pop", floatArrayOf(-1.0f, -0.5f, 0.0f, 1.0f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f, 0.5f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f)),
    TREBLE_AIR("Treble Air", floatArrayOf(0f, 0f, 0f, -0.5f, -0.5f, -0.5f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 3.5f, 4.5f, 5.0f)),
    DEEP_BASS_CLEAN("Deep Bass Clean", floatArrayOf(5.0f, 4.5f, 3.5f, 2.5f, 1.0f, 0f, -0.5f, -0.5f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f)),
    ELECTRONIC("Electronic", floatArrayOf(4.5f, 4.0f, 3.0f, 2.0f, 0.5f, -0.5f, 0f, 1.0f, 2.0f, 2.5f, 2.5f, 2.5f, 3.0f, 3.5f, 4.0f)),
    RNB("R&B", floatArrayOf(5.0f, 4.5f, 3.5f, 2.0f, 1.0f, -0.5f, -1.0f, -0.5f, 0.5f, 1.5f, 2.0f, 2.5f, 3.0f, 3.0f, 3.0f)),
    CUSTOM("Custom", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f));

    val displayName: String get() = label
}

data class AudioPlaybackState(
    val streamingQuality: AudioQualityTier = AudioQualityTier.DOLBY_ATMOS,
    val downloadQuality: AudioQualityTier = AudioQualityTier.DOLBY_ATMOS,
    val bitPerfectMode: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val crossfadeSeconds: Int = 6,
    val keepScreenAwakeLyrics: Boolean = false,
    val downloadSyncedLyrics: Boolean = true,
    val usbExclusiveOutput: Boolean = false,
    val internalDirectOutput: Boolean = false,
    val softwareVolumeUsb: Boolean = false,
    val loudnessMode: LoudnessMode = LoudnessMode.OFF,
    val eqEnabled: Boolean = false,
    val eqPreset: Eq15Preset = Eq15Preset.DEFAULT,
    val eqBandGains: List<Float> = Eq15Preset.DEFAULT.gainsDb.toList(),
    val systemAudioEffects: Boolean = false,
    val studioMasterClarity: Boolean = true,
    val clarityOutputPreset: ClarityOutputPreset = ClarityOutputPreset.REFERENCE,
    val claritySpatialBypass: Boolean = false,
    val dolbySurroundAtmos: Boolean = false,
    val spatialAudioEnabled: Boolean = false,
    val spatialMode: SpatialMode = SpatialMode.SPEAKERS,
    val spatialSpeakerAngle: Float = 30f,
    val spatialAmount: Float = 1.0f,

    // Backwards-compatible aliases
    val wifiQuality: AudioQualityTier = streamingQuality,
    val mobileQuality: AudioQualityTier = streamingQuality,
    val autoAdjustQuality: Boolean = false,
    val gaplessPlayback: Boolean = true,
    val automixEnabled: Boolean = true,
    val skipSilence: Boolean = false,
    val bitPerfectUsbDac: Boolean = bitPerfectMode
)

object AudioQualityFallbackHelper {
    fun getQualityFallbackChain(selectedTier: AudioQualityTier): List<AudioQualityTier> {
        return when (selectedTier) {
            AudioQualityTier.YOUTUBE_MUSIC -> listOf(AudioQualityTier.YOUTUBE_MUSIC)
            AudioQualityTier.DATA_SAVER -> listOf(AudioQualityTier.DATA_SAVER)
            AudioQualityTier.DOLBY_ATMOS -> listOf(
                AudioQualityTier.DOLBY_ATMOS,
                AudioQualityTier.HI_RES_MAX,
                AudioQualityTier.HI_RES_96,
                AudioQualityTier.CD_LOSSLESS,
                AudioQualityTier.STANDARD_320,
                AudioQualityTier.YOUTUBE_MUSIC
            )
            AudioQualityTier.HI_RES_MAX -> listOf(
                AudioQualityTier.HI_RES_MAX,
                AudioQualityTier.HI_RES_96,
                AudioQualityTier.CD_LOSSLESS,
                AudioQualityTier.STANDARD_320,
                AudioQualityTier.YOUTUBE_MUSIC
            )
            AudioQualityTier.HI_RES_96 -> listOf(
                AudioQualityTier.HI_RES_96,
                AudioQualityTier.CD_LOSSLESS,
                AudioQualityTier.STANDARD_320,
                AudioQualityTier.YOUTUBE_MUSIC
            )
            AudioQualityTier.CD_LOSSLESS -> listOf(
                AudioQualityTier.CD_LOSSLESS,
                AudioQualityTier.STANDARD_320,
                AudioQualityTier.YOUTUBE_MUSIC
            )
            AudioQualityTier.STANDARD_320 -> listOf(
                AudioQualityTier.STANDARD_320,
                AudioQualityTier.YOUTUBE_MUSIC
            )
        }
    }
}

@Singleton
class AudioPlaybackStore internal constructor(
    context: Context?,
    @Suppress("UNUSED_PARAMETER") isTest: Boolean
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, false)

    constructor() : this(null, true)

    private val dataStore = context?.audioPlaybackDataStore

    private object Keys {
        val STREAM_QUALITY = stringPreferencesKey("stream_quality")
        val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        val BIT_PERFECT = booleanPreferencesKey("bit_perfect")
        val CROSSFADE = booleanPreferencesKey("crossfade")
        val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        val KEEP_AWAKE_LYRICS = booleanPreferencesKey("keep_awake_lyrics")
        val DOWNLOAD_LRC = booleanPreferencesKey("download_lrc")
        val USB_EXCLUSIVE = booleanPreferencesKey("usb_exclusive")
        val INTERNAL_DIRECT = booleanPreferencesKey("internal_direct")
        val SW_VOLUME_USB = booleanPreferencesKey("sw_volume_usb")
        val LOUDNESS_MODE = stringPreferencesKey("loudness_mode")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val EQ_BANDS_CSV = stringPreferencesKey("eq_bands_csv")
        val SYSTEM_FX = booleanPreferencesKey("system_fx")
        val STUDIO_CLARITY = booleanPreferencesKey("studio_clarity")
        val CLARITY_PRESET = stringPreferencesKey("clarity_preset")
        val CLARITY_SPATIAL_BYPASS = booleanPreferencesKey("clarity_spatial_bypass")
        val DOLBY_SURROUND_ATMOS = booleanPreferencesKey("dolby_surround_atmos")
        val SPATIAL_AUDIO_ENABLED = booleanPreferencesKey("spatial_audio_enabled")
        val SPATIAL_MODE = stringPreferencesKey("spatial_mode")
        val SPATIAL_SPEAKER_ANGLE = floatPreferencesKey("spatial_speaker_angle")
        val SPATIAL_AMOUNT = floatPreferencesKey("spatial_amount")

        // Legacy keys
        val WIFI_QUALITY = stringPreferencesKey("wifi_quality_tier")
        val MOBILE_QUALITY = stringPreferencesKey("mobile_quality_tier")
        val AUTO_ADJUST_QUALITY = booleanPreferencesKey("auto_adjust_quality")
        val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        val AUTOMIX_ENABLED = booleanPreferencesKey("automix_enabled")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val BIT_PERFECT_USB = booleanPreferencesKey("bit_perfect_usb_dac")
    }

    val stateFlow: Flow<AudioPlaybackState> = dataStore?.data?.map { prefs ->
        val preset = runCatching { Eq15Preset.valueOf(prefs[Keys.EQ_PRESET] ?: "DEFAULT") }.getOrDefault(Eq15Preset.DEFAULT)
        val savedBands = prefs[Keys.EQ_BANDS_CSV]?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 15 } ?: preset.gainsDb.toList()

        AudioPlaybackState(
            streamingQuality = runCatching { AudioQualityTier.valueOf(prefs[Keys.STREAM_QUALITY] ?: prefs[Keys.WIFI_QUALITY] ?: "DOLBY_ATMOS") }.getOrDefault(AudioQualityTier.DOLBY_ATMOS),
            downloadQuality = runCatching { AudioQualityTier.valueOf(prefs[Keys.DOWNLOAD_QUALITY] ?: "DOLBY_ATMOS") }.getOrDefault(AudioQualityTier.DOLBY_ATMOS),
            bitPerfectMode = prefs[Keys.BIT_PERFECT] ?: prefs[Keys.BIT_PERFECT_USB] ?: false,
            crossfadeEnabled = prefs[Keys.CROSSFADE] ?: false,
            crossfadeSeconds = prefs[Keys.CROSSFADE_SECONDS] ?: 6,
            keepScreenAwakeLyrics = prefs[Keys.KEEP_AWAKE_LYRICS] ?: false,
            downloadSyncedLyrics = prefs[Keys.DOWNLOAD_LRC] ?: true,
            usbExclusiveOutput = prefs[Keys.USB_EXCLUSIVE] ?: false,
            internalDirectOutput = prefs[Keys.INTERNAL_DIRECT] ?: false,
            softwareVolumeUsb = prefs[Keys.SW_VOLUME_USB] ?: false,
            loudnessMode = runCatching { LoudnessMode.valueOf(prefs[Keys.LOUDNESS_MODE] ?: "OFF") }.getOrDefault(LoudnessMode.OFF),
            eqEnabled = prefs[Keys.EQ_ENABLED] ?: false,
            eqPreset = preset,
            eqBandGains = savedBands,
            systemAudioEffects = prefs[Keys.SYSTEM_FX] ?: false,
            studioMasterClarity = prefs[Keys.STUDIO_CLARITY] ?: true,
            clarityOutputPreset = runCatching { ClarityOutputPreset.valueOf(prefs[Keys.CLARITY_PRESET] ?: "REFERENCE") }.getOrDefault(ClarityOutputPreset.REFERENCE),
            claritySpatialBypass = prefs[Keys.CLARITY_SPATIAL_BYPASS] ?: false,
            dolbySurroundAtmos = prefs[Keys.DOLBY_SURROUND_ATMOS] ?: false,
            spatialAudioEnabled = prefs[Keys.SPATIAL_AUDIO_ENABLED] ?: false,
            spatialMode = runCatching { SpatialMode.valueOf(prefs[Keys.SPATIAL_MODE] ?: "SPEAKERS") }.getOrDefault(SpatialMode.SPEAKERS),
            spatialSpeakerAngle = prefs[Keys.SPATIAL_SPEAKER_ANGLE] ?: 30f,
            spatialAmount = prefs[Keys.SPATIAL_AMOUNT] ?: 1.0f
        )
    } ?: flowOf(AudioPlaybackState())

    suspend fun setStreamingQuality(tier: AudioQualityTier) {
        dataStore?.edit { it[Keys.STREAM_QUALITY] = tier.name }
    }

    suspend fun setDownloadQuality(tier: AudioQualityTier) {
        dataStore?.edit { it[Keys.DOWNLOAD_QUALITY] = tier.name }
    }

    suspend fun setBitPerfect(enabled: Boolean) {
        dataStore?.edit {
            it[Keys.BIT_PERFECT] = enabled
            if (!enabled) it[Keys.USB_EXCLUSIVE] = false
        }
    }

    suspend fun setCrossfade(enabled: Boolean, seconds: Int = 6) {
        dataStore?.edit {
            it[Keys.CROSSFADE] = enabled
            it[Keys.CROSSFADE_SECONDS] = seconds.coerceIn(1, 12)
        }
    }

    suspend fun setKeepAwakeLyrics(enabled: Boolean) {
        dataStore?.edit { it[Keys.KEEP_AWAKE_LYRICS] = enabled }
    }

    suspend fun setDownloadSyncedLyrics(enabled: Boolean) {
        dataStore?.edit { it[Keys.DOWNLOAD_LRC] = enabled }
    }

    suspend fun setUsbExclusive(enabled: Boolean) {
        dataStore?.edit {
            it[Keys.USB_EXCLUSIVE] = enabled
            if (enabled) it[Keys.BIT_PERFECT] = true
        }
    }

    suspend fun setInternalDirect(enabled: Boolean) {
        dataStore?.edit { it[Keys.INTERNAL_DIRECT] = enabled }
    }

    suspend fun setSoftwareVolumeUsb(enabled: Boolean) {
        dataStore?.edit { it[Keys.SW_VOLUME_USB] = enabled }
    }

    suspend fun setLoudnessMode(mode: LoudnessMode) {
        dataStore?.edit { it[Keys.LOUDNESS_MODE] = mode.name }
    }

    suspend fun setEqEnabled(enabled: Boolean) {
        dataStore?.edit { it[Keys.EQ_ENABLED] = enabled }
    }

    suspend fun setEqPreset(preset: Eq15Preset) {
        dataStore?.edit {
            it[Keys.EQ_PRESET] = preset.name
            it[Keys.EQ_BANDS_CSV] = preset.gainsDb.joinToString(",")
            it[Keys.EQ_ENABLED] = true
        }
    }

    suspend fun setEqBandGain(bandIndex: Int, gainDb: Float, currentBands: List<Float>) {
        if (bandIndex !in 0 until 15) return
        dataStore?.edit {
            val stored = it[Keys.EQ_BANDS_CSV]?.split(",")?.mapNotNull { v -> v.toFloatOrNull() }
                ?.takeIf { l -> l.size == 15 }
            val base = stored ?: currentBands.takeIf { l -> l.size == 15 } ?: List(15) { 0f }
            val updated = base.toMutableList().apply { this[bandIndex] = gainDb.coerceIn(-12f, 12f) }
            it[Keys.EQ_PRESET] = Eq15Preset.CUSTOM.name
            it[Keys.EQ_BANDS_CSV] = updated.joinToString(",")
        }
    }

    suspend fun setSystemAudioEffects(enabled: Boolean) {
        dataStore?.edit { it[Keys.SYSTEM_FX] = enabled }
    }

    suspend fun setStudioMasterClarity(enabled: Boolean) {
        dataStore?.edit { it[Keys.STUDIO_CLARITY] = enabled }
    }

    suspend fun setClarityOutputPreset(preset: ClarityOutputPreset) {
        dataStore?.edit { it[Keys.CLARITY_PRESET] = preset.name }
    }

    suspend fun setClaritySpatialBypass(bypass: Boolean) {
        dataStore?.edit { it[Keys.CLARITY_SPATIAL_BYPASS] = bypass }
    }

    suspend fun setDolbySurroundAtmos(enabled: Boolean) {
        dataStore?.edit { it[Keys.DOLBY_SURROUND_ATMOS] = enabled }
    }

    suspend fun setSpatialAudioEnabled(enabled: Boolean) {
        dataStore?.edit { it[Keys.SPATIAL_AUDIO_ENABLED] = enabled }
    }

    suspend fun setSpatialMode(mode: SpatialMode) {
        dataStore?.edit { it[Keys.SPATIAL_MODE] = mode.name }
    }

    suspend fun setSpatialSpeakerAngle(angleDeg: Float) {
        dataStore?.edit { it[Keys.SPATIAL_SPEAKER_ANGLE] = angleDeg.coerceIn(10f, 60f) }
    }

    suspend fun setSpatialAmount(amount: Float) {
        dataStore?.edit { it[Keys.SPATIAL_AMOUNT] = amount.coerceIn(0f, 1f) }
    }

    // Compatibility methods
    suspend fun setWifiQuality(tier: AudioQualityTier) = setStreamingQuality(tier)
    suspend fun setMobileQuality(tier: AudioQualityTier) = setStreamingQuality(tier)
    suspend fun setAutoAdjustQuality(enabled: Boolean) { dataStore?.edit { it[Keys.AUTO_ADJUST_QUALITY] = enabled } }
    suspend fun setGaplessPlayback(enabled: Boolean) { dataStore?.edit { it[Keys.GAPLESS_PLAYBACK] = enabled } }
    suspend fun setAutomixEnabled(enabled: Boolean) { dataStore?.edit { it[Keys.AUTOMIX_ENABLED] = enabled } }
    suspend fun setSkipSilence(enabled: Boolean) { dataStore?.edit { it[Keys.SKIP_SILENCE] = enabled } }
    suspend fun setBitPerfectUsbDac(enabled: Boolean) = setBitPerfect(enabled)
    suspend fun setKeepScreenAwakeLyrics(enabled: Boolean) = setKeepAwakeLyrics(enabled)
}
