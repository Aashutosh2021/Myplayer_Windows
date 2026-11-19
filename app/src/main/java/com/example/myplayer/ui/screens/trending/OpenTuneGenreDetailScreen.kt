package com.example.myplayer.ui.screens.trending

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplayer.data.online.InnertubeApi
import com.example.myplayer.data.online.model.OnlineSong
import com.example.myplayer.ui.common.AlbumArtImage
import com.example.myplayer.ui.theme.bouncyPopClick
import kotlinx.coroutines.launch

/**
 * OpenTune-style Genre / Mood Detail Subscreen (`MoodScreen` from `ExploreScreen.kt`):
 * - Circular glass back button (`<-`) + large `displaySmall` title (`Focus`, `Gaming`, `Workout`, `Chill`, etc.)
 * - Synchronized skeleton sweep shimmer (`OpenTuneShelfPlaceholder`) while loading
 * - Multi-section horizontal carousels (`LazyColumn` of `InnertubeApi.ExploreGenreShelf`)
 *   with `156.dp` square playlist/album cards (`OpenTuneShelfItemCard`)
 * - Clicking any playlist/album card opens `OpenTunePlaylistCollectionSubscreen` with full song list & playback!
 */
@Composable
fun OpenTuneGenreMoodDetailScreen(
    genreTitle: String,
    browseId: String? = null,
    params: String? = null,
    preferredLanguages: Set<String> = emptySet(),
    preferredGenres: Set<String> = emptySet(),
    preferredArtists: Set<String> = emptySet(),
    innertubeApi: InnertubeApi,
    currentPlayingVideoId: String? = null,
    isPlaying: Boolean = false,
    bottomPadding: Dp = 100.dp,
    onBack: () -> Unit,
    onPlaySongs: (List<OnlineSong>, Int) -> Unit
) {
    var selectedPlaylistItem by remember { mutableStateOf<InnertubeApi.ExploreShelfItem?>(null) }
    var shelves by remember(genreTitle, browseId, params, preferredLanguages, preferredGenres, preferredArtists) {
        mutableStateOf<List<InnertubeApi.ExploreGenreShelf>?>(
            innertubeApi.getCachedGenreShelves(
                genreTitle = genreTitle,
                preferredLanguages = preferredLanguages,
                preferredGenres = preferredGenres,
                preferredArtists = preferredArtists
            )
        )
    }
    var isLoading by remember(genreTitle, browseId, params, preferredLanguages, preferredGenres, preferredArtists) {
        mutableStateOf(shelves.isNullOrEmpty())
    }
    var errorMessage by remember(genreTitle) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadShelves(forceRefresh: Boolean = false) {
        scope.launch {
            if (forceRefresh || shelves.isNullOrEmpty()) {
                isLoading = true
                errorMessage = null
            }
            val result = runCatching {
                innertubeApi.fetchGenreOrMoodShelves(
                    genreTitle = genreTitle,
                    browseId = browseId,
                    params = params,
                    forceRefresh = forceRefresh,
                    preferredLanguages = preferredLanguages,
                    preferredGenres = preferredGenres,
                    preferredArtists = preferredArtists
                )
            }.getOrNull()

            if (!result.isNullOrEmpty()) {
                shelves = result
                errorMessage = null
            } else if (shelves.isNullOrEmpty()) {
                errorMessage = "Couldn't load playlists for $genreTitle. Check your connection and try again."
            }
            isLoading = false
        }
    }

    LaunchedEffect(genreTitle, browseId, params, preferredLanguages, preferredGenres, preferredArtists) {
        if (shelves.isNullOrEmpty()) {
            loadShelves(forceRefresh = false)
        }
    }

    // If a playlist/album card inside the genre/mood screen was clicked, show the Collection Song List screen
    val activeCollectionItem = selectedPlaylistItem
    if (activeCollectionItem != null) {
        OpenTunePlaylistCollectionSubscreen(
            item = activeCollectionItem,
            innertubeApi = innertubeApi,
            currentPlayingVideoId = currentPlayingVideoId,
            isPlaying = isPlaying,
            bottomPadding = bottomPadding,
            onBack = { selectedPlaylistItem = null },
            onPlaySongs = onPlaySongs
        )
        return
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        OpenTunePageHeader(
            title = genreTitle,
            onBack = onBack,
            actions = {
                Surface(
                    onClick = { loadShelves(forceRefresh = true) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh $genreTitle",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )

        when {
            isLoading && shelves.isNullOrEmpty() -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(3) {
                        OpenTuneShelfPlaceholder()
                    }
                }
            }

            !errorMessage.isNullOrBlank() && shelves.isNullOrEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomPadding),
                    contentAlignment = Alignment.Center
                ) {
                    OpenTuneErrorState(
                        message = errorMessage ?: "Couldn't load this genre",
                        onRetry = { loadShelves(forceRefresh = true) }
                    )
                }
            }

            else -> {
                val loadedShelves = shelves.orEmpty()
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomPadding + 28.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = loadedShelves,
                        key = { shelf -> "genre_shelf_${genreTitle}_${shelf.title}" }
                    ) { shelf ->
                        OpenTuneGenreShelfRow(
                            shelf = shelf,
                            onItemClick = { clickedItem ->
                                if (!clickedItem.videoId.isNullOrBlank() &&
                                    clickedItem.browseId.isNullOrBlank() &&
                                    clickedItem.seedQuery.isNullOrBlank()
                                ) {
                                    // Direct single song item
                                    val directSongs = shelf.items.mapNotNull { it.toOnlineSongOrNull() }
                                    if (directSongs.isNotEmpty()) {
                                        val idx = directSongs.indexOfFirst { it.videoId == clickedItem.videoId }.coerceAtLeast(0)
                                        onPlaySongs(directSongs, idx)
                                    } else {
                                        selectedPlaylistItem = clickedItem
                                    }
                                } else {
                                    // Open full Playlist / Album library song list!
                                    selectedPlaylistItem = clickedItem
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * OpenTune's `PageHeader` (`Glass.kt`):
 * Circular 56.dp glass back button at top-left + large `displaySmall` title below.
 */
@Composable
fun OpenTunePageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                OpenTuneGlassBackButton(onClick = onBack)
            } else {
                Box(Modifier.size(56.dp))
            }
            Box(Modifier.weight(1f))
            actions?.invoke()
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)
        )
    }
}

@Composable
fun OpenTuneGlassBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(size * 0.46f)
            )
        }
    }
}

/**
 * OpenTune's `Shelf` (`Components.kt`):
 * SectionHeader (title + optional strapline) + horizontally scrolling `LazyRow` of `ItemCard`s.
 */
@Composable
fun OpenTuneGenreShelfRow(
    shelf: InnertubeApi.ExploreGenreShelf,
    onItemClick: (InnertubeApi.ExploreShelfItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (shelf.subtitle.isNotBlank()) {
                    Text(
                        text = shelf.subtitle.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = shelf.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(
                items = shelf.items,
                key = { item -> "${shelf.title}_${item.title}_${item.browseId ?: item.videoId ?: item.seedQuery}" }
            ) { item ->
                OpenTuneShelfItemCard(
                    item = item,
                    onClick = { onItemClick(item) }
                )
            }
        }
    }
}

/**
 * OpenTune's `ItemCard` (`Components.kt`):
 * 156.dp wide card with 6.dp inset, 1:1 square rounded artwork (`MaterialTheme.shapes.medium`),
 * 2-line `titleSmall` title, and 1-line `bodySmall` subtitle (`"Playlist • YouTube Music"`).
 */
@Composable
fun OpenTuneShelfItemCard(
    item: InnertubeApi.ExploreShelfItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 156.dp
) {
    val cardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .width(width)
            .clip(cardShape)
            .bouncyPopClick(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            AlbumArtImage(
                uri = item.thumbnailUrl,
                title = item.title,
                artist = item.subtitle,
                size = width - 12.dp,
                shape = cardShape,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        if (item.subtitle.isNotBlank() || item.isExplicit) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isExplicit) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "E",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = item.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

/**
 * OpenTune's `CollectionScreen` (`BrowseScreens.kt`):
 * Opens when the user taps any playlist/album card inside a Genre/Mood Detail Subscreen
 * (or on the Explore board). Displays the playlist cover hero, title, subtitle, track count & total duration,
 * Shuffle + Play + Search buttons, and the full playable song list!
 */
@Composable
fun OpenTunePlaylistCollectionSubscreen(
    item: InnertubeApi.ExploreShelfItem,
    innertubeApi: InnertubeApi,
    currentPlayingVideoId: String? = null,
    isPlaying: Boolean = false,
    bottomPadding: Dp = 100.dp,
    onBack: () -> Unit,
    onPlaySongs: (List<OnlineSong>, Int) -> Unit
) {
    BackHandler(onBack = onBack)

    var collectionPage by remember(item) {
        mutableStateOf<InnertubeApi.ExploreCollectionPage?>(
            innertubeApi.getCachedCollectionPage(item)
        )
    }
    var isLoading by remember(item) { mutableStateOf(collectionPage == null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortOrder by rememberSaveable { mutableStateOf(0) } // 0 = Default, 1 = A-Z, 2 = Z-A
    var sortMenuOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scrolledPastHeader by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 280 }
    }

    LaunchedEffect(item) {
        if (collectionPage == null) {
            isLoading = true
            collectionPage = runCatching {
                innertubeApi.fetchCollectionSongs(item)
            }.getOrNull()
            isLoading = false
        }
    }

    val songs = collectionPage?.songs.orEmpty()
    val displayedSongs = remember(songs, searchQuery, sortOrder) {
        val q = searchQuery.trim()
        val filtered = if (q.isEmpty()) songs else {
            songs.filter {
                it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
            }
        }
        when (sortOrder) {
            1 -> filtered.sortedBy { it.title.lowercase() }
            2 -> filtered.sortedByDescending { it.title.lowercase() }
            else -> filtered
        }
    }

    val heroCover = collectionPage?.thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: item.thumbnailUrl.takeIf { it.isNotBlank() }
        ?: songs.firstOrNull()?.thumbnailUrl.orEmpty()
    val displayTitle = collectionPage?.title?.takeIf { it.isNotBlank() } ?: item.title
    val displaySubtitle = collectionPage?.subtitle?.takeIf { it.isNotBlank() } ?: item.subtitle

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomPadding + 32.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Hero Header (Cover + Title + Meta + Shuffle/Play/Search Row) ──
            item(key = "collection_hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 68.dp, start = 24.dp, end = 24.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val coverShape = RoundedCornerShape(20.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.66f)
                            .aspectRatio(1f)
                            .shadow(22.dp, coverShape)
                            .clip(coverShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        if (heroCover.isNotBlank()) {
                            AlbumArtImage(
                                uri = heroCover,
                                title = displayTitle,
                                artist = displaySubtitle,
                                size = 240.dp,
                                shape = coverShape,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (displaySubtitle.isNotBlank()) {
                        Text(
                            text = displaySubtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    val metaLine = remember(songs) { formatCollectionMeta(songs) }
                    if (metaLine.isNotBlank()) {
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // OpenTune ListPlayRow: Round Shuffle (56dp) + Wide Play Button (56dp) + Round Search Button (56dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = {
                                val listToPlay = displayedSongs.ifEmpty { songs }
                                if (listToPlay.isNotEmpty()) {
                                    onPlaySongs(listToPlay.shuffled(), 0)
                                }
                            },
                            enabled = songs.isNotEmpty(),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val listToPlay = displayedSongs.ifEmpty { songs }
                                if (listToPlay.isNotEmpty()) {
                                    onPlaySongs(listToPlay, 0)
                                }
                            },
                            enabled = songs.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Play",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Surface(
                            onClick = {
                                searchOpen = !searchOpen
                                if (!searchOpen) searchQuery = ""
                            },
                            shape = CircleShape,
                            color = if (searchOpen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                                    contentDescription = "Search this list",
                                    tint = if (searchOpen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = searchOpen) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                                .height(52.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search this list",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        }
                    }
                }
            }

            // ── Loading / Empty / Song Rows ──
            if (isLoading && songs.isEmpty()) {
                items(8) {
                    OpenTuneSongRowPlaceholder()
                }
            } else if (displayedSongs.isEmpty()) {
                item(key = "empty_collection") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching songs in this playlist" else "No songs found in this playlist",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = displayedSongs,
                    key = { index, song -> "col_song_${index}_${song.videoId}" }
                ) { index, song ->
                    val isCurrent = currentPlayingVideoId != null &&
                        currentPlayingVideoId.removePrefix("online://") == song.videoId.removePrefix("online://")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPlaySongs(displayedSongs, index)
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(modifier = Modifier.size(52.dp)) {
                            AlbumArtImage(
                                uri = song.thumbnailUrl,
                                title = song.title,
                                artist = song.artist,
                                size = 52.dp,
                                shape = RoundedCornerShape(10.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            val subText = buildString {
                                append(song.artist.ifBlank { "YouTube Music" })
                                val dur = song.durationText.ifBlank {
                                    if (song.durationMs > 0) {
                                        val s = (song.durationMs / 1000).coerceAtLeast(0)
                                        "%d:%02d".format(s / 60, s % 60)
                                    } else ""
                                }
                                if (dur.isNotBlank()) append(" • $dur")
                            }
                            Text(
                                text = subText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        IconButton(
                            onClick = { onPlaySongs(displayedSongs, index) },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play ${song.title}",
                                tint = if (isCurrent && isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Collapsing Top Bar Overlay (`TopNavBar` / `CollapsingBar`) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (scrolledPastHeader) MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
                    else Color.Transparent
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OpenTuneGlassBackButton(onClick = onBack, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            AnimatedVisibility(
                visible = scrolledPastHeader,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!scrolledPastHeader) {
                Spacer(Modifier.weight(1f))
            }
            Box {
                Surface(
                    onClick = { sortMenuOpen = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    val options = listOf(
                        0 to "Default order",
                        1 to "Alphabetical (A to Z)",
                        2 to "Alphabetical (Z to A)"
                    )
                    options.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = {
                                if (sortOrder == code) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                sortOrder = code
                                sortMenuOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * OpenTune's synchronized skeleton shimmer block (`Placeholder` in `Components.kt`).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun OpenTuneShimmerPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp)
) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val sheen = Color.White.copy(alpha = if (dark) 0.08f else 0.55f)
    val screenPx = LocalWindowInfo.current.containerSize.width.toFloat().coerceAtLeast(800f)
    var left by remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "opentune_skeleton")
    val tick = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeleton_tick"
    )
    Box(
        modifier = modifier
            .onGloballyPositioned { left = it.positionInWindow().x }
            .clip(shape)
            .drawBehind {
                drawRect(base)
                tick.value
                val band = 220.dp.toPx()
                val phase = (SystemClock.uptimeMillis() % 1500L) / 1500f
                val x = -band + phase * (screenPx + band * 2) - left
                drawRect(
                    Brush.linearGradient(
                        0f to sheen.copy(alpha = 0f),
                        0.5f to sheen,
                        1f to sheen.copy(alpha = 0f),
                        start = Offset(x - band / 2f, 0f),
                        end = Offset(x + band / 2f, size.height * 0.35f)
                    )
                )
            }
    )
}

/**
 * OpenTune's `ShelfPlaceholder` (`Components.kt` lines 661–673):
 * Matches 00:08 and 00:13 in the user's screen recording!
 */
@Composable
fun OpenTuneShelfPlaceholder() {
    Column(Modifier.padding(vertical = 12.dp)) {
        OpenTuneShimmerPlaceholder(
            modifier = Modifier
                .padding(start = 16.dp)
                .width(160.dp)
                .height(22.dp),
            shape = RoundedCornerShape(8.dp)
        )
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            repeat(3) {
                Column {
                    OpenTuneShimmerPlaceholder(
                        modifier = Modifier.size(144.dp),
                        shape = RoundedCornerShape(14.dp)
                    )
                    OpenTuneShimmerPlaceholder(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(110.dp)
                            .height(14.dp),
                        shape = RoundedCornerShape(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OpenTuneSongRowPlaceholder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OpenTuneShimmerPlaceholder(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(10.dp)
        )
        Column(Modifier.padding(start = 14.dp)) {
            OpenTuneShimmerPlaceholder(
                modifier = Modifier
                    .width(180.dp)
                    .height(14.dp),
                shape = RoundedCornerShape(6.dp)
            )
            OpenTuneShimmerPlaceholder(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(110.dp)
                    .height(12.dp),
                shape = RoundedCornerShape(6.dp)
            )
        }
    }
}

@Composable
private fun OpenTuneErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(
            text = "Couldn't load this",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}

private fun formatCollectionMeta(songs: List<OnlineSong>): String {
    if (songs.isEmpty()) return ""
    val totalSec = songs.sumOf { it.durationMs } / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val durPart = when {
        totalSec <= 0L -> null
        hours > 0L -> "${hours}h ${mins}m"
        else -> "${mins.coerceAtLeast(1L)}m"
    }
    return listOfNotNull("${songs.size} songs", durPart).joinToString(" • ")
}