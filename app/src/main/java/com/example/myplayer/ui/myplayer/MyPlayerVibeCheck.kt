package com.example.myplayer.ui.myplayer

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

// Funny desi mood / vibe-check popup: tracks a same-vibe listening streak and shows a dialog after N minutes.
enum class MyPlayerVibeCategory(
    val label: String,
    val emoji: String,
    val badgeText: String,
    val accentColor: Color,
    val secondaryColor: Color,
    val primaryButtonText: String,
    val switchButtonText: String
) {
    SAD_HEARTBREAK(
        label = "Sad / Heartbreak",
        emoji = "ðŸ˜­ðŸ’”",
        badgeText = "AASHIQUI MODE DETECTED",
        accentColor = Color(0xFF7E57C2),
        secondaryColor = Color(0xFF3949AB),
        primaryButtonText = "Sab Theek Hai Bhai ðŸ˜Ž",
        switchButtonText = "Mood Change Karo! ðŸ”¥"
    ),
    PARTY_BANGER(
        label = "Party / Dance",
        emoji = "ðŸ•ºðŸŽ‰",
        badgeText = "FULL MAHAUL MODE",
        accentColor = Color(0xFFFF4081),
        secondaryColor = Color(0xFFFF9100),
        primaryButtonText = "Full Party Chal Rahi Hai! ðŸ¥³",
        switchButtonText = "Agla Banger Bajao âš¡"
    ),
    ROMANTIC_LOVE(
        label = "Romantic / Love",
        emoji = "ðŸ˜ðŸŒ¹",
        badgeText = "PYAAR MOHABBAT ALERT",
        accentColor = Color(0xFFE91E63),
        secondaryColor = Color(0xFFAD1457),
        primaryButtonText = "Nazar Mat Laga Bhai ðŸ§¿ðŸ˜‚",
        switchButtonText = "Next Song â–¶"
    ),
    WORKOUT_HUSTLE(
        label = "Gym / Beast Mode",
        emoji = "ðŸ’ªðŸ”¥",
        badgeText = "BEAST & HUSTLE MODE",
        accentColor = Color(0xFF38E54D),
        secondaryColor = Color(0xFF00BFA5),
        primaryButtonText = "Aaj Toofani Karenge! ðŸ’ª",
        switchButtonText = "Next Banger ðŸ”¥"
    ),
    DEVOTIONAL_PEACE(
        label = "Bhakti / Sukoon",
        emoji = "ðŸ™âœ¨",
        badgeText = "SUKOON & BHAKTI VIBE",
        accentColor = Color(0xFFFFB300),
        secondaryColor = Color(0xFFF57C00),
        primaryButtonText = "Dil Ko Sukoon Mila ðŸ™",
        switchButtonText = "Continue Listening âœ¨"
    ),
    CHILL_LOFI(
        label = "Chill / Late Night",
        emoji = "ðŸŒ™ðŸŽ§",
        badgeText = "CHILL & OVERTHINKING ZONE",
        accentColor = Color(0xFF26C6DA),
        secondaryColor = Color(0xFF5C6BC0),
        primaryButtonText = "Bas Vibe Le Raha Hu ðŸ˜Œ",
        switchButtonText = "Kuch Energetic Bajao âš¡"
    )
}

data class MyPlayerVibePopupEvent(
    val category: MyPlayerVibeCategory,
    val elapsedMinutes: Int,
    val headline: String,
    val funnyMessage: String,
    val currentSongTitle: String
)

object MyPlayerVibeCheckManager {
    var enabled by mutableStateOf(true)
    var thresholdMinutes by mutableIntStateOf(15)
    var activePopup by mutableStateOf<MyPlayerVibePopupEvent?>(null)

    private var currentStreakCategory: MyPlayerVibeCategory? = null
    private var accumulatedListeningMs: Long = 0L
    private var lastTickRealtimeMs: Long = 0L
    private var msgVariantIdx: Int = 0

    private val sadKeywords = listOf(
        "sad", "bewafa", "judaai", "judaiyaan", "adhura", "tanha", "rona", "yaad", "yaadein",
        "dard", "toot", "channa mereya", "agar tum saath", "tum hi ho", "bedardi", "bekhayali",
        "kabira", "bhula diya", "adhuri kahani", "bairaagi", "alvida", "khairiyat", "mann bharrya",
        "qismat", "pachtaoge", "filhall", "zaroori tha", "heartbreak", "lonely", "broken", "cry",
        "miss you", "hurt", "tears", "hamdard", "phir bhi tumko", "ae dil hai mushkil",
        "tadap", "bikhra", "shikayat", "husn", "khat", "jo tum mere ho", "choo lo", "afsos"
    )

    private val partyKeywords = listOf(
        "party", "dance", "dj", "remix", "club", "nachde", "bhangra", "kala chashma", "chull",
        "badshah", "honey singh", "diljit", "karan aujla", "ap dhillon", "brown munde", "tauba",
        "zingaat", "ghungroo", "baby doll", "bom diggy", "saturday", "gallan goodiyan", "thumakda",
        "lalkara", "goat", "hass hass", "softly", "hookah", "balma", "sharara", "naatu",
        "besharam", "jhoome", "chaleya", "illuminati", "aayi nai", "stree", "banger", "disco"
    )

    private val romanticKeywords = listOf(
        "ishq", "pyaar", "pyar", "mohabbat", "kesariya", "raataan lambiyan", "tum se hi",
        "apna bana le", "satranga", "heeriye", "pehle bhi main", "tere hawale", "mast magan",
        "shayad", "hawayein", "zaalima", "dil diyan gallan", "love", "romance", "jaan", "sanam",
        "mahiye", "tu hai to", "ve kamleya", "o maahi", "sajni", "akhiyaan gulab", "tera ban jaunga"
    )

    private val workoutKeywords = listOf(
        "sidhu moose", "moose wala", "kr\$na", "krsna", "seedhe maut", "divine", "raftaar",
        "emiway", "workout", "gym", "hustle", "beast", "phonk", "believer", "unstoppable",
        "lose yourself", "sultan", "zinda", "chak de", "kar har maidan", "aarambh", "dangal",
        "millionaire", "hanumankind", "big dawgs", "295", "levels", "warrior", "power"
    )

    private val devotionalKeywords = listOf(
        "bhajan", "aarti", "chalisa", "hanuman", "shiv", "mahadev", "krishna", "ram", "radhe",
        "kun faya", "arziyan", "bhar do jholi", "sufi", "qawwali", "namo namo", "ganesha", "bholenath"
    )

    fun classifySongVibe(title: String, artist: String, album: String): MyPlayerVibeCategory {
        val combined = "$title $artist $album".lowercase(Locale.ROOT)
        if (MyPlayerAudioFxDspBridge.reverbEnabled && !MyPlayerAudioFxDspBridge.spatialIsSpeakersMode &&
            sadKeywords.any { combined.contains(it) }
        ) {
            return MyPlayerVibeCategory.SAD_HEARTBREAK
        }

        return when {
            devotionalKeywords.any { combined.contains(it) } -> MyPlayerVibeCategory.DEVOTIONAL_PEACE
            sadKeywords.any { combined.contains(it) } -> MyPlayerVibeCategory.SAD_HEARTBREAK
            partyKeywords.any { combined.contains(it) } -> MyPlayerVibeCategory.PARTY_BANGER
            workoutKeywords.any { combined.contains(it) } -> MyPlayerVibeCategory.WORKOUT_HUSTLE
            romanticKeywords.any { combined.contains(it) } -> MyPlayerVibeCategory.ROMANTIC_LOVE
            combined.contains("arijit") || combined.contains("atif") || combined.contains("kk") ||
                    combined.contains("jubin") || combined.contains("vishal mishra") ||
                    combined.contains("anuv jain") || combined.contains("prateek kuhad") -> MyPlayerVibeCategory.SAD_HEARTBREAK
            else -> MyPlayerVibeCategory.CHILL_LOFI
        }
    }

    fun onPlaybackTick(
        title: String,
        artist: String,
        album: String,
        isPlaying: Boolean
    ) {
        if (!enabled || !isPlaying || title.isBlank()) {
            lastTickRealtimeMs = System.currentTimeMillis()
            return
        }

        val now = System.currentTimeMillis()
        val deltaMs = if (lastTickRealtimeMs > 0L) (now - lastTickRealtimeMs).coerceIn(0L, 2500L) else 0L
        lastTickRealtimeMs = now

        val detectedVibe = classifySongVibe(title, artist, album)
        if (currentStreakCategory == detectedVibe) {
            accumulatedListeningMs += deltaMs
        } else {
            currentStreakCategory = detectedVibe
            accumulatedListeningMs = deltaMs
        }

        val targetMs = thresholdMinutes.coerceAtLeast(1) * 60_000L
        if (accumulatedListeningMs >= targetMs && activePopup == null) {
            val mins = (accumulatedListeningMs / 60_000L).toInt().coerceAtLeast(thresholdMinutes)
            accumulatedListeningMs = 0L
            activePopup = buildFunnyPopup(detectedVibe, mins, title)
        }
    }

    fun triggerPreviewPopup(category: MyPlayerVibeCategory, currentSongTitle: String = "Current Track") {
        activePopup = buildFunnyPopup(category, thresholdMinutes, currentSongTitle)
    }

    fun dismissPopup() {
        activePopup = null
    }

    private fun buildFunnyPopup(
        category: MyPlayerVibeCategory,
        mins: Int,
        songTitle: String
    ): MyPlayerVibePopupEvent {
        val pick = msgVariantIdx++
        return when (category) {
            MyPlayerVibeCategory.SAD_HEARTBREAK -> {
                val options = listOf(
                    "à¤­à¤¾à¤ˆ à¤¬à¤¹à¥à¤¤ à¤¸à¥ˆà¤¡ à¤²à¤— à¤°à¤¹à¤¾ à¤¹à¥ˆ... à¤•à¥à¤¯à¤¾ à¤¬à¤¾à¤¤ à¤¹à¥‹ à¤—à¤ˆ? ðŸ¥ºðŸ’”" to
                            "à¤ªà¤¿à¤›à¤²à¥‡ $mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤²à¤—à¤¾à¤¤à¤¾à¤° Sad Songs à¤¸à¥à¤¨ à¤°à¤¹à¤¾ à¤¹à¥ˆ! à¤¸à¤¬ à¤ à¥€à¤• à¤¤à¥‹ à¤¹à¥ˆ à¤­à¤¾à¤ˆ? à¤•à¤¿à¤¸à¤¨à¥‡ à¤¦à¤¿à¤² à¤¤à¥‹à¤¡à¤¼ à¤¦à¤¿à¤¯à¤¾ à¤¯à¤¾ à¤ªà¥à¤°à¤¾à¤¨à¥€ à¤¯à¤¾à¤¦à¥‡à¤‚ à¤¤à¤¾à¤œà¤¼à¤¾ à¤¹à¥‹ à¤—à¤ˆà¤‚? à¤ªà¤¾à¤¨à¥€ à¤ªà¥€ à¤²à¥‡ à¤¯à¤¾à¤°, à¤¹à¤® à¤¹à¥ˆà¤‚ à¤¨à¤¾!",
                    "à¤…à¤°à¥‡ à¤­à¤¾à¤ˆ à¤¬à¤¸ à¤•à¤°, à¤°à¥à¤²à¤¾à¤à¤—à¤¾ à¤•à¥à¤¯à¤¾? ðŸ˜­ðŸŽ»" to
                            "à¤ªà¤¿à¤›à¤²à¥‡ $mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤«à¥à¤² à¤†à¤¶à¤¿à¤•à¥€/à¤¦à¤°à¥à¤¦ à¤­à¤°à¥‡ à¤—à¤¾à¤¨à¥‡ à¤¬à¤œ à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚! Arijit Singh à¤­à¥€ à¤«à¥‹à¤¨ à¤¸à¥‡ à¤¬à¤¾à¤¹à¤° à¤†à¤•à¤° à¤ªà¥‚à¤›à¤¨à¥‡ à¤µà¤¾à¤²à¤¾ à¤¹à¥ˆ â€” 'à¤­à¤¾à¤ˆ à¤Ÿà¤¿à¤¶à¥‚ à¤ªà¥‡à¤ªà¤° à¤­à¤¿à¤œà¤µà¤¾à¤Šà¤‚ à¤¯à¤¾ à¤®à¥‚à¤¡ à¤šà¥‡à¤‚à¤œ à¤•à¤°à¥‡à¤‚?'",
                    "à¤šà¥‡à¤¤à¤¾à¤µà¤¨à¥€ âš ï¸: Sadness Overload! ðŸ’”" to
                            "$mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤¸à¥ˆà¤¡ à¤¸à¥‰à¤¨à¥à¤— à¤šà¤² à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚! à¤‡à¤¤à¤¨à¥€ à¤¦à¥‡à¤° à¤¦à¤°à¥à¤¦ à¤­à¤°à¥‡ à¤—à¤¾à¤¨à¥‡ à¤¸à¥à¤¨à¤¨à¥‡ à¤¸à¥‡ à¤ªà¥à¤°à¤¾à¤¨à¥€ à¤šà¥ˆà¤Ÿà¥à¤¸ à¤¸à¥à¤•à¥à¤°à¥‰à¤² à¤•à¤°à¤¨à¥‡ à¤•à¤¾ à¤–à¤¤à¤°à¤¾ 99% à¤¬à¤¢à¤¼ à¤œà¤¾à¤¤à¤¾ à¤¹à¥ˆà¥¤ à¤šà¤²à¥‹ à¤…à¤¬ à¤¥à¥‹à¤¡à¤¼à¤¾ à¤®à¤¾à¤¹à¥Œà¤² à¤¬à¤¦à¤²à¤¤à¥‡ à¤¹à¥ˆà¤‚!"
                )
                val (head, body) = options[pick % options.size]
                MyPlayerVibePopupEvent(category, mins, head, body, songTitle)
            }

            MyPlayerVibeCategory.PARTY_BANGER -> {
                val options = listOf(
                    "à¤“à¤¹à¥‹! à¤†à¤œ à¤¤à¥‹ à¤«à¥à¤² à¤®à¥‚à¤¡ à¤®à¥‡à¤‚ à¤²à¤— à¤°à¤¹à¤¾ à¤¹à¥ˆ à¤­à¤¾à¤ˆ! ðŸ•ºðŸŽ‰" to
                            "à¤ªà¤¿à¤›à¤²à¥‡ $mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤²à¤—à¤¾à¤¤à¤¾à¤° Party Bangers à¤¬à¤œ à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚! à¤•à¥à¤¯à¤¾ à¤–à¥à¤¶à¥€ à¤•à¥€ à¤¬à¤¾à¤¤ à¤¹à¥ˆ à¤­à¤¾à¤ˆ? à¤²à¥‰à¤Ÿà¤°à¥€ à¤²à¤— à¤—à¤ˆ, à¤¸à¥ˆà¤²à¤°à¥€ à¤† à¤—à¤ˆ à¤¯à¤¾ à¤•à¥à¤°à¤¶ à¤•à¤¾ à¤°à¤¿à¤ªà¥à¤²à¤¾à¤ˆ à¤† à¤—à¤¯à¤¾?",
                    "à¤…à¤•à¥‡à¤²à¥‡-à¤…à¤•à¥‡à¤²à¥‡ à¤ªà¤¾à¤°à¥à¤Ÿà¥€ à¤¹à¥‹ à¤°à¤¹à¥€ à¤¹à¥ˆ à¤­à¤¾à¤ˆ? ðŸ¥³ðŸ”¥" to
                            "$mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤¨à¥‰à¤¨-à¤¸à¥à¤Ÿà¥‰à¤ª à¤¡à¤¾à¤‚à¤¸ à¤”à¤° à¤ªà¤¾à¤°à¥à¤Ÿà¥€ à¤Ÿà¥à¤°à¥ˆà¤• à¤šà¤² à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚! à¤ªà¤¡à¤¼à¥‹à¤¸à¥€ à¤­à¥€ à¤ªà¥‚à¤› à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚ à¤•à¤¿ à¤†à¤œ à¤•à¤¿à¤¸ à¤–à¥à¤¶à¥€ à¤®à¥‡à¤‚ DJ à¤¬à¤¨à¤¾ à¤¬à¥ˆà¤ à¤¾ à¤¹à¥ˆ?",
                    "à¤†à¤œ à¤¤à¥‹ à¤…à¤²à¤— à¤¹à¥€ à¤²à¥‡à¤µà¤² à¤•à¤¾ à¤®à¤¾à¤¹à¥Œà¤² à¤¹à¥ˆ! âš¡ðŸ¾" to
                            "à¤²à¤—à¤¾à¤¤à¤¾à¤° $mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤«à¥à¤² à¤à¤¨à¤°à¥à¤œà¥€ à¤ªà¤¾à¤°à¥à¤Ÿà¥€ à¤®à¥à¤¯à¥‚à¤œà¤¿à¤• à¤šà¤² à¤°à¤¹à¤¾ à¤¹à¥ˆ! à¤à¤¸à¥‡ à¤¹à¥€ à¤–à¥à¤¶ à¤°à¤¹ à¤®à¥‡à¤°à¥‡ à¤­à¤¾à¤ˆ, à¤ªà¤¾à¤°à¥à¤Ÿà¥€ à¤°à¥à¤•à¤¨à¥€ à¤¨à¤¹à¥€à¤‚ à¤šà¤¾à¤¹à¤¿à¤!"
                )
                val (head, body) = options[pick % options.size]
                MyPlayerVibePopupEvent(category, mins, head, body, songTitle)
            }

            MyPlayerVibeCategory.ROMANTIC_LOVE -> {
                val options = listOf(
                    "à¤†à¤¯ à¤¹à¤¾à¤¯! à¤¬à¤¡à¤¼à¥‡ à¤°à¥‹à¤®à¤¾à¤‚à¤Ÿà¤¿à¤• à¤—à¤¾à¤¨à¥‡ à¤¸à¥à¤¨à¥‡ à¤œà¤¾ à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚! ðŸ˜ðŸŒ¹" to
                            "à¤ªà¤¿à¤›à¤²à¥‡ $mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤¸à¤¿à¤°à¥à¤« à¤ªà¥à¤¯à¤¾à¤°-à¤®à¥‹à¤¹à¤¬à¥à¤¬à¤¤ à¤µà¤¾à¤²à¥‡ à¤—à¤¾à¤¨à¥‡ à¤šà¤² à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚! à¤­à¤¾à¤ˆ à¤šà¤•à¥à¤•à¤° à¤•à¥à¤¯à¤¾ à¤¹à¥ˆ? à¤•à¥‹à¤ˆ à¤–à¤¾à¤¸ à¤®à¤¿à¤² à¤—à¤¯à¤¾ à¤¹à¥ˆ à¤¯à¤¾ à¤¹à¤µà¤¾à¤“à¤‚ à¤®à¥‡à¤‚ à¤‡à¤¶à¥à¤• à¤˜à¥à¤² à¤°à¤¹à¤¾ à¤¹à¥ˆ?",
                    "à¤šà¥‡à¤¹à¤°à¥‡ à¤•à¥€ à¤®à¥à¤¸à¥à¤•à¤¾à¤¨ à¤›à¥à¤ªà¤¾à¤ à¤¨à¤¹à¥€à¤‚ à¤›à¥à¤ª à¤°à¤¹à¥€ à¤­à¤¾à¤ˆ! ðŸ’˜âœ¨" to
                            "$mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤¨à¥‰à¤¨-à¤¸à¥à¤Ÿà¥‰à¤ª Romantic Vibe à¤šà¤² à¤°à¤¹à¥€ à¤¹à¥ˆ! à¤•à¤¿à¤¸à¤•à¥‡ à¤–à¥à¤¯à¤¾à¤²à¥‹à¤‚ à¤®à¥‡à¤‚ à¤–à¥‹à¤¯à¤¾ à¤¹à¥à¤† à¤¹à¥ˆ à¤®à¥‡à¤°à¥‡ à¤­à¤¾à¤ˆ? à¤¨à¤œà¤¼à¤° à¤¨à¤¾ à¤²à¤—à¥‡!"
                )
                val (head, body) = options[pick % options.size]
                MyPlayerVibePopupEvent(category, mins, head, body, songTitle)
            }

            MyPlayerVibeCategory.WORKOUT_HUSTLE -> {
                val options = listOf(
                    "à¤­à¤¾à¤ˆ à¤†à¤œ à¤¹à¥€ à¤¹à¤¿à¤®à¤¾à¤²à¤¯ à¤«à¤¤à¥‡à¤¹ à¤•à¤°à¥‡à¤—à¤¾ à¤•à¥à¤¯à¤¾? ðŸ’ªðŸ”¥" to
                            "à¤ªà¤¿à¤›à¤²à¥‡ $mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤«à¥à¤² Beast Mode à¤”à¤° Banger à¤—à¤¾à¤¨à¥‡ à¤¬à¤œ à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚! à¤œà¤¿à¤® à¤®à¥‡à¤‚ à¤²à¥‹à¤¹à¤¾ à¤ªà¤¿à¤˜à¤²à¤¾ à¤°à¤¹à¤¾ à¤¹à¥ˆ à¤¯à¤¾ à¤•à¤¿à¤¸à¥€ à¤¸à¥‡ à¤¬à¤¦à¤²à¤¾ à¤²à¥‡à¤¨à¥‡ à¤•à¥€ à¤¤à¥ˆà¤¯à¤¾à¤°à¥€ à¤šà¤² à¤°à¤¹à¥€ à¤¹à¥ˆ?",
                    "à¤à¤¨à¤°à¥à¤œà¥€ à¤®à¥€à¤Ÿà¤° 200% à¤ªà¤¾à¤° à¤¹à¥‹ à¤—à¤¯à¤¾ à¤­à¤¾à¤ˆ! âš¡ðŸ˜¤" to
                            "$mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤²à¤—à¤¾à¤¤à¤¾à¤° à¤¤à¤—à¤¡à¤¼à¥‡ Hustle/Hip-Hop à¤Ÿà¥à¤°à¥ˆà¤• à¤šà¤² à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚! à¤†à¤œ à¤¤à¥‹ à¤­à¤¾à¤ˆ à¤•à¥‹ à¤•à¥‹à¤ˆ à¤¨à¤¹à¥€à¤‚ à¤°à¥‹à¤• à¤¸à¤•à¤¤à¤¾!"
                )
                val (head, body) = options[pick % options.size]
                MyPlayerVibePopupEvent(category, mins, head, body, songTitle)
            }

            MyPlayerVibeCategory.DEVOTIONAL_PEACE -> {
                val (head, body) = "à¤µà¤¾à¤¹ à¤­à¤¾à¤ˆ! à¤†à¤œ à¤¤à¥‹ à¤à¤•à¤¦à¤® à¤­à¤•à¥à¤¤à¤¿ à¤”à¤° à¤¸à¥à¤•à¥‚à¤¨ à¤•à¤¾ à¤®à¤¾à¤¹à¥Œà¤² à¤¹à¥ˆ ðŸ™âœ¨" to
                        "à¤ªà¤¿à¤›à¤²à¥‡ $mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤à¤•à¤¦à¤® à¤¶à¤¾à¤‚à¤¤ à¤”à¤° à¤¸à¥à¤•à¥‚à¤¨ à¤¦à¥‡à¤¨à¥‡ à¤µà¤¾à¤²à¥‡ à¤—à¤¾à¤¨à¥‡ à¤¬à¤œ à¤°à¤¹à¥‡ à¤¹à¥ˆà¤‚à¥¤ à¤®à¤¨ à¤à¤•à¤¦à¤® à¤¹à¤²à¥à¤•à¤¾ à¤¹à¥‹ à¤—à¤¯à¤¾ à¤¨à¤¾?"
                MyPlayerVibePopupEvent(category, mins, head, body, songTitle)
            }

            MyPlayerVibeCategory.CHILL_LOFI -> {
                val options = listOf(
                    "à¤­à¤¾à¤ˆ à¤¬à¤¡à¤¼à¥€ à¤—à¤¹à¤°à¥€ à¤¸à¥‹à¤š à¤®à¥‡à¤‚ à¤²à¤— à¤°à¤¹à¤¾ à¤¹à¥ˆ? ðŸŒ™ðŸŽ§" to
                            "à¤ªà¤¿à¤›à¤²à¥‡ $mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤²à¤—à¤¾à¤¤à¤¾à¤° Chill / Lo-Fi à¤µà¤¾à¤‡à¤¬ à¤šà¤² à¤°à¤¹à¥€ à¤¹à¥ˆ! à¤“à¤µà¤°à¤¥à¤¿à¤‚à¤•à¤¿à¤‚à¤— à¤šà¤² à¤°à¤¹à¥€ à¤¹à¥ˆ à¤¯à¤¾ à¤¬à¤¸ à¤¸à¥à¤•à¥‚à¤¨ à¤•à¥‡ à¤ªà¤² à¤œà¥€ à¤°à¤¹à¤¾ à¤¹à¥ˆ?",
                    "à¤à¤•à¤¦à¤® à¤¸à¥à¤•à¥‚à¤¨ à¤µà¤¾à¤²à¤¾ à¤®à¤¾à¤¹à¥Œà¤² à¤¸à¥‡à¤Ÿ à¤¹à¥ˆ à¤­à¤¾à¤ˆ! âœ¨ðŸ˜Œ" to
                            "$mins à¤®à¤¿à¤¨à¤Ÿ à¤¸à¥‡ à¤¨à¥‰à¤¨-à¤¸à¥à¤Ÿà¥‰à¤ª à¤¸à¥à¤®à¥‚à¤¦ à¤®à¥à¤¯à¥‚à¤œà¤¿à¤• à¤¸à¥à¤¨ à¤°à¤¹à¤¾ à¤¹à¥ˆ! à¤šà¤¾à¤¯/à¤•à¥‰à¤«à¥€ à¤¸à¤¾à¤¥ à¤®à¥‡à¤‚ à¤¹à¥ˆ à¤¯à¤¾ à¤¬à¤¸ à¤¹à¥‡à¤¡à¤«à¥‹à¤¨ à¤²à¤—à¤¾ à¤•à¥‡ à¤¦à¥à¤¨à¤¿à¤¯à¤¾ à¤‘à¤« à¤•à¤° à¤°à¤–à¥€ à¤¹à¥ˆ?"
                )
                val (head, body) = options[pick % options.size]
                MyPlayerVibePopupEvent(category, mins, head, body, songTitle)
            }
        }
    }
}

@Composable
fun MyPlayerVibeCheckPopupDialog(
    event: MyPlayerVibePopupEvent,
    onDismiss: () -> Unit,
    onSwitchVibeClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "vibeEmojiPulse")
    val emojiScale by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emojiBounce"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .shadow(24.dp, RoundedCornerShape(30.dp)),
            shape = RoundedCornerShape(30.dp),
            color = Color(0xFF121217),
            border = BorderStroke(
                width = 1.8.dp,
                brush = Brush.linearGradient(
                    colors = listOf(event.category.accentColor, event.category.secondaryColor)
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                event.category.accentColor.copy(alpha = 0.22f),
                                Color(0xFF121217),
                                Color(0xFF0C0C10)
                            )
                        )
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = event.category.accentColor.copy(alpha = 0.20f),
                    border = BorderStroke(1.dp, event.category.accentColor.copy(alpha = 0.60f))
                ) {
                    Text(
                        text = "${event.category.badgeText} â€¢ ${event.elapsedMinutes} MIN STREAK",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .scale(emojiScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    event.category.accentColor.copy(alpha = 0.38f),
                                    Color.White.copy(alpha = 0.05f)
                                )
                            )
                        )
                        .border(1.5.dp, event.category.accentColor.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = event.category.emoji, fontSize = 38.sp)
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = event.headline,
                    fontSize = 21.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = event.funnyMessage,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = Color.White.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(22.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = event.category.accentColor,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = event.category.primaryButtonText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onSwitchVibeClick()
                    },
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text(
                        text = event.category.switchButtonText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun MyPlayerFunnyVibeCheckSettingsCard(
    myplayerState: MyPlayerUiStateHolder
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MyPlayerSurface,
        border = BorderStroke(1.dp, MyPlayerAccentPurple.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MyPlayerAccentPurple.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ðŸ˜­ðŸŽ‰", fontSize = 18.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Desi Mood & Vibe-Check Pop-up",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "Shows a funny pop-up when you listen to Sad, Party, Romantic or Gym songs non-stop",
                        fontSize = 12.sp,
                        color = MyPlayerTextSecondary
                    )
                }
                Switch(
                    checked = myplayerState.vibePopupEnabled,
                    onCheckedChange = { myplayerState.vibePopupEnabled = it }
                )
            }

            AnimatedVisibility(visible = myplayerState.vibePopupEnabled) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "POP-UP TIME INTERVAL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MyPlayerTextSecondary
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            1 to "1 min (Test)",
                            10 to "10 min",
                            15 to "15 min",
                            30 to "30 min"
                        ).forEach { (mins, label) ->
                            val selected = myplayerState.vibePopupIntervalMin == mins
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (selected) Color.White else MyPlayerSurfaceHigh,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { myplayerState.vibePopupIntervalMin = mins }
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.Black else Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "INSTANT PREVIEW (TAP TO TEST POP-UP NOW)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MyPlayerAccentGreen
                    )
                    Spacer(Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(MyPlayerVibeCategory.entries) { cat ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = cat.accentColor.copy(alpha = 0.20f),
                                border = BorderStroke(1.dp, cat.accentColor.copy(alpha = 0.65f)),
                                modifier = Modifier.clickable {
                                    MyPlayerVibeCheckManager.triggerPreviewPopup(cat)
                                }
                            ) {
                                Text(
                                    text = "${cat.emoji} ${cat.label}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MyPlayerPlaybackAndMoodSettingsScreen(
    myplayerState: MyPlayerUiStateHolder,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MyPlayerBlack).statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(42.dp).clip(CircleShape).background(MyPlayerSurface)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
            Spacer(Modifier.height(10.dp))
            Text("Playback &\nMood Pop-up", fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        item { MyPlayerFunnyVibeCheckSettingsCard(myplayerState = myplayerState) }
        item { Spacer(Modifier.height(90.dp)) }
    }
}
