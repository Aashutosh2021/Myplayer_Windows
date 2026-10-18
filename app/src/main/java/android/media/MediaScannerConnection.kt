package android.media

import android.content.Context
import android.net.Uri

class MediaScannerConnection {
    fun interface OnScanCompletedListener {
        fun onScanCompleted(path: String?, uri: Uri?)
    }

    companion object {
        @JvmStatic
        fun scanFile(
            context: Context,
            paths: Array<String>,
            mimeTypes: Array<String>?,
            callback: OnScanCompletedListener?
        ) {
            paths.forEach { path ->
                callback?.onScanCompleted(path, Uri.parse("file://$path"))
            }
        }
    }
}
