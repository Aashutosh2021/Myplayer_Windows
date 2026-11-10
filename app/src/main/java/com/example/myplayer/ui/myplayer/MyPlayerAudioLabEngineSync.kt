package com.example.myplayer.ui.myplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Keeps both the real-time Audio Lab DSP engine and Appearance / Theme engine
 * synchronized and persisted across the entire app lifecycle.
 */
@Composable
fun MyPlayerAudioLabEngineSync(myplayerState: MyPlayerUiStateHolder) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(myplayerState) {
        MyPlayerAudioFxDspBridge.loadFineTune(appContext)
        launch {
            snapshotFlow { myplayerState.audioLabSignature() }.collect {
                MyPlayerAudioFxDspBridge.syncFromUiState(myplayerState)
                myplayerState.saveAudioLabToPrefs()
            }
        }
        launch {
            snapshotFlow { myplayerState.appearanceSignature() }.collect {
                myplayerState.saveAppearanceToPrefs()
            }
        }
    }
}
