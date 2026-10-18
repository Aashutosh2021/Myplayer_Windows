package android.os

import javax.swing.SwingUtilities

object Build {
    object VERSION {
        const val SDK_INT: Int = 34
        const val RELEASE: String = "14"
    }
    object VERSION_CODES {
        const val O: Int = 26
        const val P: Int = 28
        const val Q: Int = 29
        const val R: Int = 30
        const val S: Int = 31
        const val TIRAMISU: Int = 33
        const val UPSIDE_DOWN_CAKE: Int = 34
    }
    const val MODEL: String = "Windows Desktop"
    const val MANUFACTURER: String = "PC"
    const val BRAND: String = "PC"
    const val DEVICE: String = "Desktop"
    const val PRODUCT: String = "MyPlayer_Windows"
    const val HARDWARE: String = "x86_64"
    const val FINGERPRINT: String = "Windows/MyPlayer/Desktop"
    const val TAGS: String = "release-keys"
    val SUPPORTED_ABIS: Array<String> = arrayOf("x86_64")
}

object Environment {
    const val DIRECTORY_MUSIC = "Music"
    const val DIRECTORY_DOWNLOADS = "Download"
}

object Process {
    fun myUid(): Int = 1000
    fun myPid(): Int = 1000
}

object Debug {
    fun isDebuggerConnected(): Boolean = false
    fun waitingForDebugger(): Boolean = false
}

class PowerManager {
    fun newWakeLock(levelAndFlags: Int, tag: String): WakeLock = WakeLock()

    class WakeLock {
        fun setReferenceCounted(value: Boolean) {}
        fun acquire(timeout: Long = 0L) {}
        fun release(flags: Int = 0) {}
        val isHeld: Boolean get() = false
        @JvmName("getIsHeldMethod")
        fun isHeld(): Boolean = false
    }

    companion object {
        const val PARTIAL_WAKE_LOCK = 1
    }
}

class Looper private constructor() {
    companion object {
        private val mainLooper = Looper()
        @JvmStatic fun getMainLooper(): Looper = mainLooper
        @JvmStatic fun myLooper(): Looper = mainLooper
    }
}

class Handler(private val looper: Looper = Looper.getMainLooper()) {
    fun post(r: Runnable): Boolean {
        SwingUtilities.invokeLater(r)
        return true
    }
    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        java.util.Timer().schedule(object : java.util.TimerTask() {
            override fun run() {
                SwingUtilities.invokeLater(r)
            }
        }, delayMillis)
        return true
    }
    fun removeCallbacks(r: Runnable) {}
    fun removeCallbacksAndMessages(token: Any?) {}
}

class Bundle {
    private val map = mutableMapOf<String, Any?>()
    fun putString(key: String, value: String?) { map[key] = value }
    fun getString(key: String): String? = map[key] as? String
    fun putInt(key: String, value: Int) { map[key] = value }
    fun getInt(key: String, default: Int = 0): Int = (map[key] as? Int) ?: default
    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun getBoolean(key: String, default: Boolean = false): Boolean = (map[key] as? Boolean) ?: default
    fun putLong(key: String, value: Long) { map[key] = value }
    fun getLong(key: String, default: Long = 0L): Long = (map[key] as? Long) ?: default
    fun putFloat(key: String, value: Float) { map[key] = value }
    fun getFloat(key: String, default: Float = 0f): Float = (map[key] as? Number)?.toFloat() ?: default
    fun putFloatArray(key: String, value: FloatArray?) { map[key] = value }
    fun getFloatArray(key: String): FloatArray? = map[key] as? FloatArray
    fun putStringArrayList(key: String, value: ArrayList<String>?) { map[key] = value }
    fun getStringArrayList(key: String): ArrayList<String>? = map[key] as? ArrayList<String>
    fun putParcelable(key: String, value: Any?) { map[key] = value }
    @Suppress("UNCHECKED_CAST")
    fun <T> getParcelable(key: String, clazz: Class<T>? = null): T? = map[key] as? T
    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun clear() { map.clear() }

    companion object {
        val EMPTY = Bundle()
    }
}

interface Parcelable {
    fun describeContents(): Int = 0
    fun writeToParcel(dest: Parcel, flags: Int) {}
    interface Creator<T> {
        fun createFromParcel(source: Parcel): T
        fun newArray(size: Int): Array<T?>
    }
}

class Parcel {
    fun readString(): String? = null
    fun writeString(valStr: String?) {}
    fun readInt(): Int = 0
    fun writeInt(valInt: Int) {}
    fun readLong(): Long = 0L
    fun writeLong(valLong: Long) {}
    fun readByte(): Byte = 0
    fun writeByte(valByte: Byte) {}
    fun readBundle(loader: ClassLoader? = null): Bundle? = Bundle()
    fun writeBundle(b: Bundle?) {}
    fun <T> createTypedArrayList(c: Any?): ArrayList<T> = ArrayList()
    fun writeTypedList(l: List<*>?) {}
    fun <T> readParcelable(c: Any?): T? = null
    fun writeParcelable(p: Any?, flags: Int) {}
    fun createStringArrayList(): ArrayList<String> = ArrayList()
    fun writeStringList(l: List<String>?) {}
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Parcelize
