package androidx.media3.datasource

import android.content.Context

class DefaultHttpDataSource {
    class Factory {
        fun setAllowCrossProtocolRedirects(allow: Boolean): Factory = this
        fun setConnectTimeoutMs(timeoutMs: Int): Factory = this
        fun setReadTimeoutMs(timeoutMs: Int): Factory = this
    }
}

class DefaultDataSource {
    class Factory(context: Context, httpDataSourceFactory: Any? = null)
}

open class HttpDataSource {
    open class HttpDataSourceException(message: String? = null, cause: Throwable? = null) : java.io.IOException(message, cause)
}
