package com.example.myplayer.ui.screens.trending

import kotlin.math.abs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myplayer.R
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.recommendation.model.RecommendationSong
import com.example.myplayer.ui.common.AlbumArtImage
import com.example.myplayer.ui.screens.home.HomeViewModel
import com.example.myplayer.ui.screens.home.TasteOnboardingDialog
import com.example.myplayer.ui.theme.NeonLimePrimary
import com.example.myplayer.ui.theme.ThemeState
import com.example.myplayer.ui.theme.bouncyPopClick
import com.example.myplayer.ui.theme.slidePopIn

private sealed interface ExploreBoardTile {
    val key: String
    data class MoodCard(
        override val key: String,
        val title: String,
        val subtitle: String,
        val browseId: String = "",
        val params: String? = null,
        val isGenre: Boolean = false,
        val coverUrl: String?,
        val heightDp: Dp
    ) : ExploreBoardTile

    data class TrackPinCard(
        override val key: String,
        val badge: String,
        val song: RecommendationSong,
        val indexInHotlist: Int,
        val aspectRatio: Float
    ) : ExploreBoardTile
}

private data class DefaultMoodGenreSeed(
    val title: String,
    val subtitle: String,
    val isGenre: Boolean
)

private val EXPLORE_MOODS_AND_GENRES = listOf(
    DefaultMoodGenreSeed("Chill", "Lo-Fi, Coffee Shop & Calm", false),
    DefaultMoodGenreSeed("Commute", "Morning Drive & Transit", false),
    DefaultMoodGenreSeed("Energize", "Upbeat Feel-Good Anthems", false),
    DefaultMoodGenreSeed("Feel good", "Sunshine & Positive Vibes", false),
    DefaultMoodGenreSeed("Focus", "Flow State, Classical & Study", false),
    DefaultMoodGenreSeed("Gaming", "Gaming Hits, Phonk & Soundtracks", false),
    DefaultMoodGenreSeed("Party", "Club Hits, Dance & EDM", false),
    DefaultMoodGenreSeed("Workout", "Gym, High Tempo & Power", false),
    DefaultMoodGenreSeed("Romance", "Love Songs & Acoustic", false),
    DefaultMoodGenreSeed("Sad", "Late Night & Heartbreak", false),
    DefaultMoodGenreSeed("Sleep", "Ambient & Peaceful Rest", false),
    DefaultMoodGenreSeed("Bollywood", "Top Hindi & Desi Hits", true),
    DefaultMoodGenreSeed("Punjabi", "Punjabi Pop & Hip-Hop", true),
    DefaultMoodGenreSeed("Pop", "Global Chart Toppers", true),
    DefaultMoodGenreSeed("Hip Hop", "Rap, Trap & Beats", true),
    DefaultMoodGenreSeed("Indie", "Fresh Indie & Underground", true),
    DefaultMoodGenreSeed("Lo-Fi", "Chillhop & Late Night Beats", true),
    DefaultMoodGenreSeed("Rock", "Classic & Alternative Rock", true),
    DefaultMoodGenreSeed("Classical", "Orchestral, Piano & Cello", true),
    DefaultMoodGenreSeed("Jazz", "Smooth Jazz & Cafe Sessions", true),
    DefaultMoodGenreSeed("Deep Focus", "Drone & Binaural Flow", false),
    DefaultMoodGenreSeed("Acoustic Chill", "Warm Strings & Unplugged", false),
    DefaultMoodGenreSeed("Late Night", "Synthwave & After Hours", false),
    DefaultMoodGenreSeed("Discoveries", "Fresh Underground Gems", true)
)

private val STAGGERED_HEIGHTS = listOf(148.dp, 204.dp, 174.dp, 236.dp, 138.dp, 192.dp)
private val CARD_ASPECTS = listOf(1.0f, 0.82f, 1.16f, 0.90f)

@Composable
fun TrendingScreen(
    onNavigateToNowPlaying: () -> Unit,
    onNavigateToSyncPlay: () -> Unit = {},
    onNavigateToDualBud: () -> Unit = {},
    bottomPadding: Dp = 100.dp,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val recommendations by viewModel.hotlistSongs.collectAsStateWithLifecycle()
    val homeRecommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingHotlist.collectAsStateWithLifecycle()
    val selectedLanguages by viewModel.selectedLanguages.collectAsStateWithLifecycle()
    val selectedGenres by viewModel.selectedGenres.collectAsStateWithLifecycle()
    val selectedArtists by viewModel.selectedArtists.collectAsStateWithLifecycle()
    val showTasteOnboarding by viewModel.showTasteOnboarding.collectAsStateWithLifecycle()
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val currentOnlineSong by viewModel.musicController.currentOnlineSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()

    var selectedFilter by rememberSaveable { mutableStateOf("For you") }
    var selectedArtistFilter by rememberSaveable { mutableStateOf<String?>(null) }

    // Active Genre/Mood Detail Subscreen state (null = show Explore board)
    var activeMoodGenre by remember { mutableStateOf<ExploreBoardTile.MoodCard?>(null) }

    // Fetch live YouTube Music Moods & Genres metadata so official browseId + params are used
    var remoteMoodsAndGenres by remember { mutableStateOf<List<InnertubeApi.ExploreMoodGenre>>(emptyList()) }
    LaunchedEffect(Unit) {
        val fetched = runCatching { viewModel.innertubeApi.fetchMoodsAndGenres() }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            remoteMoodsAndGenres = fetched
        }
    }

    // If the user tapped a Mood or Genre card (e.g., Focus, Gaming, Workout, Chill), open OpenTuneGenreMoodDetailScreen!
    val currentMood = activeMoodGenre
    if (currentMood != null) {
        OpenTuneGenreMoodDetailScreen(
            genreTitle = currentMood.title,
            browseId = currentMood.browseId.takeIf { it.isNotBlank() },
            params = currentMood.params,
            preferredLanguages = selectedLanguages,
            preferredGenres = selectedGenres,
            preferredArtists = selectedArtists,
            innertubeApi = viewModel.innertubeApi,
            currentPlayingVideoId = currentOnlineSong?.videoId ?: currentSong?.videoId ?: currentSong?.id,
            isPlaying = isPlaying,
            bottomPadding = bottomPadding,
            onBack = { activeMoodGenre = null },
            onPlaySongs = { songs, startIndex ->
                viewModel.playOnlineSongs(songs, startIndex)
                onNavigateToNowPlaying()
            }
        )
        return
    }

    if (showTasteOnboarding) {
        TasteOnboardingDialog(
            initialLanguages = selectedLanguages,
            initialGenres = selectedGenres,
            initialArtists = selectedArtists,
            onDismiss = { viewModel.dismissTasteSelector() },
            onSave = { langs, genres, artists ->
                viewModel.saveUserTastePreferences(langs, genres, artists)
            }
        )
    }


    val allPool = remember(recommendations, homeRecommendations) {
        (recommendations + homeRecommendations).distinctBy { it.videoId }
    }

    // Track individual song thumbnails so Mood/Genre cards never duplicate them
    val trackThumbnailSet = remember(allPool) {
        allPool.mapNotNull { it.thumbnailUrl.takeIf { u -> u.isNotBlank() } }.toSet()
    }

    // Merge default moods/genres with live YouTube Music moods/genres
    // coverUrl is intentionally null so each Mood/Genre tile fetches its OWN cover by name/genre
    val mergedMoodsAndGenres = remember(remoteMoodsAndGenres) {
        val out = mutableListOf<ExploreBoardTile.MoodCard>()
        val seen = HashSet<String>()

        EXPLORE_MOODS_AND_GENRES.forEach { seed ->
            val remoteMatch = remoteMoodsAndGenres.firstOrNull {
                it.title.equals(seed.title, ignoreCase = true)
            }
            if (seen.add(seed.title.lowercase())) {
                out.add(
                    ExploreBoardTile.MoodCard(
                        key = "mood_card_${seed.title}",
                        title = seed.title,
                        subtitle = seed.subtitle,
                        browseId = remoteMatch?.browseId.orEmpty(),
                        params = remoteMatch?.params,
                        isGenre = remoteMatch?.isGenre ?: seed.isGenre,
                        coverUrl = null,
                        heightDp = STAGGERED_HEIGHTS[abs(seed.title.hashCode()) % STAGGERED_HEIGHTS.size]
                    )
                )
            }
        }

        remoteMoodsAndGenres.forEach { remote ->
            if (seen.add(remote.title.lowercase())) {
                out.add(
                    ExploreBoardTile.MoodCard(
                        key = "mood_remote_${remote.title}_${remote.params}",
                        title = remote.title,
                        subtitle = remote.subtitle.ifBlank { if (remote.isGenre) "Genre" else "Mood" },
                        browseId = remote.browseId,
                        params = remote.params,
                        isGenre = remote.isGenre,
                        coverUrl = null,
                        heightDp = STAGGERED_HEIGHTS[abs(remote.title.hashCode()) % STAGGERED_HEIGHTS.size]
                    )
                )
            }
        }
        out
    }

    // Build Artist Pills from user's selected artists + trending artists
    val artistPills = remember(selectedArtists, allPool) {
        val fromTracks = allPool.map { it.artist.substringBefore(",").substringBefore("&").trim() }
            .filter { it.isNotBlank() }
        val combined = (selectedArtists.toList() + fromTracks).distinct()
        combined.take(14).map { artistName ->
            val art = allPool.firstOrNull {
                it.artist.contains(artistName, ignoreCase = true)
            }?.thumbnailUrl
            artistName to art
        }
    }

    // Build OpenTune Staggered Board tiles (interleaving Mood tiles and Track Pin cards)
    val boardTiles: List<ExploreBoardTile> = remember(allPool, mergedMoodsAndGenres, selectedFilter, selectedArtistFilter) {
        if (selectedArtistFilter != null) {
            val filtered = allPool.filter {
                it.artist.contains(selectedArtistFilter!!, ignoreCase = true) ||
                    it.title.contains(selectedArtistFilter!!, ignoreCase = true)
            }.ifEmpty { allPool }
            filtered.mapIndexed { idx, song ->
                ExploreBoardTile.TrackPinCard(
                    key = "artist_track_${song.videoId}_$idx",
                    badge = selectedArtistFilter!!,
                    song = song,
                    indexInHotlist = idx,
                    aspectRatio = CARD_ASPECTS[abs(song.videoId.hashCode()) % CARD_ASPECTS.size]
                )
            }
        } else {
            when (selectedFilter) {
                "Moods", "Moods & genres" -> {
                    mergedMoodsAndGenres.filter { !it.isGenre || selectedFilter == "Moods & genres" }
                        .ifEmpty { mergedMoodsAndGenres }
                }
                "Genres" -> {
                    mergedMoodsAndGenres.filter { it.isGenre }.ifEmpty { mergedMoodsAndGenres }
                }
                "New releases" -> {
                    allPool.reversed().mapIndexed { idx, song ->
                        ExploreBoardTile.TrackPinCard(
                            key = "new_rel_${song.videoId}_$idx",
                            badge = if (idx < 5) "New release" else "Fresh Pick",
                            song = song,
                            indexInHotlist = idx,
                            aspectRatio = CARD_ASPECTS[abs(song.videoId.hashCode()) % CARD_ASPECTS.size]
                        )
                    }
                }
                "Charts" -> {
                    allPool.mapIndexed { idx, song ->
                        ExploreBoardTile.TrackPinCard(
                            key = "chart_pin_${song.videoId}_$idx",
                            badge = "Chart #${idx + 1}",
                            song = song,
                            indexInHotlist = idx,
                            aspectRatio = CARD_ASPECTS[abs(song.videoId.hashCode()) % CARD_ASPECTS.size]
                        )
                    }
                }
                else -> {
                    // "For you": Pinterest-style interleaved Mood/Genre tiles + Chart/New Release BoardCards
                    buildList<ExploreBoardTile> {
                        val maxLen = maxOf(mergedMoodsAndGenres.size, allPool.size)
                        for (i in 0 until maxLen) {
                            if (i < mergedMoodsAndGenres.size && i % 2 == 0) {
                                add(mergedMoodsAndGenres[i])
                            }
                            if (i < allPool.size) {
                                val song = allPool[i]
                                val badge = when {
                                    i % 3 == 0 -> "Chart"
                                    i % 3 == 1 -> "New release"
                                    else -> "Top ${i + 1}"
                                }
                                add(
                                    ExploreBoardTile.TrackPinCard(
                                        key = "board_song_${song.videoId}_$i",
                                        badge = badge,
                                        song = song,
                                        indexInHotlist = i,
                                        aspectRatio = CARD_ASPECTS[abs(song.videoId.hashCode()) % CARD_ASPECTS.size]
                                    )
                                )
                            }
                            if (i < mergedMoodsAndGenres.size && i % 2 == 1) {
                                add(mergedMoodsAndGenres[i])
                            }
                        }
                    }
                }
            }
        }
    }

    val filterTabs = listOf("For you", "Moods", "Genres", "New releases", "Charts", "Hotlist Charts")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // ── Top Header (OpenTune Explore + MyPlayer Hotlist) ────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.inside_logo),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp)
                )
                Column {
                    Text(
                        text = "DISCOVER • HOTLIST",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "Explore",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { viewModel.openTasteSelector() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "Change Taste",
                            tint = NeonLimePrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Change Taste",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (ThemeState.isDarkMode) Color(0xFF332024) else Color(0xFFFDE8E8))
                        .clickable { viewModel.refreshTrendingMusic() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalFireDepartment,
                        contentDescription = "Refresh Explore & Hotlist",
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Filter Pills Row (For you | Moods | Genres | New releases | Charts | Hotlist Charts) ──
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filterTabs) { tab ->
                val isSelected = selectedFilter == tab && selectedArtistFilter == null
                val bg by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                    label = "exploreFilterBg_$tab"
                )
                val fg by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    label = "exploreFilterFg_$tab"
                )
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(bg)
                        .clickable {
                            selectedArtistFilter = null
                            selectedFilter = tab
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = fg
                    )
                }
            }
        }

        when {
            selectedFilter == "Hotlist Charts" -> {
                // Preserved MyPlayer World Trending Hotlist Hero + Ranked #01-#50 List
                if (isLoading && recommendations.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                } else if (recommendations.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No trending songs available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                } else {
                    val firstTrack = recommendations.firstOrNull()
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = bottomPadding + 24.dp, top = 4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(92.dp)
                                        .shadow(8.dp, RoundedCornerShape(20.dp), spotColor = Color(0x22000000))
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xFF262C38)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AlbumArtImage(
                                        uri = firstTrack?.thumbnailUrl,
                                        title = firstTrack?.title ?: "Trending",
                                        artist = firstTrack?.artist ?: "Hotlist",
                                        size = 92.dp,
                                        shape = RoundedCornerShape(20.dp),
                                        iconSize = 36.dp
                                    )
                                }

                                Spacer(Modifier.width(18.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Your Choices",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-0.3).sp
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = "Hotlist • ${recommendations.size} tracks",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = "Updated Daily",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.playRecommendedSongs(recommendations, 0)
                                        onNavigateToNowPlaying()
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Play", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }

                                Button(
                                    onClick = {
                                        val shuffled = recommendations.shuffled()
                                        viewModel.playRecommendedSongs(shuffled, 0)
                                        onNavigateToNowPlaying()
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Shuffle", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }

                        itemsIndexed(
                            items = recommendations,
                            key = { index, song -> "${song.videoId}_$index" }
                        ) { index, song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .slidePopIn(index + 1)
                                    .bouncyPopClick {
                                        viewModel.playRecommendedSongs(recommendations, index)
                                        onNavigateToNowPlaying()
                                    }
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "%02d".format(index + 1),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    ),
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.width(30.dp)
                                )
                                AlbumArtImage(
                                    uri = song.thumbnailUrl,
                                    title = song.title,
                                    artist = song.artist,
                                    size = 46.dp,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${song.artist} • ${formatDuration(song.durationMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                // ── OpenTune Pinterest-Style Staggered Masonry Explore Board ──
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = 6.dp,
                        bottom = bottomPadding + 28.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 1. Quick Feature Hub Cards (Recognize | Together | DualBud)
                    item(span = StaggeredGridItemSpan.FullLine, key = "quick_feature_hub") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (com.example.myplayer.BuildConfig.DEBUG) {
                                ExploreFeatureActionCard(
                                    icon = Icons.Filled.GraphicEq,
                                    title = "Recognize",
                                    subtitle = "Identify any song",
                                    accent = Color(0xFF8B5CF6),
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { viewModel.openTasteSelector() }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ExploreFeatureActionCard(
                                    icon = Icons.Filled.Groups,
                                    title = "Together",
                                    subtitle = "Listen with friends",
                                    accent = Color(0xFFF59E0B),
                                    modifier = Modifier.weight(1f),
                                    onClick = onNavigateToSyncPlay
                                )
                                ExploreFeatureActionCard(
                                    icon = Icons.Filled.Headphones,
                                    title = "DualBud",
                                    subtitle = "Split L/R audio",
                                    accent = Color(0xFFEC4899),
                                    modifier = Modifier.weight(1f),
                                    onClick = onNavigateToDualBud
                                )
                            }
                        }
                    }

                    // 2. OpenTune Artist Pills Row
                    if (artistPills.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine, key = "artist_pills_row") {
                            LazyRow(
                                contentPadding = PaddingValues(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(artistPills, key = { it.first }) { (artistName, thumbUrl) ->
                                    val isPicked = selectedArtistFilter == artistName
                                    Row(
                                        modifier = Modifier
                                            .height(44.dp)
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(
                                                if (isPicked) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceContainerHigh
                                            )
                                            .clickable {
                                                selectedArtistFilter = if (isPicked) null else artistName
                                            }
                                            .padding(start = 5.dp, end = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!thumbUrl.isNullOrBlank()) {
                                                AlbumArtImage(
                                                    uri = thumbUrl,
                                                    title = artistName,
                                                    artist = artistName,
                                                    size = 34.dp,
                                                    shape = CircleShape
                                                )
                                            } else {
                                                Text(
                                                    text = artistName.take(1).uppercase(),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                        Text(
                                            text = artistName,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isPicked) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .widthIn(max = 140.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Staggered Mood Tiles & Board Track Cards
                    items(boardTiles, key = { it.key }) { tile ->
                        when (tile) {
                            is ExploreBoardTile.MoodCard -> {
                                OpenTuneMoodTile(
                                    title = tile.title,
                                    subtitle = tile.subtitle,
                                    browseId = tile.browseId,
                                    params = tile.params,
                                    initialCoverUrl = tile.coverUrl,
                                    avoidCoverUrls = trackThumbnailSet,
                                    innertubeApi = viewModel.innertubeApi,
                                    height = tile.heightDp,
                                    onClick = {
                                        // Open OpenTune-style Genre/Mood Detail Subscreen!
                                        activeMoodGenre = tile
                                    }
                                )
                            }
                            is ExploreBoardTile.TrackPinCard -> {
                                OpenTuneBoardTrackCard(
                                    badge = tile.badge,
                                    song = tile.song,
                                    aspectRatio = tile.aspectRatio,
                                    onClick = {
                                        val listToPlay = allPool.ifEmpty { listOf(tile.song) }
                                        val idx = listToPlay.indexOfFirst { it.videoId == tile.song.videoId }.coerceAtLeast(0)
                                        viewModel.playRecommendedSongs(listToPlay, idx)
                                        onNavigateToNowPlaying()
                                    }
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
private fun ExploreFeatureActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.bouncyPopClick(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * OpenTune Mood Tile (`ExploreScreen.kt` lines 324–355):
 * Lazily fetches the first cover from the mood's playlists behind a scrim in the mood's own HSV color,
 * with the bold mood/genre name at the bottom-left. Clicking opens `OpenTuneGenreMoodDetailScreen`.
 */
private fun moodGenreFallbackIcon(title: String): ImageVector {
    val lower = title.lowercase()
    return when {
        "workout" in lower || "gym" in lower || "fitness" in lower -> Icons.Filled.FitnessCenter
        "chill" in lower || "lo-fi" in lower || "lofi" in lower || "calm" in lower -> Icons.Filled.Spa
        "commute" in lower || "drive" in lower || "road" in lower -> Icons.Filled.DirectionsCar
        "energize" in lower || "energy" in lower || "pump" in lower -> Icons.Filled.Bolt
        "party" in lower || "dance" in lower || "club" in lower -> Icons.Filled.Nightlife
        "gaming" in lower || "game" in lower -> Icons.Filled.SportsEsports
        "focus" in lower || "study" in lower || "classical" in lower -> Icons.Filled.AutoStories
        "sleep" in lower || "night" in lower || "ambient" in lower -> Icons.Filled.Bedtime
        "romance" in lower || "love" in lower -> Icons.Filled.Favorite
        "sad" in lower || "heartbreak" in lower -> Icons.Filled.WaterDrop
        "feel good" in lower || "happy" in lower -> Icons.Filled.WbSunny
        else -> Icons.Filled.MusicNote
    }
}

@Composable
private fun OpenTuneMoodTile(
    title: String,
    subtitle: String,
    browseId: String,
    params: String?,
    initialCoverUrl: String?,
    avoidCoverUrls: Set<String> = emptySet(),
    innertubeApi: InnertubeApi,
    height: Dp,
    onClick: () -> Unit
) {
    val hue = (abs(title.hashCode()) % 360).toFloat()
    val moodColor = Color.hsv(hue, 0.65f, 0.55f)
    val moodAccent = Color.hsv((hue + 28f) % 360f, 0.72f, 0.34f)
    var resolvedCover by remember(title, browseId, params) {
        mutableStateOf(initialCoverUrl?.takeIf { it.isNotBlank() && it !in avoidCoverUrls })
    }

    LaunchedEffect(title, browseId, params) {
        if (resolvedCover.isNullOrBlank()) {
            resolvedCover = runCatching {
                innertubeApi.fetchFirstCoverForMood(
                    genreTitle = title,
                    browseId = browseId.takeIf { it.isNotBlank() },
                    params = params,
                    avoidUrls = avoidCoverUrls
                )
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(moodColor, moodAccent)))
            .bouncyPopClick(onClick = onClick)
    ) {
        // Thematic decorative icon watermark visible while loading or when offline
        Icon(
            imageVector = moodGenreFallbackIcon(title),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.18f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(56.dp)
        )
        AnimatedVisibility(
            visible = !resolvedCover.isNullOrBlank(),
            enter = fadeIn(tween(450)),
            modifier = Modifier.fillMaxSize()
        ) {
            AlbumArtImage(
                uri = resolvedCover,
                title = title,
                artist = subtitle,
                size = height,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            moodColor.copy(alpha = 0.08f),
                            moodColor.copy(alpha = 0.58f),
                            moodColor.copy(alpha = 0.95f)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** OpenTune Staggered Board Card: variable aspect ratio cover + top-left kind pill + title & artist below. */
@Composable
private fun OpenTuneBoardTrackCard(
    badge: String,
    song: RecommendationSong,
    aspectRatio: Float,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyPopClick(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            AlbumArtImage(
                uri = song.thumbnailUrl,
                title = song.title,
                artist = song.artist,
                size = 180.dp,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Text(
            text = song.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)
        )
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}