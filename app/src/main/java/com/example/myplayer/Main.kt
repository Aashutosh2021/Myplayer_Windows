package com.example.myplayer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.myplayer.di.AppContainer
import com.example.myplayer.ui.components.WhatsNewDialog
import com.example.myplayer.ui.components.WhatsNewRepository
import com.example.myplayer.ui.screens.main.MainScreen
import com.example.myplayer.ui.theme.MyPlayerTheme

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
                    var showWhatsNew by remember { mutableStateOf(WhatsNewRepository.shouldShow(context)) }
                    if (showWhatsNew) {
                        WhatsNewDialog(
                            onDismiss = {
                                showWhatsNew = false
                                WhatsNewRepository.markSeen(context)
                            }
                        )
                    }
                }
            }
        }
    }
}
