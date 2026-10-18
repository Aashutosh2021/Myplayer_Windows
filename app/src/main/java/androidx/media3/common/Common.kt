package androidx.media3.common

import android.net.Uri

object C {
    const val INDEX_UNSET = -1
    const val TIME_UNSET = -9223372036854775807L
    const val AUDIO_CONTENT_TYPE_MUSIC = 2
    const val USAGE_MEDIA = 1
    const val WAKE_MODE_NETWORK = 1
    const val ENCODING_PCM_16BIT = 2
}

class MediaMetadata {
    var title: CharSequence? = null
    var artist: CharSequence? = null
    var albumTitle: CharSequence? = null
    var artworkUri: Uri? = null

    class Builder {
        private val metadata = MediaMetadata()
        fun setTitle(title: CharSequence?): Builder { metadata.title = title; return this }
        fun setArtist(artist: CharSequence?): Builder { metadata.artist = artist; return this }
        fun setAlbumTitle(album: CharSequence?): Builder { metadata.albumTitle = album; return this }
        fun setArtworkUri(uri: Uri?): Builder { metadata.artworkUri = uri; return this }
        fun build(): MediaMetadata = metadata
    }
}

class MediaItem {
    var mediaId: String = ""
    var mediaMetadata: MediaMetadata = MediaMetadata()
    var localConfiguration: LocalConfiguration? = null

    class LocalConfiguration(val uri: Uri)

    class Builder {
        private val item = MediaItem()
        fun setMediaId(id: String): Builder { item.mediaId = id; return this }
        fun setUri(uri: String): Builder {
            item.localConfiguration = LocalConfiguration(Uri.parse(uri))
            return this
        }
        fun setUri(uri: Uri): Builder {
            item.localConfiguration = LocalConfiguration(uri)
            return this
        }
        fun setMediaMetadata(metadata: MediaMetadata): Builder { item.mediaMetadata = metadata; return this }
        fun build(): MediaItem = item
    }

    companion object {
        fun fromUri(uri: String): MediaItem = Builder().setUri(uri).build()
        fun fromUri(uri: Uri): MediaItem = Builder().setUri(uri).build()
    }
}

open class PlaybackException(
    message: String?,
    cause: Throwable?,
    val errorCode: Int = ERROR_CODE_UNSPECIFIED
) : Exception(message, cause) {
    val errorCodeName: String get() = when (errorCode) {
        ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"
        ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE"
        ERROR_CODE_IO_UNSPECIFIED -> "ERROR_CODE_IO_UNSPECIFIED"
        ERROR_CODE_DECODER_QUERY_FAILED -> "ERROR_CODE_DECODER_QUERY_FAILED"
        ERROR_CODE_DECODING_FAILED -> "ERROR_CODE_DECODING_FAILED"
        ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "ERROR_CODE_PARSING_CONTAINER_MALFORMED"
        ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "ERROR_CODE_PARSING_MANIFEST_MALFORMED"
        else -> "ERROR_CODE_$errorCode"
    }

    companion object {
        const val ERROR_CODE_UNSPECIFIED = 1000
        const val ERROR_CODE_IO_UNSPECIFIED = 2000
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
        const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE = 2003
        const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
        const val ERROR_CODE_IO_FILE_NOT_FOUND = 2005
        const val ERROR_CODE_PARSING_CONTAINER_MALFORMED = 3001
        const val ERROR_CODE_PARSING_MANIFEST_MALFORMED = 3002
        const val ERROR_CODE_PARSER_CONTAINER_UNSUPPORTED = 3003
        const val ERROR_CODE_DECODER_INIT_FAILED = 4001
        const val ERROR_CODE_DECODER_QUERY_FAILED = 4002
        const val ERROR_CODE_DECODING_FAILED = 4003
    }
}

class PlaybackParameters(val speed: Float = 1.0f, val pitch: Float = 1.0f) {
    companion object {
        val DEFAULT = PlaybackParameters()
    }
}

class Format(
    val sampleMimeType: String? = null,
    val pcmEncoding: Int = C.ENCODING_PCM_16BIT,
    val sampleRate: Int = 44100,
    val channelCount: Int = 2
) {
    companion object {
        const val NO_VALUE = -1
    }
}

object MimeTypes {
    const val AUDIO_UNKNOWN = "audio/unknown"
    const val AUDIO_RAW = "audio/raw"
}

class Clock {
    companion object {
        val DEFAULT = Clock()
    }
}

class AudioAttributes {
    class Builder {
        fun setContentType(type: Int): Builder = this
        fun setUsage(usage: Int): Builder = this
        fun build(): AudioAttributes = AudioAttributes()
    }
    companion object {
        val DEFAULT = AudioAttributes()
    }
}

class PlayerId
class AuxEffectInfo(val effectId: Int = 0, val sendLevel: Float = 0f)

interface Player {
    val currentMediaItem: MediaItem? get() = null
    val currentMediaItemIndex: Int get() = 0
    val mediaItemCount: Int get() = 0
    val isPlaying: Boolean get() = false
    val isConnected: Boolean get() = true
    val playbackState: Int get() = STATE_IDLE
    var playWhenReady: Boolean get() = false; set(_) {}
    val duration: Long get() = 0L
    val currentPosition: Long get() = 0L
    var volume: Float get() = 1.0f; set(_) {}
    var playbackParameters: PlaybackParameters get() = PlaybackParameters(); set(_) {}
    var repeatMode: Int get() = REPEAT_MODE_OFF; set(_) {}
    var shuffleModeEnabled: Boolean get() = false; set(_) {}

    fun play() {}
    fun pause() {}
    fun prepare() {}
    fun stop() {}
    fun release() {}
    fun seekTo(positionMs: Long) {}
    fun seekTo(mediaItemIndex: Int, positionMs: Long) {}
    fun seekToNext() {}
    fun seekToNextMediaItem() {}
    fun seekToPrevious() {}
    fun seekToPreviousMediaItem() {}
    fun setMediaItem(item: MediaItem) {}
    fun setMediaItem(item: MediaItem, startPositionMs: Long) {}
    fun setMediaItem(item: MediaItem, resetPosition: Boolean) {}
    fun setMediaItems(items: List<MediaItem>) {}
    fun setMediaItems(items: List<MediaItem>, resetPosition: Boolean) {}
    fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {}
    fun replaceMediaItem(index: Int, item: MediaItem) {}
    fun getMediaItemAt(index: Int): MediaItem? = null
    fun addMediaItem(item: MediaItem) {}
    fun addMediaItem(index: Int, item: MediaItem) {}
    fun removeMediaItem(index: Int) {}
    fun clearMediaItems() {}
    fun setPlaybackSpeed(speed: Float) {}
    fun addListener(listener: Listener) {}
    fun removeListener(listener: Listener) {}
    fun hasNextMediaItem(): Boolean = false
    fun hasPreviousMediaItem(): Boolean = false
    fun getAvailableCommands(): Commands = Commands()
    fun isCommandAvailable(command: Int): Boolean = true

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4

        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2

        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 0
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK = 1
        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT = 2
        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 3

        const val COMMAND_PLAY_PAUSE = 1
        const val COMMAND_SEEK_TO_NEXT = 2
        const val COMMAND_SEEK_TO_NEXT_MEDIA_ITEM = 3
        const val COMMAND_SEEK_TO_PREVIOUS = 4
        const val COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM = 5
    }

    interface Listener {
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onPositionDiscontinuity(oldPos: PositionInfo, newPos: PositionInfo, reason: Int) {}
        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
        fun onRepeatModeChanged(repeatMode: Int) {}
    }

    class PositionInfo(val positionMs: Long)

    class Commands {
        fun buildUpon(): Commands = this
        fun add(command: Int): Commands = this
        fun build(): Commands = this
        fun contains(command: Int): Boolean = true
    }
}

open class ForwardingPlayer(val player: Player) : Player by player
