package android.app

class NotificationChannel(val id: String, val name: CharSequence, val importance: Int) {
    var description: String = ""
}

class NotificationManager {
    fun getNotificationChannel(id: String): NotificationChannel? = null
    fun createNotificationChannel(channel: NotificationChannel) {}
    fun notify(id: Int, notification: Any?) {}
    fun cancel(id: Int) {}

    companion object {
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
    }
}
