package com.example.myplayer

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.myplayer.di.AppContainer
import com.example.myplayer.ui.screens.main.MainScreen
import com.example.myplayer.ui.theme.MyPlayerTheme

fun main() = application {
    AppContainer.initialize()
    Window(
        onCloseRequest = {
            AppContainer.cleanup()
            exitApplication()
        },
        title = "MyPlayer",
        state = rememberWindowState(width = 1200.dp, height = 800.dp)
    ) {
        MyPlayerTheme {
            MainScreen()
        }
    }
}
