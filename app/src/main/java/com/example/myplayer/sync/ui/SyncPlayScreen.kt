package com.example.myplayer.sync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.myplayer.data.local.entity.SongEntity
import com.example.myplayer.sync.DeviceSyncStatus
import com.example.myplayer.sync.LtConnectionState
import com.example.myplayer.sync.LtRole
import com.example.myplayer.sync.LtRoomUiState
import com.example.myplayer.sync.SyncConnectionState
import com.example.myplayer.sync.SyncDevice
import com.example.myplayer.sync.SyncPlaybackState
import com.example.myplayer.sync.SyncRole
import com.example.myplayer.ui.common.AlbumArtImage
import com.example.myplayer.ui.theme.CardBorderOlive
import com.example.myplayer.ui.theme.NeonLimePrimary
import com.example.myplayer.ui.theme.OnNeonLime
import com.example.myplayer.ui.theme.OnSurface
import com.example.myplayer.ui.theme.OnSurfaceVariant
import com.example.myplayer.ui.theme.PureWhite
import com.example.myplayer.ui.theme.SurfaceContainer
import com.example.myplayer.ui.theme.SurfaceContainerHigh
import com.example.myplayer.ui.theme.SurfaceLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPlayScreen(
    onBack: () -> Unit,
    onNavigateToNowPlaying: () -> Unit = {},
    viewModel: SyncPlayViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val ltState by viewModel.ltState.collectAsState()

    // 0 = Online Room Code (Listen Together), 1 = Local Wi-Fi Sync Play
    var selectedTab by rememberSaveable {
        mutableIntStateOf(if (state.role != SyncRole.NONE) 1 else 0)
    }

    // Automatically switch tab if a session is active in one of the modes
    LaunchedEffect(state.role, ltState.role) {
        if (ltState.role != LtRole.NONE) selectedTab = 0
        else if (state.role != SyncRole.NONE) selectedTab = 1
    }

    var showJoinByIpDialog by remember { mutableStateOf(false) }
    var inputIp by remember { mutableStateOf("") }
    var inputPort by remember { mutableStateOf("45200") }
    var showUserManual by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (selectedTab == 0) Icons.Default.Groups else Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (selectedTab == 0) "Listen Together" else "Sync Play",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // User manual is a Local Wi-Fi Sync feature — hide it on the online Listen Together tab
                    if (selectedTab == 1) {
                        IconButton(onClick = { showUserManual = !showUserManual }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = "User Manual",
                                tint = if (showUserManual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Mode Switcher Pill (Online Room Code vs Local Wi-Fi Sync) ─────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceContainer)
                    .border(BorderStroke(1.dp, CardBorderOlive), RoundedCornerShape(18.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModeTabButton(
                    title = "Listen Together (Code)",
                    icon = Icons.Default.Public,
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                ModeTabButton(
                    title = "Local Wi-Fi Sync",
                    icon = Icons.Default.Wifi,
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(18.dp))

            if (selectedTab == 0) {
                OnlineListenTogetherSection(
                    ltState = ltState,
                    currentSong = currentSong,
                    viewModel = viewModel,
                    onNavigateToNowPlaying = onNavigateToNowPlaying
                )
            } else {
            // Error banner if any
            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                state.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // When active in a room, top bar help button shows the user manual card
            AnimatedVisibility(
                visible = showUserManual && state.role != SyncRole.NONE,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    SyncPlayUserManualCard(
                        expanded = true,
                        onToggle = { showUserManual = false }
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            when (state.role) {
                SyncRole.NONE -> {
                    // Header card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cast,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Synchronized Listening",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Play music simultaneously across 2 devices on the same Wi-Fi network.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (state.isEmulator) {
                                Spacer(Modifier.height(12.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Emulator detected. For local playback with physical devices, we recommend hosting on the physical phone/tablet.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // User Manual & Quick Start
                    SyncPlayUserManualCard(
                        expanded = showUserManual,
                        onToggle = { showUserManual = !showUserManual }
                    )

                    Spacer(Modifier.height(20.dp))

                    // Currently playing song indicator
                    if (currentSong != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumArtImage(
                                    uri = currentSong?.albumArt,
                                    title = currentSong?.title,
                                    artist = currentSong?.artist,
                                    album = currentSong?.album,
                                    size = 42.dp,
                                    shape = RoundedCornerShape(8.dp),
                                    iconSize = 20.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        currentSong?.title ?: "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        currentSong?.artist ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    } else {
                        Text(
                            "Tip: Start playing a song first if you plan to Host the room.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // Action buttons
                    Button(
                        onClick = { viewModel.createRoom(Build.MODEL) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("CREATE ROOM (HOST)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { viewModel.joinNearbyRoom() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text("JOIN NEARBY ROOM (AUTO)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showJoinByIpDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.SettingsEthernet, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text("JOIN BY IP ADDRESS", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                SyncRole.MASTER -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "HOST MODE (MASTER)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Room: MyPlayer-${Build.MODEL}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))

                            // Show local IP & port so slave can connect directly
                            state.localIpAddress?.let { ip ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            "Host IP: $ip",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "Port: ${state.boundPort}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            if (state.isEmulator) {
                                Text(
                                    "Note: Running in Android Emulator (10.0.2.x). To connect a physical device, please run Master on the physical device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            Spacer(Modifier.height(4.dp))
                            StatusBadge(connectionState = state.connectionState)
                            Spacer(Modifier.height(14.dp))

                            if (state.connectionState == SyncConnectionState.ADVERTISING) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Waiting for other device to join...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Connected Devices: 0",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else if (state.connectionState == SyncConnectionState.CONNECTED) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Connected Devices (${state.devices.size})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                for (device in state.devices) {
                                    DeviceItemCard(
                                        device = device,
                                        onRemove = { viewModel.removeSlave(device.deviceId) }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                            } else if (state.connectionState == SyncConnectionState.ERROR) {
                                state.errorMessage?.let { msg ->
                                    Text(
                                        msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    val masterTrackTitle = currentSong?.title ?: state.currentTrack?.title
                    val masterTrackArtist = currentSong?.artist ?: state.currentTrack?.artist
                    val masterTrackArt = currentSong?.albumArt ?: state.currentTrack?.albumArt

                    if (!masterTrackTitle.isNullOrBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumArtImage(
                                    uri = masterTrackArt,
                                    title = masterTrackTitle,
                                    artist = masterTrackArtist,
                                    size = 46.dp,
                                    shape = RoundedCornerShape(8.dp),
                                    iconSize = 22.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        masterTrackTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        masterTrackArtist ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    val buttonText = when (state.playbackState) {
                        SyncPlaybackState.WAITING_FOR_READY,
                        SyncPlaybackState.PREPARING -> "PREPARING TRACK..."
                        SyncPlaybackState.SCHEDULED -> "STARTING IN SYNC..."
                        SyncPlaybackState.PLAYING -> "PLAYING IN SYNC"
                        SyncPlaybackState.PAUSED -> "RESUME SYNC PLAY"
                        else -> if (state.connectionState == SyncConnectionState.CONNECTED) "START SYNC PLAY" else "WAITING FOR DEVICE TO JOIN..."
                    }

                    if (state.playbackState == SyncPlaybackState.PLAYING) {
                        Button(
                            onClick = { viewModel.pause() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("PAUSE SYNC PLAY", fontWeight = FontWeight.Bold)
                        }
                    } else if (state.playbackState == SyncPlaybackState.PAUSED) {
                        Button(
                            onClick = { viewModel.resume() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("RESUME SYNC PLAY", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startSyncPlayback() },
                            enabled = state.connectionState == SyncConnectionState.CONNECTED &&
                                    state.playbackState != SyncPlaybackState.WAITING_FOR_READY &&
                                    state.playbackState != SyncPlaybackState.PREPARING,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (state.playbackState == SyncPlaybackState.WAITING_FOR_READY ||
                                state.playbackState == SyncPlaybackState.PREPARING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(10.dp))
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(buttonText, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                SyncRole.SLAVE -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "CLIENT MODE (SLAVE)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            StatusBadge(connectionState = state.connectionState)
                            Spacer(Modifier.height(14.dp))

                            when (state.connectionState) {
                                SyncConnectionState.CONNECTING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "Connecting to ${state.roomHostAddress ?: "Host"}...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Ensure Host room is open on the Master phone",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SyncConnectionState.CONNECTED -> {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Connected to Host: ${state.roomHostAddress ?: ""}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    when (state.playbackState) {
                                        SyncPlaybackState.PREPARING -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "Preparing track: ${state.currentTrack?.title ?: "..."}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        SyncPlaybackState.WAITING_FOR_READY -> {
                                            Text(
                                                "Track ready! Waiting for Host to schedule start...",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        SyncPlaybackState.SCHEDULED -> {
                                            Text(
                                                "Starting synchronized playback...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        SyncPlaybackState.PLAYING -> {
                                            Text(
                                                "Playing in sync with Host!",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        SyncPlaybackState.PAUSED -> {
                                            Text(
                                                "Playback paused by Host",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        else -> {
                                            Text(
                                                "Ready! Waiting for Host to start music...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    val slaveTrackTitle = state.currentTrack?.title ?: currentSong?.title
                                    val slaveTrackArtist = state.currentTrack?.artist ?: currentSong?.artist
                                    val slaveTrackArt = state.currentTrack?.albumArt ?: currentSong?.albumArt

                                    if (!slaveTrackTitle.isNullOrBlank()) {
                                        Spacer(Modifier.height(12.dp))
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                AlbumArtImage(
                                                    uri = slaveTrackArt,
                                                    title = slaveTrackTitle,
                                                    artist = slaveTrackArtist,
                                                    size = 46.dp,
                                                    shape = RoundedCornerShape(8.dp),
                                                    iconSize = 22.dp
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        slaveTrackTitle,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        slaveTrackArtist ?: "",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                SyncConnectionState.DISCOVERING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "Searching for nearby rooms...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                SyncConnectionState.ERROR -> {
                                    Surface(
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                "Connection Failed",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            state.errorMessage?.let { msg ->
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    msg,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }
                                SyncConnectionState.DISCONNECTED -> {
                                    Text(
                                        "Disconnected from Host",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            if (state.role != SyncRole.NONE) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { viewModel.leaveRoom() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("LEAVE ROOM", fontWeight = FontWeight.Bold)
                }
            }
            } // end Local Wi-Fi Sync tab
        }
    }

    // Join by IP Dialog
    if (showJoinByIpDialog) {
        AlertDialog(
            onDismissRequest = { showJoinByIpDialog = false },
            title = { Text("Join Room by IP") },
            text = {
                Column {
                    Text(
                        "Enter the IP address of the Host (Master) device displayed on its Sync Play screen:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputIp,
                        onValueChange = { inputIp = it },
                        label = { Text("Host IP Address") },
                        placeholder = { Text("e.g. 172.16.225.57") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputPort,
                        onValueChange = { inputPort = it },
                        label = { Text("Port") },
                        placeholder = { Text("45200") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val port = inputPort.toIntOrNull() ?: 45200
                        viewModel.joinByIp(inputIp, port)
                        showJoinByIpDialog = false
                    },
                    enabled = inputIp.isNotBlank()
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinByIpDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatusBadge(connectionState: SyncConnectionState) {
    val (color, text) = when (connectionState) {
        SyncConnectionState.IDLE -> MaterialTheme.colorScheme.outline to "Idle"
        SyncConnectionState.ADVERTISING -> MaterialTheme.colorScheme.primary to "Broadcasting room..."
        SyncConnectionState.DISCOVERING -> MaterialTheme.colorScheme.primary to "Searching for nearby room..."
        SyncConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary to "Connecting to room..."
        SyncConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary to "Connected"
        SyncConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline to "Disconnected"
        SyncConnectionState.ERROR -> MaterialTheme.colorScheme.error to "Connection error"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DeviceItemCard(
    device: SyncDevice,
    onRemove: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    when (device.status) {
                        DeviceSyncStatus.IDLE -> {
                            Text(
                                "Connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DeviceSyncStatus.PREPARING -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Preparing...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DeviceSyncStatus.DOWNLOADING -> {
                            Text(
                                "Downloading ${device.downloadProgress}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DeviceSyncStatus.READY -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Ready",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DeviceSyncStatus.FAILED -> {
                            Text(
                                device.errorMessage ?: "Failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        DeviceSyncStatus.DISCONNECTED -> {
                            Text(
                                "Disconnected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    if (onRemove != null && !device.isMaster) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove device",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            if (device.status == DeviceSyncStatus.DOWNLOADING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (device.downloadProgress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SyncPlayUserManualCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "User Manual & Quick Start",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (expanded) "Tap to collapse manual" else "Learn how to play in sync on 2+ devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
                Spacer(Modifier.height(14.dp))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SyncPlayManualStep(
                        step = "1",
                        title = "Connect to the Same Network",
                        description = "Both devices must be connected to the same Wi-Fi router or local network. Alternatively, one device can host a Mobile Hotspot and the other connects to it."
                    )
                    SyncPlayManualStep(
                        step = "2",
                        title = "Device 1: Host a Room",
                        description = "Tap 'CREATE ROOM (HOST)'. This device acts as the master sync clock and starts broadcasting to nearby devices on the network."
                    )
                    SyncPlayManualStep(
                        step = "3",
                        title = "Device 2: Join the Room",
                        description = "Tap 'JOIN NEARBY ROOM (AUTO)'. Select the host from the discovered devices. Or use 'JOIN BY IP' if on a subnet/restricted router."
                    )
                    SyncPlayManualStep(
                        step = "4",
                        title = "Synchronized Playback",
                        description = "Play any local or online song on the Host. Audio synchronizes with sub-millisecond precision across all connected devices!"
                    )
                    SyncPlayManualStep(
                        step = "5",
                        title = "Real-time Playback Sync",
                        description = "Play, pause, seek, or skip tracks on the Host — all connected guest devices follow in perfect real-time synchronization."
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncPlayManualStep(
    step: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

// ============================================================================
// TAB 1: ONLINE LISTEN TOGETHER (MELD ROOM CODE OVER INTERNET)
// ============================================================================

@Composable
private fun OnlineListenTogetherSection(
    ltState: LtRoomUiState,
    currentSong: SongEntity?,
    viewModel: SyncPlayViewModel,
    onNavigateToNowPlaying: () -> Unit
) {
    // Desktop shim context — Toast / ClipboardManager are served by the android.* shims.
    val context = LocalContext.current
    var usernameInput by rememberSaveable(ltState.username) { mutableStateOf(ltState.username) }
    var roomCodeInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ltState.errorMessage?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (ltState.role == LtRole.NONE) {
            // Create Online Room Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceLight),
                border = BorderStroke(1.dp, CardBorderOlive),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(NeonLimePrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Headphones, null, tint = OnNeonLime, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Listen Together Anywhere",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = OnSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Not on the same Wi-Fi? Create a 6-digit Room Code and listen in real-time with friends over Mobile Data / Internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("Your Display Name") },
                        placeholder = { Text("e.g. Aashutosh") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = { viewModel.createOnlineRoom(usernameInput) },
                        enabled = ltState.connectionState != LtConnectionState.CONNECTING,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonLimePrimary,
                            contentColor = OnNeonLime
                        )
                    ) {
                        if (ltState.connectionState == LtConnectionState.CONNECTING && !ltState.waitingForApproval) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = OnNeonLime, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Icon(Icons.Default.AddCircleOutline, null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Create Room Code (Host)", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Join Online Room by Code Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceLight),
                border = BorderStroke(1.dp, CardBorderOlive),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Join with Room Code",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = OnSurface
                    )
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = roomCodeInput,
                        onValueChange = { roomCodeInput = it.uppercase().take(8) },
                        label = { Text("Enter 6-Digit Room Code") },
                        placeholder = { Text("e.g. AB12CD") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.joinOnlineRoom(roomCodeInput, usernameInput) },
                        enabled = roomCodeInput.isNotBlank() && !ltState.waitingForApproval,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceContainerHigh,
                            contentColor = OnSurface
                        )
                    ) {
                        if (ltState.waitingForApproval) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NeonLimePrimary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Waiting for Host Approval...", fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.Login, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Join Room", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            val isHost = ltState.role == LtRole.HOST

            // Active Room Code Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceLight),
                border = BorderStroke(1.dp, NeonLimePrimary),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isHost) "HOSTING ONLINE ROOM" else "CONNECTED AS GUEST",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = NeonLimePrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = ltState.roomCode,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 4.sp
                        ),
                        color = OnSurface
                    )
                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Room Code", ltState.roomCode))
                                Toast.makeText(context, "Room code ${ltState.roomCode} copied!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Code")
                        }

                        // shortcut: Desktop has no share sheet (Intent shim has no ACTION_SEND) —
                        // "Share" copies the invite text to the clipboard instead (Toast confirms).
                        Button(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Room Code",
                                        "Join my Listen Together room on MyPlayer! Room Code: ${ltState.roomCode}"
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    "Invite message copied — share it from any chat app",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonLimePrimary,
                                contentColor = OnNeonLime
                            )
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isHost) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Auto-approve join requests", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                            Switch(
                                checked = ltState.autoApproveJoins,
                                onCheckedChange = { viewModel.setAutoApprove(it) }
                            )
                        }
                    }
                }
            }

            // Currently Synced Track Card
            val activeTitle = ltState.currentTrack?.title ?: currentSong?.title
            val activeArtist = ltState.currentTrack?.artist ?: currentSong?.artist
            val activeArt = ltState.currentTrack?.thumbnail ?: currentSong?.albumArt

            if (!activeTitle.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceLight),
                    border = BorderStroke(1.dp, CardBorderOlive),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToNowPlaying() }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtImage(
                            uri = activeArt,
                            title = activeTitle,
                            artist = activeArtist,
                            size = 50.dp,
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Synced Track", style = MaterialTheme.typography.labelSmall, color = NeonLimePrimary)
                            Text(activeTitle, fontWeight = FontWeight.Bold, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(activeArtist ?: "", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 1)
                        }
                        if (!isHost && currentSong != null) {
                            OutlinedButton(
                                onClick = { viewModel.suggestCurrentSong() },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Suggest", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Pending Join Requests (Host Only)
            if (isHost && ltState.pendingJoinRequests.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceLight),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pending Join Requests", fontWeight = FontWeight.Bold, color = OnSurface)
                        ltState.pendingJoinRequests.forEach { req ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(req.username, fontWeight = FontWeight.SemiBold, color = OnSurface)
                                Row {
                                    IconButton(onClick = { viewModel.approveJoin(req.userId) }) {
                                        Icon(Icons.Default.CheckCircle, "Approve", tint = NeonLimePrimary)
                                    }
                                    IconButton(onClick = { viewModel.rejectJoin(req.userId) }) {
                                        Icon(Icons.Default.Cancel, "Reject", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Pending Song Suggestions (Host Only)
            if (isHost && ltState.pendingSuggestions.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceLight),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Suggested Songs", fontWeight = FontWeight.Bold, color = OnSurface)
                        ltState.pendingSuggestions.forEach { sug ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sug.track.title, fontWeight = FontWeight.SemiBold, color = OnSurface, maxLines = 1)
                                    Text("by ${sug.fromUsername}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                                }
                                Row {
                                    IconButton(onClick = { viewModel.approveSuggestion(sug) }) {
                                        Icon(Icons.Default.PlayCircle, "Play", tint = NeonLimePrimary)
                                    }
                                    IconButton(onClick = { viewModel.rejectSuggestion(sug.suggestionId) }) {
                                        Icon(Icons.Default.Cancel, "Reject", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Connected Users List
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceLight),
                border = BorderStroke(1.dp, CardBorderOlive),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Listeners in Room (${ltState.users.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = OnSurface
                    )
                    ltState.users.forEach { user ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (user.isHost) Icons.Default.Star else Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (user.isHost) NeonLimePrimary else OnSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "${user.username}${if (user.userId == ltState.userId) " (You)" else ""}",
                                    fontWeight = if (user.isHost) FontWeight.Bold else FontWeight.Medium,
                                    color = OnSurface
                                )
                            }
                            if (isHost && user.userId != ltState.userId) {
                                Row {
                                    TextButton(onClick = { viewModel.transferHost(user.userId) }) {
                                        Text("Make Host", fontSize = 12.sp, color = NeonLimePrimary)
                                    }
                                    IconButton(onClick = { viewModel.kickUser(user.userId) }) {
                                        Icon(Icons.Default.PersonRemove, "Kick", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.leaveOnlineRoom() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = PureWhite
                )
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Leave Online Room", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ============================================================================
// TAB MODE BUTTON FOR THE ONLINE / LOCAL SWITCHER PILL
// ============================================================================

@Composable
private fun ModeTabButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) NeonLimePrimary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) OnNeonLime else OnSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) OnNeonLime else OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
