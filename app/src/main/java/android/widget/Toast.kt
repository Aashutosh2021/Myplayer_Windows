package android.widget

import android.content.Context

class Toast(private val context: Context?) {
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence, duration: Int): Toast {
            println("[Toast] $text")
            return Toast(context)
        }
    }

    fun show() {
        // Logged on creation above
    }
}
