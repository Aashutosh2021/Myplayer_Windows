package com.example.myplayer.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.myplayer.ui.components.FloatingNavBar
import com.example.myplayer.ui.components.WhatsNewDialog
import com.example.myplayer.ui.components.WhatsNewRepository
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myplayer.ui.myplayer.*
import com.example.myplayer.ui.navigation.Screen
import com.example.myplayer.ui.screens.home.HomeViewModel
import com.example.myplayer.ui.screens.downloads.DownloadsScreen
import com.example.myplayer.ui.screens.home.HomeScreen
import com.example.myplayer.ui.screens.library.LibraryScreen
import com.example.myplayer.ui.screens.library.PlaylistDetailScreen
import com.example.myplayer.ui.screens.nowplaying.NowPlayingScreen
import com.example.myplayer.ui.screens.search.SearchScreen
import com.example.myplayer.ui.screens.settings.SettingsScreen
import com.example.myplayer.ui.theme.CloudBlueBackground

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val homeViewModel: HomeViewModel = hiltViewModel()
    val myplayerState = rememberMyPlayerUiStateHolder()
    // Audio Lab -> real DSP for the whole UI lifetime (restores saved settings at app start).
    MyPlayerAudioLabEngineSync(myplayerState)
    var showStylePicker by remember { mutableStateOf(false) }
    var showWhatsNew by remember { mutableStateOf(false) }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val currentOnlineSong by viewModel.currentOnlineSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val playbackError by viewModel.playbackError.collectAsStateWithLifecycle()
    LaunchedEffect(playbackError) {
        playbackError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearPlaybackError()
        }
    }

    val downloadError by viewModel.downloadError.collectAsStateWithLifecycle()
    LaunchedEffect(downloadError) {
        downloadError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearDownloadError()
        }
    }

    val hasAnySong = currentSong != null || currentOnlineSong != null
    val displayTitle = currentSong?.title ?: currentOnlineSong?.title
    val displayArtist = currentSong?.artist ?: currentOnlineSong?.artist
    val displayArt = currentSong?.albumArt ?: currentOnlineSong?.thumbnailUrl

    val isOnNowPlaying = currentRoute == Screen.NowPlaying.route

    // 1. Onboarding runs as a full-window gate before any nav surface is shown.
    if (!myplayerState.onboardingDone) {
        MyPlayerOnboardingFlow(
            myplayerState = myplayerState,
            spotifyManager = homeViewModel.spotifyManager,
            onComplete = { username, selectedArtists ->
                myplayerState.completeOnboarding(username, selectedArtists)
                homeViewModel.setUserName(username)
                homeViewModel.saveUserTastePreferences(
                    setOf("Hindi", "Punjabi"),
                    setOf("Bollywood", "Pop"),
                    selectedArtists
                )
            }
        )
        return
    }

    // 2. Interactive 7-Step Feature Tour runs automatically right after Onboarding
    //    (and whenever the user taps "Interactive Feature Tour" in Settings).
    if (!myplayerState.featureTourDone) {
        MyPlayerInteractiveFeatureTour(
            myplayerState = myplayerState,
            onApplyEqToEngine = { enabled, gains, preamp ->
                MyPlayerAudioFxDspBridge.setEq(enabled, gains, preamp)
            },
            onFinishTour = {
                myplayerState.completeFeatureTour()
                if (WhatsNewRepository.shouldShow(context)) showWhatsNew = true
            }
        )
        return
    }

    if (showWhatsNew) {
        WhatsNewDialog(
            onDismiss = {
                showWhatsNew = false
                WhatsNewRepository.markSeen(context)
            }
        )
    }

    if (showStylePicker) {
        Dialog(
            onDismissRequest = { showStylePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            MyPlayerPlayerStylesPickerScreen(
                myplayerState = myplayerState,
                previewTitle = displayTitle ?: "Khat",
                previewArtist = displayArtist ?: "Navjot Ahuja",
                previewArtUri = displayArt,
                onBack = { showStylePicker = false }
            )
        }
    }

    // Remote push notification handling & deep redirect to settings
    LaunchedEffect(Unit) {
        MyPlayerRemoteNotificationManager.startAutoPolling(context)
    }

    LaunchedEffect(MyPlayerRemoteNotificationManager.pendingNavigateToSettings) {
        if (MyPlayerRemoteNotificationManager.pendingNavigateToSettings) {
            MyPlayerRemoteNotificationManager.pendingNavigateToSettings = false
            navController.navigate(Screen.Settings.route) { launchSingleTop = true }
        }
    }

    MyPlayerRemoteNotificationManager.activeRemotePopup?.let { remoteNotif ->
        MyPlayerRemoteNotificationPopupDialog(
            notification = remoteNotif,
            onDismiss = { MyPlayerRemoteNotificationManager.dismissActivePopup() },
            onNavigateToSettings = {
                MyPlayerRemoteNotificationManager.dismissActivePopup()
                navController.navigate(Screen.Settings.route) { launchSingleTop = true }
            }
        )
    }

    // Funny mood / vibe-check streak tracker + its popup (hoisted so active even when MiniPlayer is hidden)
    LaunchedEffect(displayTitle, displayArtist, isPlaying) {
        MyPlayerVibeCheckManager.onPlaybackTick(displayTitle ?: "", displayArtist ?: "", "", isPlaying)
    }
    MyPlayerVibeCheckManager.activePopup?.let { event ->
        MyPlayerVibeCheckPopupDialog(
            event = event,
            onDismiss = { MyPlayerVibeCheckManager.dismissPopup() },
            onSwitchVibeClick = {
                MyPlayerVibeCheckManager.dismissPopup()
                viewModel.skipToNext()
            }
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        FloatingNavBar(
            currentRoute = currentRoute,
            onNavigate = { route ->
                if (route == currentRoute) return@FloatingNavBar
                if (route == Screen.Home.route) {
                    val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
                    if (!popped) {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = false
                            }
                            launchSingleTop = true
                        }
                    }
                } else {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        )

        val density = androidx.compose.ui.platform.LocalDensity.current
        var barHeight by remember { mutableStateOf(0.dp) }

        Scaffold(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            containerColor = CloudBlueBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { barHeight = with(density) { it.size.height.toDp() } }
                        .background(androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = hasAnySong && !isOnNowPlaying,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + scaleIn(initialScale = 0.88f) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        val miniSong = remember(currentSong, currentOnlineSong, displayArt, displayTitle, displayArtist) {
                            currentSong?.let {
                                if (it.albumArt.isNullOrBlank() && !displayArt.isNullOrBlank()) {
                                    it.copy(albumArt = displayArt)
                                } else {
                                    it
                                }
                            } ?: com.example.myplayer.data.local.entity.SongEntity(
                                id = currentOnlineSong?.videoId ?: "",
                                title = displayTitle ?: "",
                                artist = displayArtist ?: "",
                                album = "",
                                duration = 0,
                                path = displayArt ?: "",
                                albumArt = displayArt,
                                dateAdded = 0
                            )
                        }
                        MiniPlayer(
                            song = miniSong,
                            isPlaying = isPlaying,
                            onPlayPauseClick = { viewModel.playPause() },
                            onNextClick = { viewModel.skipToNext() },
                            onPreviousClick = { viewModel.skipToPrevious() },
                            onPlayerClick = {
                                navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                            }
                        )
                    }
                }
            }
        ) { _ ->
            val effectiveBottomPadding = barHeight.coerceAtLeast(16.dp)
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth / 4 },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + scaleIn(
                        initialScale = 0.92f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(tween(180))
                },
                exitTransition = {
                    scaleOut(
                        targetScale = 0.94f,
                        animationSpec = tween(150)
                    ) + fadeOut(tween(150))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth / 4 },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + scaleIn(initialScale = 0.94f) + fadeIn(tween(180))
                },
                popExitTransition = {
                    scaleOut(targetScale = 0.92f) + fadeOut(tween(150))
                },
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        onNavigateToNowPlaying = {
                            navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                        },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                        },
                        bottomPadding = effectiveBottomPadding
                    )
                }
                composable(Screen.Search.route) {
                    SearchScreen(
                        onNavigateToNowPlaying = {
                            navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                        },
                        bottomPadding = effectiveBottomPadding
                    )
                }
                composable(Screen.Trending.route) {
                    com.example.myplayer.ui.screens.trending.TrendingScreen(
                        onNavigateToNowPlaying = {
                            navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                        },
                        bottomPadding = effectiveBottomPadding,
                        viewModel = homeViewModel
                    )
                }
                composable(Screen.Downloads.route) {
                    DownloadsScreen(
                        onNavigateToNowPlaying = {
                            navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                        },
                        bottomPadding = effectiveBottomPadding
                    )
                }
                composable(Screen.Library.route) {
                    LibraryScreen(
                        onPlaylistClick = { playlistId ->
                            navController.navigate("playlist_detail/$playlistId")
                        },
                        onPlaySong = { songs, index ->
                            viewModel.playPlaylist(songs, index)
                            navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                        },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route)
                        },
                        myplayerState = myplayerState,
                        onOpenCustomizeStyles = { showStylePicker = true },
                        onOpenAudioLab = { navController.navigate(Screen.MyPlayerAudioLab.route) { launchSingleTop = true } },
                        onOpenAppearance = { navController.navigate(Screen.MyPlayerAppearance.route) { launchSingleTop = true } },
                        bottomPadding = effectiveBottomPadding
                    )
                }
                composable("playlist_detail/{playlistId}") { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull() ?: -1L
                    PlaylistDetailScreen(
                        playlistId = playlistId,
                        onBack = { navController.popBackStack() },
                        onPlaySong = { songs: List<com.example.myplayer.data.repository.PlayableSong>, index: Int ->
                            viewModel.playPlaylist(songs, index)
                            navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                        }
                    )
                }
                composable(
                    route = Screen.NowPlaying.route,
                    enterTransition = {
                        slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + scaleIn(initialScale = 0.90f) + fadeIn()
                    },
                    exitTransition = {
                        slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    },
                    popExitTransition = {
                        slideOutVertically(targetOffsetY = { it }) +
                            scaleOut(targetScale = 0.90f) +
                            fadeOut()
                    }
                ) {
                    val sleepTimerRemaining by viewModel.sleepTimerRemainingSeconds.collectAsStateWithLifecycle()
                    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
                    val isShuffleOn by viewModel.isShuffleOn.collectAsStateWithLifecycle()
                    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
                    val durationMs = currentSong?.duration ?: viewModel.currentDuration.collectAsStateWithLifecycle().value
                    val canDownload by viewModel.isCurrentSongOnline.collectAsStateWithLifecycle()
                    val isDownloaded by viewModel.isCurrentSongDownloaded.collectAsStateWithLifecycle()
                    val isDownloading by viewModel.isCurrentSongDownloading.collectAsStateWithLifecycle()
                    val currentAudioQuality by viewModel.currentAudioQuality.collectAsStateWithLifecycle()
                    val positionState = viewModel.currentPosition.collectAsStateWithLifecycle()
                    val sideTracks by viewModel.sideTracks.collectAsStateWithLifecycle()
                    val sideTrackTitle by viewModel.contextTitle.collectAsStateWithLifecycle()
                    val playbackContext by viewModel.playbackSourceContext.collectAsStateWithLifecycle()
                    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
                    val playerVolume by viewModel.playerVolume.collectAsStateWithLifecycle()

                    NowPlayingScreen(
                        myplayerState = myplayerState,
                        canDownload = canDownload,
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        onDownloadClick = { viewModel.downloadCurrentSong() },
                        onRemoveDownloadClick = { viewModel.removeCurrentSongDownload() },
                        audioQuality = currentAudioQuality,
                        title = displayTitle,
                        artist = displayArtist,
                        artUri = currentSong?.albumArt ?: currentOnlineSong?.thumbnailUrl,
                        durationMs = durationMs,
                        isPlaying = isPlaying,
                        positionState = positionState,
                        onPlayPauseClick = { viewModel.playPause() },
                        onNextClick = { viewModel.skipToNext() },
                        onPreviousClick = { viewModel.skipToPrevious() },
                        onSeek = { viewModel.seekTo(it) },
                        onBackClick = { navController.popBackStack() },
                        isFavorite = isFavorite,
                        onToggleFavorite = { viewModel.toggleFavorite() },
                        sleepTimerRemaining = sleepTimerRemaining,
                        onStartSleepTimer = { viewModel.startSleepTimer(it) },
                        onCancelSleepTimer = { viewModel.cancelSleepTimer() },
                        isShuffleOn = isShuffleOn,
                        onToggleShuffle = { viewModel.toggleShuffle() },
                        repeatMode = repeatMode,
                        onCycleRepeatMode = { viewModel.cycleRepeatMode() },
                        onNavigateToSettings = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
                        sideTracks = sideTracks,
                        sideTrackTitle = sideTrackTitle,
                        playbackContext = playbackContext,
                        onSideTrackClick = { viewModel.playSideTrack(it) },
                        onContextChange = { viewModel.setSideTrackContext(it) },
                        playlists = playlists,
                        onAddToPlaylist = { viewModel.addCurrentSongToPlaylist(it) },
                        onAddTrackToPlaylist = { playlistId, track -> viewModel.addSongToPlaylist(playlistId, track) },
                        onCreatePlaylistAndAdd = { viewModel.createPlaylistAndAdd(it) },
                        playerVolume = playerVolume,
                        onVolumeChange = { viewModel.setPlayerVolume(it) },
                        onOpenCustomizeStyles = { showStylePicker = true }
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onBack = {
                            val popped = navController.popBackStack()
                            if (!popped) {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Home.route) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        },
                        onNavigateToDualBud = { navController.navigate(Screen.DualBud.route) { launchSingleTop = true } },
                        onNavigateToSyncPlay = { navController.navigate(Screen.SyncPlay.route) { launchSingleTop = true } },
                        onNavigateToAudioPlayback = { navController.navigate(Screen.AudioPlayback.route) { launchSingleTop = true } },
                        myplayerState = myplayerState,
                        onNavigateToPlayerStyles = { showStylePicker = true },
                        onNavigateToMyPlayerAppearance = { navController.navigate(Screen.MyPlayerAppearance.route) { launchSingleTop = true } },
                        onNavigateToMyPlayerAudioLab = { navController.navigate(Screen.MyPlayerAudioLab.route) { launchSingleTop = true } },
                        onNavigateToRemoteNotifications = { navController.navigate(Screen.RemoteNotifications.route) { launchSingleTop = true } },
                        bottomPadding = 16.dp
                    )
                }
                composable(Screen.MyPlayerAudioLab.route) {
                    MyPlayerAudioLabHubScreen(
                        myplayerState = myplayerState,
                        onOpenEqualizer = { navController.navigate(Screen.MyPlayerEqualizer.route) { launchSingleTop = true } },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.MyPlayerEqualizer.route) {
                    MyPlayerEqualizerScreen(
                        myplayerState = myplayerState,
                        onApplyGainsToEngine = { enabled, gains, preamp -> MyPlayerAudioFxDspBridge.setEq(enabled, gains, preamp) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.MyPlayerAppearance.route) {
                    MyPlayerAppearanceSettingsScreen(
                        myplayerState = myplayerState,
                        onOpenStylesPicker = { showStylePicker = true },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.RemoteNotifications.route) {
                    MyPlayerRemoteNotificationsCenterScreen(
                        myplayerState = myplayerState,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.DualBud.route) {
                    com.example.myplayer.dualbud.ui.DualBudScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.SyncPlay.route) {
                    com.example.myplayer.sync.ui.SyncPlayScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToNowPlaying = { navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true } }
                    )
                }
                composable(Screen.AudioPlayback.route) {
                    val settingsVm: com.example.myplayer.ui.screens.settings.SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                    com.example.myplayer.ui.settings.AudioAndPlaybackScreen(
                        store = settingsVm.audioPlaybackStore,
                        featureManagers = settingsVm.audioFeatureManagers,
                        onBack = { navController.popBackStack() },
                        myplayerState = myplayerState
                    )
                }
            }
        }
    }
}