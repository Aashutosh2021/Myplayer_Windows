package com.example.myplayer.ui.screens.nowplaying

import android.content.Context
import android.os.PowerManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.example.myplayer.data.local.entity.PlaylistEntity
import com.example.myplayer.playback.AudioQualityInfo
import com.example.myplayer.playback.PlaybackSourceContext
import com.example.myplayer.ui.common.AlbumArtImage
import com.example.myplayer.ui.myplayer.*

/**
 * Windows Desktop 50/50 Split-Screen NowPlaying Layout:
 * - Left Half (~46% width): Always displays Suggestions / Recommended / Downloaded / Queue / Playlist tracks!
 * - Right Half (~54% width): Displays the active NowPlaying Player Style (Radial Desktop Hero, Turntable,
 *   Poster, Timeline, Lyrics Stage, or Default) properly scaled and centered for the Windows half-screen pane,
 *   with quick `<` / `>` style cycle buttons and a 1-click "Styles" button in the header!
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    title: String?,
    artist: String?,
    artUri: String?,
    durationMs: Long,
    isPlaying: Boolean,
    positionState: State<Long>,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onBackClick: () -> Unit,
    sleepTimerRemaining: Long = -1L,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onStartSleepTimer: (Int) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    isShuffleOn: Boolean = false,
    onToggleShuffle: () -> Unit = {},
    repeatMode: Int = 0,
    onCycleRepeatMode: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    canDownload: Boolean = true,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onDownloadClick: () -> Unit = {},
    onRemoveDownloadClick: () -> Unit = {},
    audioQuality: AudioQualityInfo = AudioQualityInfo(),
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
    // Context-Aware Side Track List:
    sideTracks: List<SideTrackItem> = emptyList(),
    sideTrackTitle: String = "Recommended",
    playbackContext: PlaybackSourceContext = PlaybackSourceContext.RECOMMENDED,
    onSideTrackClick: (SideTrackItem) -> Unit = {},
    onContextChange: (PlaybackSourceContext) -> Unit = {},
    playlists: List<PlaylistEntity> = emptyList(),
    onAddToPlaylist: (Long) -> Unit = {},
    onAddTrackToPlaylist: (Long, SideTrackItem) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String) -> Unit = {},
    playerVolume: Float = 1.0f,
    onVolumeChange: (Float) -> Unit = {},
    myplayerState: MyPlayerUiStateHolder = rememberMyPlayerUiStateHolder(),
    onOpenCustomizeStyles: () -> Unit = {}
) {
    var showSleepTimer by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showQualityDetails by remember { mutableStateOf(false) }
    var showCustomLyricsDialog by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showContextDropdown by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showLyricsView by remember { mutableStateOf(false) }
    var selectedSideTrackForPlaylist by remember { mutableStateOf<SideTrackItem?>(null) }
    var lastNonZeroVolume by remember { mutableFloatStateOf(if (playerVolume > 0f) playerVolume else 1.0f) }

    val onToggleMute = {
        if (playerVolume > 0f) {
            lastNonZeroVolume = playerVolume
            onVolumeChange(0f)
        } else {
            onVolumeChange(if (lastNonZeroVolume > 0f) lastNonZeroVolume else 1.0f)
        }
    }

    val allStyles = remember { PlayerStyleMode.entries }
    val cycleStyle: (Int) -> Unit = { delta ->
        val currentIdx = allStyles.indexOf(myplayerState.playerStyle).coerceAtLeast(0)
        val nextIdx = (currentIdx + delta + allStyles.size) % allStyles.size
        myplayerState.updatePlayerStyle(allStyles[nextIdx])
    }

    val hasSong = !title.isNullOrBlank()
    val duration = durationMs.coerceAtLeast(1L)

    // Dialogs
    if (showSleepTimer) {
        SleepTimerDialog(
            currentRemaining = sleepTimerRemaining,
            onDismiss = { showSleepTimer = false },
            onStart = { minutes -> onStartSleepTimer(minutes); showSleepTimer = false },
            onCancel = { onCancelSleepTimer(); showSleepTimer = false }
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove Download?", color = Color.White) },
            text = { Text("This will remove the downloaded audio from your device.", color = Color(0xFFD0D4DC)) },
            confirmButton = {
                TextButton(
                    onClick = { onRemoveDownloadClick(); showRemoveConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel", color = Color.White) }
            },
            containerColor = Color(0xFF1E222B)
        )
    }

    if (showCustomLyricsDialog) {
        val lyricsState by lyricsViewModel.lyricsState.collectAsStateWithLifecycle()
        val currentLyrics = when (val state = lyricsState) {
            is LyricsUiState.Found -> state.result.syncedLyrics ?: state.result.plainLyrics ?: ""
            else -> ""
        }
        val isCustomPresent = lyricsState is LyricsUiState.Found
        CustomLyricsDialog(
            initialLyrics = currentLyrics,
            trackTitle = title,
            trackArtist = artist,
            onDismiss = { showCustomLyricsDialog = false },
            onSave = { lyricsViewModel.saveCustomLyrics(it) },
            onDelete = if (isCustomPresent) ({ lyricsViewModel.deleteLyrics() }) else null
        )
    }

    val keepAwakeLyrics by lyricsViewModel.keepScreenAwakeLyrics.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(keepAwakeLyrics) {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyPlayer:NowPlayingLyricsKeepAwake")
        wakeLock.setReferenceCounted(false)
        if (keepAwakeLyrics) wakeLock.acquire()
        onDispose {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    if (showQualityDetails) {
        AlertDialog(
            onDismissRequest = { showQualityDetails = false },
            title = { Text("Audio Quality Details", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quality Badge: ${audioQuality.displayBadge}", color = Color(0xFF4C8DFF), fontWeight = FontWeight.SemiBold)
                    Text("Format / Source: ${audioQuality.format.ifBlank { "High-Definition Audio" }}", color = Color(0xFFD0D4DC))
                    if ((audioQuality.bitrateKbps ?: 0) > 0) {
                        Text("Bitrate: ${audioQuality.bitrateKbps} kbps", color = Color(0xFFD0D4DC))
                    }
                    if ((audioQuality.sampleRateHz ?: 0) > 0) {
                        Text("Sample Rate: ${audioQuality.sampleRateHz} Hz", color = Color(0xFFD0D4DC))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDetails = false }) {
                    Text("OK", color = Color(0xFF4C8DFF))
                }
            },
            containerColor = Color(0xFF1E222B)
        )
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            trackTitle = selectedSideTrackForPlaylist?.title ?: title,
            onDismiss = {
                showAddToPlaylistDialog = false
                selectedSideTrackForPlaylist = null
            },
            onSelectPlaylist = { playlistId ->
                val track = selectedSideTrackForPlaylist
                if (track != null) {
                    onAddTrackToPlaylist(playlistId, track)
                } else {
                    onAddToPlaylist(playlistId)
                }
                showAddToPlaylistDialog = false
                selectedSideTrackForPlaylist = null
            },
            onCreateNewPlaylist = { name ->
                onCreatePlaylistAndAdd(name)
                showAddToPlaylistDialog = false
                selectedSideTrackForPlaylist = null
            }
        )
    }

    // ── ALWAYS 2-PANE HALF-SCREEN SPLIT ON WINDOWS FOR ALL 6 PLAYER STYLES ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF181C26),
                        Color(0xFF11141C),
                        Color(0xFF0A0C10)
                    ),
                    radius = 1800f
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── Left Half-Screen Pane: Suggestions / Recommended / Queue / Downloads (~46% width) ──
            Column(
                modifier = Modifier
                    .weight(0.46f)
                    .fillMaxHeight()
                    .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)
            ) {
                // Top header: Down chevron on left, Quick Style Switcher (< Style >) & Actions on right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Back",
                            tint = Color(0xFFDCE0E8),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Quick Style Prev / Customize / Next Bar right on the NowPlaying header!
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF1E222B))
                            .border(1.dp, Color(0xFF2C3240), RoundedCornerShape(18.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = { cycleStyle(-1) },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous Style",
                                tint = Color(0xFFDCE0E8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenCustomizeStyles() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Palette,
                                contentDescription = "Customize Styles",
                                tint = Color(0xFF4C8DFF),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = myplayerState.playerStyle.title,
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = { cycleStyle(1) },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next Style",
                                tint = Color(0xFFDCE0E8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showAddToPlaylistDialog = true }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add Playlist",
                                tint = Color(0xFFDCE0E8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { showContextDropdown = true }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Switch Category",
                                tint = Color(0xFFDCE0E8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Sub-header: Category selector ("= Recommended", "Downloaded", "Playlist / Queue") & Counter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showContextDropdown = true }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = null,
                                tint = Color(0xFF8E95A1),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Suggestions • $sideTrackTitle",
                                color = Color(0xFF8E95A1),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = Color(0xFF8E95A1),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showContextDropdown,
                            onDismissRequest = { showContextDropdown = false },
                            modifier = Modifier.background(Color(0xFF1E222B))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Recommended songs", color = Color.White) },
                                leadingIcon = { Icon(Icons.Filled.Recommend, null, tint = Color(0xFF4C8DFF)) },
                                onClick = {
                                    onContextChange(PlaybackSourceContext.RECOMMENDED)
                                    showContextDropdown = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Downloaded songs", color = Color.White) },
                                leadingIcon = { Icon(Icons.Filled.Download, null, tint = Color(0xFF4C8DFF)) },
                                onClick = {
                                    onContextChange(PlaybackSourceContext.DOWNLOADS)
                                    showContextDropdown = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Playlist / Queue songs", color = Color.White) },
                                leadingIcon = { Icon(Icons.Filled.QueueMusic, null, tint = Color(0xFF4C8DFF)) },
                                onClick = {
                                    onContextChange(PlaybackSourceContext.PLAYLIST)
                                    showContextDropdown = false
                                }
                            )
                        }
                    }

                    val activeIndex = sideTracks.indexOfFirst { it.isPlaying }.takeIf { it >= 0 } ?: 0
                    val countText = if (sideTracks.isNotEmpty()) "${activeIndex + 1}/${sideTracks.size}" else "0/0"
                    Text(
                        text = countText,
                        color = Color(0xFFDCE0E8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Scrollable Suggestions / Queue Track Items
                if (sideTracks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tracks available in $sideTrackTitle",
                            color = Color(0xFF727986),
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(sideTracks, key = { index, item -> "${item.id}_$index" }) { _, item ->
                            var showItemMenu by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSideTrackClick(item) }
                                    .background(if (item.isPlaying) Color(0x1A4C8DFF) else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail (Rounded Square)
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF252932)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!item.albumArt.isNullOrBlank()) {
                                        AsyncImage(
                                            model = item.albumArt,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.MusicNote,
                                            contentDescription = null,
                                            tint = Color(0xFFDCE0E8),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Title & Artist
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 10.dp)
                                ) {
                                    Text(
                                        text = item.title,
                                        color = if (item.isPlaying) Color(0xFF4C8DFF) else Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (item.isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = item.artist,
                                        color = Color(0xFF888F9C),
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Active Blue Indicator
                                if (item.isPlaying) {
                                    Row(
                                        modifier = Modifier.padding(end = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(2.5.dp, 8.dp).background(Color(0xFF4C8DFF), RoundedCornerShape(1.dp)))
                                        Box(modifier = Modifier.size(2.5.dp, 12.dp).background(Color(0xFF4C8DFF), RoundedCornerShape(1.dp)))
                                        Box(modifier = Modifier.size(2.5.dp, 6.dp).background(Color(0xFF4C8DFF), RoundedCornerShape(1.dp)))
                                    }
                                }

                                // 3-Dots Menu
                                Box {
                                    IconButton(
                                        onClick = { showItemMenu = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "Options",
                                            tint = Color(0xFF888F9C),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showItemMenu,
                                        onDismissRequest = { showItemMenu = false },
                                        modifier = Modifier.background(Color(0xFF1E222B))
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Play Now", color = Color.White) },
                                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF4C8DFF)) },
                                            onClick = {
                                                showItemMenu = false
                                                onSideTrackClick(item)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to Playlist", color = Color.White) },
                                            leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null, tint = Color.White) },
                                            onClick = {
                                                showItemMenu = false
                                                selectedSideTrackForPlaylist = item
                                                showAddToPlaylistDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Subtle vertical dividing line between Left Suggestions and Right NowPlaying
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF1C2029))
            )

            // ── Right Half-Screen Pane: Active NowPlaying View (~54% width) ──
            Box(
                modifier = Modifier
                    .weight(0.54f)
                    .fillMaxHeight()
            ) {
                MyPlayerAnimatedCanvasOverlay(
                    enabled = myplayerState.enableAnimatedCanvas,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize()
                )
                MyPlayerAtmosphereBlurBox(
                    state = myplayerState,
                    modifier = Modifier.fillMaxSize()
                )
                if (myplayerState.playerStyle != PlayerStyleMode.RADIAL_MYPLAYER) {
                    // Render Turntable, Poster, Timeline, Lyrics Stage, or Default inside the Right Half-Screen!
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    ) {
                        MyPlayerMultiStyleNowPlayingScreen(
                            myplayerState = myplayerState,
                            title = title,
                            artist = artist,
                            artUri = artUri,
                            durationMs = duration,
                            isPlaying = isPlaying,
                            positionState = positionState,
                            onPlayPauseClick = onPlayPauseClick,
                            onNextClick = onNextClick,
                            onPreviousClick = onPreviousClick,
                            onSeek = onSeek,
                            onBackClick = onBackClick,
                            sleepTimerRemaining = sleepTimerRemaining,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            onStartSleepTimer = onStartSleepTimer,
                            onCancelSleepTimer = onCancelSleepTimer,
                            isShuffleOn = isShuffleOn,
                            onToggleShuffle = onToggleShuffle,
                            repeatMode = repeatMode,
                            onCycleRepeatMode = onCycleRepeatMode,
                            onNavigateToSettings = onNavigateToSettings,
                            canDownload = canDownload,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            onDownloadClick = onDownloadClick,
                            onRemoveDownloadClick = onRemoveDownloadClick,
                            audioQuality = audioQuality,
                            lyricsViewModel = lyricsViewModel,
                            onOpenCustomizeStyles = onOpenCustomizeStyles
                        )
                    }
                } else {
                    // Desktop Hero / Radial Player inside the Right Half-Screen
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 20.dp, end = 24.dp, top = 14.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top Utilities Header: Customize Styles, Sleep Timer, Quality, More Options
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onOpenCustomizeStyles) {
                                Icon(
                                    Icons.Filled.Palette,
                                    contentDescription = "Customize Player Styles",
                                    tint = Color(0xFF4C8DFF),
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            IconButton(onClick = { showSleepTimer = true }) {
                                Icon(
                                    Icons.Filled.Timer,
                                    contentDescription = "Sleep Timer",
                                    tint = if (sleepTimerRemaining > 0) Color(0xFF4C8DFF) else Color(0xFF9AA0A8),
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            IconButton(onClick = { showQualityDetails = true }) {
                                Icon(
                                    Icons.Filled.Equalizer,
                                    contentDescription = "Audio Quality",
                                    tint = Color(0xFF9AA0A8),
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            Box {
                                IconButton(onClick = { showMoreSheet = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "More",
                                        tint = Color(0xFF9AA0A8),
                                        modifier = Modifier.size(21.dp)
                                    )
                                }

                                if (showMoreSheet) {
                                    DropdownMenu(
                                        expanded = showMoreSheet,
                                        onDismissRequest = { showMoreSheet = false },
                                        modifier = Modifier.background(Color(0xFF1E222B))
                                    ) {
                                        if (hasSong && canDownload) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        when {
                                                            isDownloaded -> "Remove Offline Download"
                                                            isDownloading -> "Downloading..."
                                                            else -> "Download Offline"
                                                        },
                                                        color = Color.White
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        if (isDownloaded) Icons.Filled.CheckCircle else Icons.Filled.Download,
                                                        null,
                                                        tint = Color.White
                                                    )
                                                },
                                                onClick = {
                                                    showMoreSheet = false
                                                    if (isDownloaded) showRemoveConfirm = true else if (!isDownloading) onDownloadClick()
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Customize Player Styles", color = Color.White) },
                                            leadingIcon = { Icon(Icons.Filled.Star, null, tint = Color.White) },
                                            onClick = { showMoreSheet = false; onOpenCustomizeStyles() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Settings", color = Color.White) },
                                            leadingIcon = { Icon(Icons.Filled.Settings, null, tint = Color.White) },
                                            onClick = { showMoreSheet = false; onNavigateToSettings() }
                                        )
                                    }
                                }
                            }
                        }

                        // Center Content: Hero Album Art or Lyrics Overlay (scaled for half-screen)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (showLyricsView) {
                                val lyricsState by lyricsViewModel.lyricsState.collectAsStateWithLifecycle()
                                val lyricsPositionState = lyricsViewModel.currentPosition.collectAsStateWithLifecycle()
                                LyricsTab(
                                    lyricsState = lyricsState,
                                    positionMs = lyricsPositionState,
                                    onSeek = onSeek,
                                    onAddCustomLyrics = { showCustomLyricsDialog = true },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(20.dp))
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    AlbumArtImage(
                                        artUri = artUri,
                                        title = title,
                                        artist = artist,
                                        size = 210.dp,
                                        cornerRadius = 26.dp,
                                        iconSize = 64.dp,
                                        modifier = Modifier
                                            .shadow(
                                                elevation = 14.dp,
                                                shape = RoundedCornerShape(26.dp),
                                                spotColor = Color(0x66000000)
                                            )
                                    )

                                    Spacer(Modifier.height(16.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                    ) {
                                        Text(
                                            text = title ?: "No Song Playing",
                                            color = Color.White,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (myplayerState.audioFormatBadge) {
                                            Spacer(Modifier.width(8.dp))
                                            Box(modifier = Modifier.clickable { showQualityDetails = true }) {
                                                MyPlayerFormatBadge(
                                                    formatText = audioQuality.format.ifBlank { "AAC" },
                                                    sampleRateHz = audioQuality.sampleRateHz
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    Text(
                                        text = artist ?: "Unknown Artist",
                                        color = Color(0xFF9AA0A8),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                    )
                                }
                            }
                        }

                        // Secondary 3-Icon Row: Lyrics Toggle, Heart Favorite, Plus Add-to-Playlist
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 420.dp)
                                .padding(horizontal = 32.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showLyricsView = !showLyricsView }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Notes,
                                    contentDescription = "Lyrics",
                                    tint = if (showLyricsView) Color(0xFF4C8DFF) else Color(0xFF9AA0A8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(onClick = onToggleFavorite) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (isFavorite) Color(0xFFFF4081) else Color(0xFF9AA0A8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(onClick = { showAddToPlaylistDialog = true }) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Add to playlist",
                                    tint = Color(0xFF9AA0A8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Scrubbable Seekbar & Timestamps
                        var isDragging by remember { mutableStateOf(false) }
                        var dragProgress by remember { mutableFloatStateOf(0f) }
                        val positionMs = positionState.value
                        val rawProgress = if (duration > 0L) (positionMs.toFloat() / duration.toFloat()) else 0f
                        val actualProgress = if (rawProgress.isNaN() || rawProgress.isInfinite()) 0f else rawProgress.coerceIn(0f, 1f)
                        val safeDrag = if (dragProgress.isNaN() || dragProgress.isInfinite()) 0f else dragProgress.coerceIn(0f, 1f)
                        val currentProgress = (if (isDragging) safeDrag else actualProgress).coerceIn(0f, 1f)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 440.dp)
                                .padding(horizontal = 12.dp)
                        ) {
                            Slider(
                                value = currentProgress,
                                onValueChange = {
                                    isDragging = true
                                    dragProgress = it
                                },
                                onValueChangeFinished = {
                                    onSeek((dragProgress * duration).toLong())
                                    isDragging = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color(0xFF383D49)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val displayedPos = if (isDragging) (dragProgress * duration).toLong() else positionMs
                                Text(
                                    text = formatDuration(displayedPos),
                                    color = Color(0xFF8E95A1),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = formatDuration(duration),
                                    color = Color(0xFF8E95A1),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // 5-Button Transport Controls: Shuffle, Prev, Play/Pause, Next, Repeat
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 420.dp)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onToggleShuffle) {
                                Icon(
                                    Icons.Filled.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (isShuffleOn) Color(0xFF4C8DFF) else Color(0xFF8E95A1),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(onClick = onPreviousClick, modifier = Modifier.size(42.dp)) {
                                Icon(
                                    Icons.Filled.SkipPrevious,
                                    contentDescription = "Previous",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            IconButton(
                                onClick = onPlayPauseClick,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color(0xFF171C26),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            IconButton(onClick = onNextClick, modifier = Modifier.size(42.dp)) {
                                Icon(
                                    Icons.Filled.SkipNext,
                                    contentDescription = "Next",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            IconButton(onClick = onCycleRepeatMode) {
                                val (icon, tint) = when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne to Color(0xFF4C8DFF)
                                    Player.REPEAT_MODE_ALL -> Icons.Filled.Repeat to Color(0xFF4C8DFF)
                                    else -> Icons.Filled.Repeat to Color(0xFF8E95A1)
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = "Repeat",
                                    tint = tint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Direct Volume Control Console Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 360.dp)
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (playerVolume == 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (playerVolume == 0f) "Unmute" else "Mute",
                                    tint = if (playerVolume == 0f) Color(0xFFFF5252) else Color(0xFF8E95A1),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Slider(
                                value = playerVolume.let { if (it.isNaN() || it.isInfinite()) 1f else it.coerceIn(0f, 1f) },
                                onValueChange = onVolumeChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF4C8DFF),
                                    activeTrackColor = Color(0xFF4C8DFF),
                                    inactiveTrackColor = Color(0xFF383D49)
                                )
                            )

                            Text(
                                text = "${(playerVolume * 100).toInt()}%",
                                color = Color(0xFF8E95A1),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(36.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistEntity>,
    trackTitle: String?,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreateNewPlaylist: (String) -> Unit
) {
    var newPlaylistName by remember { mutableStateOf("") }
    var isCreatingNew by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E222B),
        title = {
            Text(
                text = "Add to Playlist",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!trackTitle.isNullOrBlank()) {
                    Text(
                        text = "Song: $trackTitle",
                        color = Color(0xFF8E95A1),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (playlists.isNotEmpty()) {
                    Text(
                        text = "Select Playlist:",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists.size) { idx ->
                            val playlist = playlists[idx]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelectPlaylist(playlist.id) }
                                    .padding(vertical = 8.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = Color(0xFF4C8DFF),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = playlist.name,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (!isCreatingNew) {
                    OutlinedButton(
                        onClick = { isCreatingNew = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF2C313C)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4C8DFF))
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Create New Playlist")
                    }
                } else {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        placeholder = { Text("Playlist name...", color = Color(0xFF727986)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4C8DFF),
                            unfocusedBorderColor = Color(0xFF2C313C),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (newPlaylistName.isNotBlank()) {
                                onCreateNewPlaylist(newPlaylistName.trim())
                            }
                        },
                        enabled = newPlaylistName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C8DFF))
                    ) {
                        Text("Create & Add", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8E95A1))
            }
        }
    )
}

@Composable
fun SleepTimerDialog(
    currentRemaining: Long,
    onDismiss: () -> Unit,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val options = listOf(5, 10, 15, 30, 45, 60)
    val isActive = currentRemaining > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E222B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFD0D4DC),
        icon = { Icon(Icons.Filled.Timer, null, tint = Color(0xFF4C8DFF)) },
        title = { Text(if (isActive) "Sleep Timer Active" else "Set Sleep Timer") },
        text = {
            Column {
                if (isActive) {
                    Text(
                        text = "Pausing in ${currentRemaining / 60}m ${currentRemaining % 60}s",
                        color = Color(0xFF4C8DFF),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text("Music will pause automatically after the selected time.")
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { minutes ->
                                OutlinedButton(
                                    onClick = { onStart(minutes) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4C8DFF)),
                                    border = BorderStroke(1.dp, Color(0xFF2C313C))
                                ) {
                                    Text("${minutes}m", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive) {
                    TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))) {
                        Text("Cancel Timer")
                    }
                }
                TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8E95A1))) {
                    Text("Close")
                }
            }
        }
    )
}

fun formatDuration(durationMs: Long): String {
    val s = durationMs / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun CustomLyricsDialog(
    initialLyrics: String = "",
    trackTitle: String?,
    trackArtist: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var text by remember(initialLyrics) { mutableStateOf(initialLyrics) }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E222B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFD0D4DC),
        icon = {
            Icon(
                Icons.Filled.EditNote,
                contentDescription = null,
                tint = Color(0xFF4C8DFF),
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Add Custom Lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (!trackTitle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$trackTitle ${if (!trackArtist.isNullOrBlank()) "• $trackArtist" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E95A1),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Enter or paste plain text lyrics or synced LRC lines (e.g. [00:15.20] line):",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E95A1)
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            "Paste lyrics here...\n\nPlain text or [mm:ss.xx] timestamped lines supported.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF656C7A)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4C8DFF),
                        unfocusedBorderColor = Color(0xFF2C313C),
                        cursorColor = Color(0xFF4C8DFF),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                text = clip
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4C8DFF))
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Paste from Clipboard", style = MaterialTheme.typography.labelMedium)
                    }

                    if (text.isNotBlank()) {
                        Text(
                            text = "${text.lines().size} lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8E95A1)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onSave(text)
                        onDismiss()
                    }
                },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4C8DFF),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF2A2E38),
                    disabledContentColor = Color(0xFF656C7A)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save Lyrics", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
                    ) {
                        Text("Remove")
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8E95A1))
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}