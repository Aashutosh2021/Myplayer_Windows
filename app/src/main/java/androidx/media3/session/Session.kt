package androidx.media3.session

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.google.common.util.concurrent.ImmediateFuture
import com.google.common.util.concurrent.ListenableFuture

class SessionToken(val context: Context, val componentName: ComponentName)

class MediaController : Player {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Player.Listener>()
    private val queue = mutableListOf<MediaItem>()
    private var currentIndex = 0

    override var repeatMode: Int = Player.REPEAT_MODE_OFF
        set(value) {
            field = value
            listeners.forEach { it.onRepeatModeChanged(value) }
        }

    override var shuffleModeEnabled: Boolean = false
        set(value) {
            field = value
            listeners.forEach { it.onShuffleModeEnabledChanged(value) }
        }

    override var playWhenReady: Boolean = false
        set(value) {
            field = value
            if (value) play() else pause()
        }

    override val isPlaying: Boolean
        get() = com.example.myplayer.playback.DesktopAudioBackend.isPlaying()

    override val isConnected: Boolean = true
    override var playbackState: Int = Player.STATE_IDLE
        private set

    override val currentPosition: Long
        get() = com.example.myplayer.playback.DesktopAudioBackend.getPosition()

    override val duration: Long
        get() = com.example.myplayer.playback.DesktopAudioBackend.getDuration()

    override val mediaItemCount: Int
        get() = queue.size

    override val currentMediaItem: MediaItem?
        get() = queue.getOrNull(currentIndex)

    override val currentMediaItemIndex: Int
        get() = currentIndex

    override var volume: Float = 1.0f
        set(value) {
            field = value
            com.example.myplayer.playback.DesktopAudioBackend.setVolume(value)
        }

    override var playbackParameters: PlaybackParameters = PlaybackParameters()

    init {
        com.example.myplayer.playback.DesktopAudioBackend.setOnCompletion {
            handleTrackCompletion()
        }
        com.example.myplayer.playback.DesktopAudioBackend.setOnPlayingChanged { playing ->
            listeners.forEach { it.onIsPlayingChanged(playing) }
        }
    }

    private fun handleTrackCompletion() {
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            seekTo(0L)
            play()
            return
        }
        if (hasNextMediaItem()) {
            seekToNext()
        } else if (repeatMode == Player.REPEAT_MODE_ALL && queue.isNotEmpty()) {
            currentIndex = 0
            notifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            play()
        } else {
            playbackState = Player.STATE_ENDED
            listeners.forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        }
    }

    private fun notifyMediaItemTransition(reason: Int) {
        listeners.forEach { it.onMediaItemTransition(currentMediaItem, reason) }
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    override fun play() {
        val item = currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: item.mediaId
        playbackState = Player.STATE_READY
        listeners.forEach {
            it.onPlaybackStateChanged(Player.STATE_READY)
            it.onIsPlayingChanged(true)
        }
        com.example.myplayer.playback.DesktopAudioBackend.play(uri)
    }

    override fun pause() {
        com.example.myplayer.playback.DesktopAudioBackend.pause()
        listeners.forEach { it.onIsPlayingChanged(false) }
    }

    override fun prepare() {
        playbackState = Player.STATE_READY
        listeners.forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
    }

    override fun stop() {
        com.example.myplayer.playback.DesktopAudioBackend.stop()
        playbackState = Player.STATE_IDLE
        listeners.forEach {
            it.onPlaybackStateChanged(Player.STATE_IDLE)
            it.onIsPlayingChanged(false)
        }
    }

    override fun release() {
        stop()
        com.example.myplayer.playback.DesktopAudioBackend.release()
    }

    override fun seekTo(positionMs: Long) {
        com.example.myplayer.playback.DesktopAudioBackend.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex in queue.indices) {
            currentIndex = mediaItemIndex
            notifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            com.example.myplayer.playback.DesktopAudioBackend.seekTo(positionMs)
            if (playWhenReady) play()
        }
    }

    override fun seekToNext() {
        if (hasNextMediaItem()) {
            currentIndex++
            notifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            play()
        }
    }

    override fun seekToNextMediaItem() = seekToNext()

    override fun seekToPrevious() {
        if (hasPreviousMediaItem()) {
            currentIndex--
            notifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            play()
        } else {
            seekTo(0L)
        }
    }

    override fun seekToPreviousMediaItem() = seekToPrevious()

    override fun setMediaItem(item: MediaItem) {
        queue.clear()
        queue.add(item)
        currentIndex = 0
        notifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
    }

    override fun setMediaItem(item: MediaItem, startPositionMs: Long) {
        setMediaItem(item)
        seekTo(startPositionMs)
    }

    override fun setMediaItem(item: MediaItem, resetPosition: Boolean) {
        setMediaItem(item)
        if (resetPosition) seekTo(0L)
    }

    override fun setMediaItems(items: List<MediaItem>) {
        queue.clear()
        queue.addAll(items)
        currentIndex = 0
        notifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
    }

    override fun setMediaItems(items: List<MediaItem>, resetPosition: Boolean) {
        setMediaItems(items)
        if (resetPosition) seekTo(0L)
    }

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        queue.clear()
        queue.addAll(items)
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        notifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        seekTo(startPositionMs)
    }

    override fun replaceMediaItem(index: Int, item: MediaItem) {
        if (index in queue.indices) {
            queue[index] = item
            if (index == currentIndex) {
                notifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
        }
    }

    override fun getMediaItemAt(index: Int): MediaItem? = queue.getOrNull(index)

    override fun addMediaItem(item: MediaItem) {
        queue.add(item)
    }

    override fun addMediaItem(index: Int, item: MediaItem) {
        if (index in 0..queue.size) queue.add(index, item)
    }

    override fun removeMediaItem(index: Int) {
        if (index in queue.indices) {
            queue.removeAt(index)
            if (currentIndex >= queue.size) currentIndex = (queue.size - 1).coerceAtLeast(0)
        }
    }

    override fun clearMediaItems() {
        queue.clear()
        currentIndex = 0
    }

    override fun setPlaybackSpeed(speed: Float) {
        playbackParameters = PlaybackParameters(speed, playbackParameters.pitch)
    }

    override fun hasNextMediaItem(): Boolean = currentIndex + 1 < queue.size

    override fun hasPreviousMediaItem(): Boolean = currentIndex > 0

    override fun getAvailableCommands(): Player.Commands = Player.Commands()
    override fun isCommandAvailable(command: Int): Boolean = true

    interface Listener {
        fun onDisconnected(controller: MediaController) {}
    }

    companion object {
        private var sharedController: MediaController? = null
        fun getShared(): MediaController {
            return sharedController ?: MediaController().also { sharedController = it }
        }
    }

    class Builder(private val context: Context, private val token: SessionToken) {
        fun setApplicationLooper(looper: android.os.Looper): Builder = this
        fun setListener(listener: Listener): Builder = this
        fun buildAsync(): ListenableFuture<MediaController> {
            return ImmediateFuture(getShared())
        }
    }
}

open class MediaSessionService : android.app.Service() {
    open fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
    open fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {}
}

class MediaSession {
    val player: Player = object : Player {}

    class Builder(val context: Context, val player: Player) {
        fun setCallback(callback: Callback): Builder = this
        fun build(): MediaSession = MediaSession()
    }

    class SessionCommands {
        fun buildUpon(): SessionCommands = this
        fun add(command: Any?): SessionCommands = this
        fun build(): SessionCommands = this
    }

    class ConnectionResult(
        val availableSessionCommands: SessionCommands = SessionCommands(),
        val availablePlayerCommands: Player.Commands = Player.Commands()
    ) {
        companion object {
            fun accept(availableSessionCommands: SessionCommands, availablePlayerCommands: Player.Commands): ConnectionResult =
                ConnectionResult(availableSessionCommands, availablePlayerCommands)
        }
    }

    interface Callback {
        fun onConnect(session: MediaSession, controller: ControllerInfo): ConnectionResult =
            ConnectionResult(SessionCommands(), Player.Commands())

        fun onPlaybackResumption(
            session: MediaSession,
            controller: ControllerInfo
        ): ListenableFuture<MediaItemsWithStartPosition> =
            ImmediateFuture(MediaItemsWithStartPosition(emptyList(), 0, 0L))
    }

    class ControllerInfo

    class MediaItemsWithStartPosition(
        val mediaItems: List<MediaItem>,
        val startIndex: Int,
        val startPositionMs: Long
    )

    fun release() {}
}
