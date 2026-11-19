package com.example.myplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.myplayer.data.update.UpdateChecker
import com.example.myplayer.data.update.UpdateInfo
import com.example.myplayer.di.AppContainer
import com.example.myplayer.ui.components.WhatsNewDialog
import com.example.myplayer.ui.components.WhatsNewRepository
import com.example.myplayer.ui.myplayer.MyPlayerRemoteNotificationManager
import com.example.myplayer.ui.myplayer.MyPlayerRemoteNotificationPopupDialog
import com.example.myplayer.ui.screens.main.MainScreen
import com.example.myplayer.ui.theme.MyPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.awt.Desktop
import java.net.URI

fun main() {
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    System.setProperty("sun.java2d.dpiaware", "true")

    // Start initializing AppContainer on a high-priority bootstrap thread concurrently
    // while Compose/Skiko initializes the native OS window so the window opens immediately.
    val bootstrapThread = Thread({
        runCatching { AppContainer.initialize() }
    }, "MyPlayer-FastBootstrap").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    }

    application {
        Window(
            onCloseRequest = {
                try {
                    AppContainer.cleanup()
                } catch (_: Throwable) {}
                exitApplication()
                kotlin.system.exitProcess(0)
            },
            title = "MyPlayer",
            icon = painterResource(R.drawable.app_icon_spark),
            state = rememberWindowState(width = 1200.dp, height = 800.dp)
        ) {
            // Ensure fast non-blocking DI graph is ready before first composition frame
            remember {
                runCatching { bootstrapThread.join(1500L) }
                AppContainer.initialize()
                true
            }

            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density * 1.25f,
                    fontScale = density.fontScale
                )
            ) {
                MyPlayerTheme {
                    MainScreen()

                    val context = LocalContext.current

                    // Remote Push Notifications Background Poller (delayed slightly so UI renders first):
                    LaunchedEffect(Unit) {
                        delay(1_500L)
                        MyPlayerRemoteNotificationManager.startAutoPolling(context)
                    }

                    var showWhatsNew by remember { mutableStateOf(WhatsNewRepository.shouldShow(context)) }
                    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

                    if (showWhatsNew) {
                        WhatsNewDialog(
                            onDismiss = {
                                showWhatsNew = false
                                WhatsNewRepository.markSeen(context)
                            }
                        )
                    }

                    val remotePopup = MyPlayerRemoteNotificationManager.activeRemotePopup
                    if (remotePopup != null && !showWhatsNew && availableUpdate == null) {
                        MyPlayerRemoteNotificationPopupDialog(
                            notification = remotePopup,
                            onDismiss = { MyPlayerRemoteNotificationManager.dismissActivePopup() },
                            onNavigateToSettings = { MyPlayerRemoteNotificationManager.dismissActivePopup() }
                        )
                    }

                    // Automatic Background Update Checker for Windows Desktop:
                    LaunchedEffect(Unit) {
                        delay(4_000L)
                        while (isActive) {
                            try {
                                val updateChecker = AppContainer.get(UpdateChecker::class.java)
                                val update = updateChecker?.checkForUpdate()
                                if (update != null && updateChecker.shouldNotifyFor(update)) {
                                    availableUpdate = update
                                }
                            } catch (_: Throwable) {}
                            delay(6 * 3600 * 1000L)
                        }
                    }

                    if (!showWhatsNew && availableUpdate != null) {
                        val update = availableUpdate!!
                        val inAppUpdater = AppContainer.get(com.example.myplayer.data.update.InAppUpdateManager::class.java)
                        if (inAppUpdater != null) {
                            com.example.myplayer.ui.components.MyPlayerInAppUpdateDialog(
                                updateInfo = update,
                                inAppUpdateManager = inAppUpdater,
                                onDismiss = {
                                    AppContainer.get(UpdateChecker::class.java)?.markNotified(update)
                                    availableUpdate = null
                                },
                                onSnooze24h = {
                                    AppContainer.get(UpdateChecker::class.java)?.snoozeUpdateFor24Hours(update.versionName)
                                    availableUpdate = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}