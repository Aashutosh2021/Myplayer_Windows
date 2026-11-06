package com.example.myplayer.sync.ui

import androidx.lifecycle.ViewModel
import com.example.myplayer.data.local.entity.SongEntity
import com.example.myplayer.playback.MusicController
import com.example.myplayer.sync.LtRoomUiState
import com.example.myplayer.sync.LtSongSuggestion
import com.example.myplayer.sync.MeldListenTogetherManager
import com.example.myplayer.sync.SyncPlayManager
import com.example.myplayer.sync.SyncUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SyncPlayViewModel @Inject constructor(
    private val syncPlayManager: SyncPlayManager,
    private val meldListenTogetherManager: MeldListenTogetherManager,
    private val musicController: MusicController
) : ViewModel() {

    // Local Wi-Fi Sync state
    val uiState: StateFlow<SyncUiState> = syncPlayManager.uiState

    // Online Listen Together (Meld room code) state
    val ltState: StateFlow<LtRoomUiState> = meldListenTogetherManager.uiState

    val currentSong: StateFlow<SongEntity?> = musicController.currentSong

    // Local Wi-Fi Sync actions — entering a Wi-Fi room closes any online room first
    fun createRoom(displayName: String) {
        meldListenTogetherManager.leaveOnlineRoom()
        syncPlayManager.createRoom(displayName)
    }

    fun joinNearbyRoom() {
        meldListenTogetherManager.leaveOnlineRoom()
        syncPlayManager.startDiscovery()
    }

    fun joinByIp(ip: String, port: Int = 45200) {
        meldListenTogetherManager.leaveOnlineRoom()
        syncPlayManager.joinByIp(ip, port)
    }
    fun startSyncPlayback() = syncPlayManager.startSyncPlayback()
    fun pause() = syncPlayManager.pause()
    fun resume() = syncPlayManager.resume()
    fun seek(positionMs: Long) = syncPlayManager.seek(positionMs)
    fun removeSlave(deviceId: String) = syncPlayManager.removeSlaveDevice(deviceId)
    fun leaveRoom() = syncPlayManager.leave()

    // Online Listen Together actions — entering an online room closes any Wi-Fi room first
    fun createOnlineRoom(username: String) {
        syncPlayManager.leave()
        meldListenTogetherManager.createOnlineRoom(username)
    }

    fun joinOnlineRoom(roomCode: String, username: String) {
        syncPlayManager.leave()
        meldListenTogetherManager.joinOnlineRoom(roomCode, username)
    }

    fun leaveOnlineRoom() = meldListenTogetherManager.leaveOnlineRoom()
    fun setAutoApprove(autoApprove: Boolean) = meldListenTogetherManager.setAutoApprove(autoApprove)
    fun suggestCurrentSong() = meldListenTogetherManager.suggestCurrentSong()
    fun approveJoin(userId: String) = meldListenTogetherManager.approveJoin(userId)
    fun rejectJoin(userId: String) = meldListenTogetherManager.rejectJoin(userId)
    fun approveSuggestion(suggestion: LtSongSuggestion) = meldListenTogetherManager.approveSuggestion(suggestion)
    fun rejectSuggestion(suggestionId: String) = meldListenTogetherManager.rejectSuggestion(suggestionId)
    fun transferHost(userId: String) = meldListenTogetherManager.transferHost(userId)
    fun kickUser(userId: String) = meldListenTogetherManager.kickUser(userId)

    override fun onCleared() {
        super.onCleared()
        // DO NOT stop SyncPlayManager on ViewModel cleared.
        // SyncPlayManager is an application-scoped @Singleton. Navigating away from
        // the screen via Android Back button must NOT terminate the room/transport session.
        // The session is only terminated when the user explicitly clicks "LEAVE ROOM".
    }
}
