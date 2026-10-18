package android.app

import android.content.Context
import android.content.Intent

class PendingIntent {
    companion object {
        const val FLAG_IMMUTABLE = 0x04000000
        const val FLAG_UPDATE_CURRENT = 0x08000000

        @JvmStatic
        fun getActivity(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent = PendingIntent()
        @JvmStatic
        fun getService(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent = PendingIntent()
    }
}
