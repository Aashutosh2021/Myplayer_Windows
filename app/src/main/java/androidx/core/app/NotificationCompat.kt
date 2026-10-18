package androidx.core.app

import android.content.Context

class NotificationCompat {
    class Builder(context: Context, channelId: String = "") {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setSilent(silent: Boolean): Builder = this
        fun setPriority(priority: Int): Builder = this
        fun setContentIntent(intent: Any?): Builder = this
        fun setAutoCancel(autoCancel: Boolean): Builder = this
        fun setStyle(style: Style): Builder = this
        fun addAction(icon: Int, title: CharSequence?, intent: Any?): Builder = this
        fun addAction(action: Any?): Builder = this
        fun build(): Any = Any()
    }

    open class Style
    class BigTextStyle : Style() {
        fun bigText(text: CharSequence?): BigTextStyle = this
    }

    companion object {
        const val PRIORITY_LOW = -1
        const val PRIORITY_DEFAULT = 0
    }
}
