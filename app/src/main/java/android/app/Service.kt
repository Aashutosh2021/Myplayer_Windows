package android.app

import android.content.Context
import android.content.Intent
import android.os.IBinder

open class Service : Context() {
    open fun onCreate() {}
    open fun onDestroy() {}
    open fun onBind(intent: Intent): IBinder? = null
    open fun onUnbind(intent: Intent): Boolean = false
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = 0
    open fun stopSelf() {}
    open fun onTaskRemoved(rootIntent: Intent?) {}
}
