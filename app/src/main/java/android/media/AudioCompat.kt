package android.media

class AudioAttributes {
    class Builder {
        fun setContentType(contentType: Int): Builder = this
        fun setUsage(usage: Int): Builder = this
        fun build(): AudioAttributes = AudioAttributes()
    }

    companion object {
        const val CONTENT_TYPE_MUSIC = 2
        const val USAGE_MEDIA = 1
    }
}

class AudioFocusRequest {
    class Builder(val focusGain: Int) {
        fun setAudioAttributes(attributes: AudioAttributes): Builder = this
        fun setOnAudioFocusChangeListener(listener: AudioManager.OnAudioFocusChangeListener, handler: Any? = null): Builder = this
        fun build(): AudioFocusRequest = AudioFocusRequest()
    }
}

class AudioManager {
    fun interface OnAudioFocusChangeListener {
        fun onAudioFocusChange(focusChange: Int)
    }

    fun requestAudioFocus(request: AudioFocusRequest): Int = AUDIOFOCUS_REQUEST_GRANTED
    fun requestAudioFocus(listener: OnAudioFocusChangeListener?, streamType: Int, durationHint: Int): Int = AUDIOFOCUS_REQUEST_GRANTED
    fun abandonAudioFocusRequest(request: AudioFocusRequest): Int = AUDIOFOCUS_REQUEST_GRANTED
    fun abandonAudioFocus(listener: OnAudioFocusChangeListener?): Int = AUDIOFOCUS_REQUEST_GRANTED

    companion object {
        const val STREAM_MUSIC = 3
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
        const val AUDIOFOCUS_GAIN = 1
        const val AUDIOFOCUS_LOSS = -1
        const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
    }
}

class AudioFormat {
    class Builder {
        fun setEncoding(encoding: Int): Builder = this
        fun setSampleRate(sampleRate: Int): Builder = this
        fun setChannelMask(channelMask: Int): Builder = this
        fun build(): AudioFormat = AudioFormat()
    }

    companion object {
        const val ENCODING_PCM_16BIT = 2
        const val CHANNEL_OUT_STEREO = 12
        const val CHANNEL_OUT_MONO = 4
    }
}

class AudioTrack {
    class Builder {
        fun setAudioAttributes(attrs: Any?): Builder = this
        fun setAudioFormat(format: Any?): Builder = this
        fun setBufferSizeInBytes(size: Int): Builder = this
        fun setTransferMode(mode: Int): Builder = this
        fun build(): AudioTrack = AudioTrack()
    }

    fun play() {}
    fun pause() {}
    fun flush() {}
    fun stop() {}
    fun release() {}
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, writeMode: Int = 0): Int = sizeInBytes
    fun write(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, writeMode: Int = 0): Int = sizeInShorts
    val playbackHeadPosition: Int get() = 0

    companion object {
        const val WRITE_BLOCKING = 0
        const val MODE_STREAM = 1

        @JvmStatic
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
    }
}
