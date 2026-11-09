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

    application {
        AppContainer.initialize()
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
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density * 1.25f,
                    fontScale = density.fontScale
                )
            ) {
                MyPlayerTheme {
                    MainScreen()

                    // Startup "What's New" changelog (ported from Android MainActivity.onCreate):
                    // shows once per app version, then marks itself seen via SharedPreferences.
                    val context = LocalContext.current

                    // Remote Push Notifications Background Poller:
                    LaunchedEffect(Unit) {
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

                    // Remote notification in-app popup dialog:
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
                        delay(4_000)
                        while (isActive) {
                            try {
                                val updateChecker = AppContainer.get(UpdateChecker::class.java)
                                val update = updateChecker?.checkForUpdate()
                                if (update != null && updateChecker.shouldNotifyFor(update)) {
                                    availableUpdate = update
                                }
                            } catch (_: Throwable) {}
                            delay(6 * 3600 * 1000L) // Re-check every 6 hours while running
                        }
                    }

                    if (!showWhatsNew && availableUpdate != null) {
                        val update = availableUpdate!!
                        AlertDialog(
                            onDismissRequest = { availableUpdate = null },
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            title = { Text("Update Available — ${update.versionName}") },
                            text = {
                                Column {
                                    Text(
                                        "A new version of MyPlayer for Windows is available!",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Download the latest release for new features and performance improvements.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (update.releaseNotes.isNotBlank()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            update.releaseNotes.take(300),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        AppContainer.get(UpdateChecker::class.java)?.markNotified(update)
                                        availableUpdate = null
                                        try {
                                            Desktop.getDesktop().browse(URI(update.releaseUrl))
                                        } catch (_: Throwable) {}
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Download Update", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        AppContainer.get(UpdateChecker::class.java)?.markNotified(update)
                                        availableUpdate = null
                                    }
                                ) {
                                    Text("Later")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
