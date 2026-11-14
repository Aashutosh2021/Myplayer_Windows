package com.example.myplayer.ui.screens.library

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myplayer.data.local.entity.PlaylistEntity
import com.example.myplayer.data.repository.PlayableSong
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import com.example.myplayer.R
import com.example.myplayer.ui.common.AlbumArtImage
import com.example.myplayer.ui.myplayer.*
import com.example.myplayer.ui.theme.*

@Composable
fun LibraryScreen(
    onPlaylistClick: (Long) -> Unit,
    onPlaySong: (List<PlayableSong>, Int) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    myplayerState: MyPlayerUiStateHolder? = null,
    onOpenCustomizeStyles: () -> Unit = {},
    onOpenAudioLab: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    bottomPadding: Dp = 100.dp,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistStats by viewModel.playlistStats.collectAsStateWithLifecycle()
    val playlistBatchStatus by viewModel.playlistBatchStatus.collectAsStateWithLifecycle()
    val downloadedSongs by viewModel.downloadedSongs.collectAsStateWithLifecycle()
    val localSongs by viewModel.localSongs.collectAsStateWithLifecycle()
    val isScanningLocal by viewModel.isScanningLocal.collectAsStateWithLifecycle()
    val favoriteSongs by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val localFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val folderName = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.path?.substringAfterLast('/')?.substringAfterLast('\\')
                ?: "Local Music"
            viewModel.addLocalFolder(uri.path ?: uri.toString(), folderName)
        }
    }
    val isImportingSpotify by viewModel.isImportingSpotify.collectAsStateWithLifecycle()
    val importStatusMessage by viewModel.importStatusMessage.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var sortAscending by rememberSaveable { mutableStateOf(true) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var songToAddToPlaylist by remember { mutableStateOf<PlayableSong?>(null) }
    var songToRemoveDownload by remember { mutableStateOf<PlayableSong?>(null) }

    // Playlist rename flow (grid-level "Rename" action): target playlist + name buffer
    var playlistToRename by remember { mutableStateOf<PlaylistEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }

    importStatusMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearImportStatusMessage() },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Playlist Import", fontWeight = FontWeight.Bold) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImportStatusMessage() }) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    songToRemoveDownload?.let { song ->
        AlertDialog(
            onDismissRequest = { songToRemoveDownload = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = MaterialTheme.colorScheme.secondary,
            title = { Text("Remove Download?", fontWeight = FontWeight.Bold) },
            text = { Text("This will remove the downloaded audio file from your device storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDownload(song.id)
                        songToRemoveDownload = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFBA1A1A))
                ) { Text("Remove", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { songToRemoveDownload = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                ) { Text("Cancel") }
            }
        )
    }

    playlistToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToRename = null; renameInput = "" },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = MaterialTheme.colorScheme.secondary,
            title = { Text("Rename Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        unfocusedLabelColor = MaterialTheme.colorScheme.secondary,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameInput.trim()
                        if (newName.isNotBlank()) {
                            viewModel.renamePlaylist(target.id, newName)
                            renameInput = ""
                            playlistToRename = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("Rename", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { playlistToRename = null; renameInput = "" },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                ) { Text("Cancel") }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        val trimmedInput = newPlaylistName.trim()
        val isSpotifyInput = trimmedInput.contains("spotify.com") ||
            trimmedInput.contains("spotify:playlist") ||
            (trimmedInput.length == 22 && !trimmedInput.contains(" "))
        val isYouTubeInput = trimmedInput.contains("youtube.com") ||
            trimmedInput.contains("youtu.be") ||
            trimmedInput.contains("list=") ||
            trimmedInput.startsWith("PL") ||
            trimmedInput.startsWith("VLPL") ||
            trimmedInput.startsWith("OLAK5uy_")

        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false; newPlaylistName = "" },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = MaterialTheme.colorScheme.secondary,
            title = {
                Text(
                    when {
                        isYouTubeInput -> "Import YouTube Playlist"
                        isSpotifyInput -> "Import Spotify Playlist"
                        else -> "Create or Import Playlist"
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Playlist name, YouTube link, or Spotify link") },
                        placeholder = { Text("e.g. My Favorites or https://music.youtube.com/playlist?list=...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            unfocusedLabelColor = MaterialTheme.colorScheme.secondary,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Text(
                        text = "Tip: Paste any YouTube Music, YouTube, or Spotify playlist URL to import all tracks into MyPlayer!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (trimmedInput.isNotBlank()) {
                            when {
                                isYouTubeInput -> viewModel.importYouTubePlaylist(trimmedInput)
                                isSpotifyInput -> viewModel.importSpotifyPlaylist(trimmedInput)
                                else -> viewModel.createPlaylist(trimmedInput)
                            }
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text(if (isYouTubeInput || isSpotifyInput) "Import" else "Create", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreatePlaylistDialog = false; newPlaylistName = "" },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                ) { Text("Cancel") }
            }
        )
    }

    songToAddToPlaylist?.let { song ->
        AlertDialog(
            onDismissRequest = { songToAddToPlaylist = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = MaterialTheme.colorScheme.secondary,
            title = { Text("Add to Playlist", fontWeight = FontWeight.Bold) },
            text = {
                if (playlists.isEmpty()) {
                    Text("No playlists available. Create one first.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(playlists.size, key = { playlists[it].id }) { idx ->
                            val playlist = playlists[idx]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFF4F6F9))
                                    .clickable {
                                        viewModel.addSongToPlaylist(playlist.id, song)
                                        songToAddToPlaylist = null
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { songToAddToPlaylist = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("Close") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // ── Top Header Bar (Stitch Screen 8) ──────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
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
                        text = "Library",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onNavigateToSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

        // ── Stitch Screen 8: Category Filter Chips ────────────────────────────
        val categoryTabs = remember { listOf("Downloaded", "Local Files", "Playlists") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categoryTabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) PrimaryContainer else SurfaceContainer)
                        .clickable { selectedTab = index }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (title == "Downloaded") {
                            Icon(
                                imageVector = Icons.Filled.DownloadDone,
                                contentDescription = null,
                                tint = if (isSelected) Color.Black else Secondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) Color.Black else Secondary
                        )
                    }
                }
            }
        }

        // ── Stitch Screen 8: Sort & Action Row ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { sortAscending = !sortAscending }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Filled.SwapVert, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (selectedTab == 0 || selectedTab == 1) {
                        if (sortAscending) "Alphabetical (A–Z)" else "Alphabetical (Z–A)"
                    } else {
                        if (sortAscending) "Recently Added" else "Oldest First"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = OnSurface
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Outline, modifier = Modifier.size(16.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedTab == 1) {
                    OutlinedButton(
                        onClick = { localFolderLauncher.launch(null) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Select Folder", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.rescanLocalSongs() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isScanningLocal) "Scanning..." else "Scan PC Audio", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(2.dp, CircleShape, spotColor = Color(0x0C000000))
                        .clip(CircleShape)
                        .background(SurfaceContainer)
                        .clickable { showCreatePlaylistDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New Playlist", tint = OnSurface, modifier = Modifier.size(18.dp))
                }
            }
        }

        when (selectedTab) {
            0 -> HybridSongsTab(
                songs = remember(downloadedSongs, sortAscending) {
                    if (sortAscending) downloadedSongs.sortedBy { it.title.lowercase() }
                    else downloadedSongs.sortedByDescending { it.title.lowercase() }
                },
                onPlaySong = onPlaySong,
                onAddToPlaylist = { songToAddToPlaylist = it },
                onRemoveDownload = { songToRemoveDownload = it },
                onExportFile = { song, fmt -> viewModel.exportSongAsFile(song, fmt) },
                bottomPadding = bottomPadding
            )
            1 -> HybridSongsTab(
                songs = remember(localSongs, sortAscending) {
                    if (sortAscending) localSongs.sortedBy { it.title.lowercase() }
                    else localSongs.sortedByDescending { it.title.lowercase() }
                },
                onPlaySong = onPlaySong,
                onAddToPlaylist = { songToAddToPlaylist = it },
                onRemoveDownload = { songToRemoveDownload = it },
                onExportFile = { song, fmt -> viewModel.exportSongAsFile(song, fmt) },
                isLocalTab = true,
                onSelectLocalFolder = { localFolderLauncher.launch(null) },
                onScanDeviceAudio = { viewModel.rescanLocalSongs() },
                bottomPadding = bottomPadding
            )
            else -> PlaylistsTab(
                playlists = remember(playlists, sortAscending) {
                    if (sortAscending) playlists.sortedByDescending { it.createdAt }
                    else playlists.sortedBy { it.createdAt }
                },
                favoriteSongs = favoriteSongs,
                playlistStats = playlistStats,
                playlistBatchStatus = playlistBatchStatus,
                onTogglePlaylistDownload = viewModel::togglePlaylistDownload,
                onPlayFavoriteSongs = { onPlaySong(favoriteSongs, 0) },
                onCreatePlaylist = { showCreatePlaylistDialog = true },
                onDeletePlaylist = viewModel::deletePlaylist,
                onRenamePlaylist = { playlist ->
                    playlistToRename = playlist
                    renameInput = playlist.name
                },
                onPlaylistClick = onPlaylistClick,
                bottomPadding = bottomPadding
            )
        }
    }
}

@Composable
fun HybridSongsTab(
    songs: List<PlayableSong>,
    onPlaySong: (List<PlayableSong>, Int) -> Unit,
    onAddToPlaylist: (PlayableSong) -> Unit,
    onRemoveDownload: (PlayableSong) -> Unit,
    onExportFile: (PlayableSong, String) -> Unit = { _, _ -> },
    isLocalTab: Boolean = false,
    onSelectLocalFolder: () -> Unit = {},
    onScanDeviceAudio: () -> Unit = {},
    bottomPadding: Dp
) {
    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (isLocalTab) Icons.Filled.FolderOpen else Icons.Filled.LibraryMusic,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = SilverMuted
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (isLocalTab) "No local songs indexed yet" else "No songs yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = CharcoalPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isLocalTab) "Select your local music folder or scan your PC storage to play local MP3 / M4A / FLAC files"
                    else "Download songs online or search to add music",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SlateSecondary
                )
                if (isLocalTab) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onSelectLocalFolder,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Select Local Folder", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onScanDeviceAudio,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan PC Music Folder", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        return
    }

    val heroSong = songs.first()

    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomPadding + 24.dp, top = 8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        // ── Editorial Album / Library Header (Exact Match to Image 2 Right Screen) ──
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .shadow(12.dp, RoundedCornerShape(22.dp), spotColor = CharcoalPrimary.copy(alpha = 0.2f))
                        .clip(RoundedCornerShape(22.dp))
                        .background(SoftGraySurface)
                ) {
                    AlbumArtImage(
                        uri = heroSong.thumbnailUrl,
                        title = heroSong.title,
                        artist = heroSong.artist,
                        size = 112.dp,
                        shape = RoundedCornerShape(22.dp),
                        iconSize = 40.dp
                    )
                }

                Spacer(Modifier.width(18.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Collection • ${songs.size} songs",
                        style = MaterialTheme.typography.labelSmall,
                        color = SilverMuted
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "My Library",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 26.sp
                        ),
                        color = CharcoalPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = heroSong.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Dual [Play] & [Shuffle] Action Buttons (Exact Match to Image 2) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onPlaySong(songs, 0) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CharcoalPrimary,
                        contentColor = PureWhite
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Icon(Icons.Filled.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                }

                Button(
                    onClick = {
                        val shuffled = songs.shuffled()
                        onPlaySong(shuffled, 0)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoftGraySurface,
                        contentColor = CharcoalPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
        }

        // ── Numbered Track List (01, 02, 03... like Image 2 Right Screen) ────────
        itemsIndexed(songs, key = { index, song -> "${song.id}_${index}" }) { index, song ->
            HybridSongItem(
                index = index,
                song = song,
                onPlaySong = { onPlaySong(songs, index) },
                onAddToPlaylist = { onAddToPlaylist(song) },
                onRemoveDownload = { onRemoveDownload(song) },
                onExportFile = { fmt -> onExportFile(song, fmt) }
            )
        }
    }
}

@Composable
fun HybridSongItem(
    index: Int = 0,
    song: PlayableSong,
    onPlaySong: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveDownload: () -> Unit,
    onExportFile: (String) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val trackNum = "%02d".format(index + 1)
    val durationSec = (song.durationMs / 1000).coerceAtLeast(0L)
    val durationFormatted = if (durationSec > 0) " • %d:%02d".format(durationSec / 60, durationSec % 60) else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .slidePopIn(index)
            .clip(RoundedCornerShape(16.dp))
            .bouncyPopClick(onClick = onPlaySong)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 2-Digit Track Number ("01", "02", "03"...)
        Text(
            text = trackNum,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = SlateSecondary,
            modifier = Modifier.width(34.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                color = CharcoalPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${song.artist}$durationFormatted",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = SlateSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "More",
                    tint = SilverMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (showMenu) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(PureWhite)
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to Playlist", color = CharcoalPrimary) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = SlateSecondary) },
                        onClick = { showMenu = false; onAddToPlaylist() }
                    )
                    DropdownMenuItem(
                        text = { Text("Download MP3 File (Save to PC)", color = CharcoalPrimary) },
                        leadingIcon = { Icon(Icons.Filled.AudioFile, null, tint = CharcoalPrimary) },
                        onClick = { showMenu = false; onExportFile("mp3") }
                    )
                    DropdownMenuItem(
                        text = { Text("Download M4A File (Save to PC)", color = CharcoalPrimary) },
                        leadingIcon = { Icon(Icons.Filled.SaveAlt, null, tint = CharcoalPrimary) },
                        onClick = { showMenu = false; onExportFile("m4a") }
                    )
                    if (song is PlayableSong.Downloaded) {
                        DropdownMenuItem(
                            text = { Text("Remove Download", color = Color(0xFFD32F2F)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Color(0xFFD32F2F)) },
                            onClick = { showMenu = false; onRemoveDownload() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsTab(
    playlists: List<PlaylistEntity>,
    favoriteSongs: List<PlayableSong>,
    playlistStats: Map<Long, PlaylistOfflineInfo> = emptyMap(),
    playlistBatchStatus: Map<Long, Pair<Int, Int>> = emptyMap(),
    onTogglePlaylistDownload: (Long) -> Unit = {},
    onPlayFavoriteSongs: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    onRenamePlaylist: (PlaylistEntity) -> Unit = {},
    onPlaylistClick: (Long) -> Unit,
    bottomPadding: Dp
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomPadding + 20.dp, top = 8.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Pinned Feature: Liked Songs Hero Card (Stitch Screen 8) ───────────
        if (favoriteSongs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .slidePopIn(0)
                        .shadow(4.dp, RoundedCornerShape(16.dp), spotColor = Color(0x10171C26))
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceContainerLow)
                        .bouncyPopClick(onClick = onPlayFavoriteSongs)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .shadow(8.dp, RoundedCornerShape(16.dp), spotColor = Color(0x33171C26))
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(PrimaryContainer, Secondary, PrimaryContainer))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = OnPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Liked Songs",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = OnSurface
                                )
                                Icon(
                                    imageVector = Icons.Filled.PushPin,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${favoriteSongs.size} tracks • Auto-synced",
                                style = MaterialTheme.typography.bodySmall,
                                color = Secondary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .shadow(2.dp, CircleShape, spotColor = Color(0x14000000))
                            .clip(CircleShape)
                            .background(SurfaceContainerLowest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = Primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // ── Section Header (Stitch Screen 8) ─────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "YOUR COLLECTIONS",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    ),
                    color = Secondary
                )
                Text(
                    text = "${playlists.size} Playlist${if (playlists.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Outline
                )
            }
        }

        if (playlists.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFECEFF4)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("No playlists yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text("Create a custom playlist to organize your favorite tracks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        } else {
            items(playlists.size, key = { playlists[it].id }) { index ->
                val playlist = playlists[index]
                var showDeleteConfirm by remember { mutableStateOf(false) }

                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                        textContentColor = MaterialTheme.colorScheme.secondary,
                        title = { Text("Delete Playlist?", fontWeight = FontWeight.Bold) },
                        text = { Text("Are you sure you want to delete '${playlist.name}'?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onDeletePlaylist(playlist)
                                    showDeleteConfirm = false
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFBA1A1A))
                            ) { Text("Delete", fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .slidePopIn(index + 1)
                        .shadow(2.dp, RoundedCornerShape(16.dp), spotColor = Color(0x0A000000))
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceContainerLowest)
                        .bouncyPopClick { onPlaylistClick(playlist.id) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(3.dp, RoundedCornerShape(12.dp), spotColor = Color(0x14171C26))
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    val stats = playlistStats[playlist.id] ?: PlaylistOfflineInfo()
                    val batchProgress = playlistBatchStatus[playlist.id]
                    val subtitleText = if (stats.totalCount > 0) {
                        "${stats.downloadedCount}/${stats.totalCount} downloaded • ${formatDate(playlist.createdAt)}"
                    } else {
                        "0 songs • ${formatDate(playlist.createdAt)}"
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (batchProgress != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onTogglePlaylistDownload(playlist.id) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
                            Spacer(Modifier.width(4.dp))
                            Text("${batchProgress.first}/${batchProgress.second}", style = MaterialTheme.typography.labelSmall, color = Primary)
                        }
                    } else if (stats.remainingCount > 0) {
                        IconButton(onClick = { onTogglePlaylistDownload(playlist.id) }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download remaining", tint = Primary)
                        }
                    } else if (stats.totalCount > 0) {
                        Icon(
                            Icons.Filled.DownloadDone,
                            contentDescription = "All downloaded",
                            tint = Primary,
                            modifier = Modifier.padding(horizontal = 10.dp).size(20.dp)
                        )
                    }

                    IconButton(onClick = { onRenamePlaylist(playlist) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = Secondary)
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete", tint = Secondary)
                    }
                }
            }
        }
    }
}


private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}


