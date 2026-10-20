package android.content

import android.net.Uri
import java.io.File
import java.util.prefs.Preferences

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getLong(key: String, defValue: Long): Long
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putLong(key: String, value: Long): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

class DesktopSharedPreferences(private val name: String) : SharedPreferences {
    private val dir = File(System.getProperty("user.home"), ".myplayer/prefs").apply { mkdirs() }
    private val file = File(dir, "$name.properties")
    private val data = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        if (file.exists()) {
            try {
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    val props = java.util.Properties()
                    props.load(reader)
                    props.forEach { (k, v) -> data[k.toString()] = v.toString() }
                }
            } catch (_: Exception) {}
        } else {
            try {
                val legacy = Preferences.userRoot().node("myplayer/$name")
                val keys = legacy.keys()
                if (keys != null && keys.isNotEmpty()) {
                    for (k in keys) {
                        val v = legacy.get(k, null)
                        if (v != null) data[k] = v
                    }
                    persist()
                }
            } catch (_: Exception) {}
        }
    }

    override fun getString(key: String, defValue: String?): String? = data[key] ?: defValue
    override fun getInt(key: String, defValue: Int): Int = data[key]?.toIntOrNull() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key]?.toBooleanStrictOrNull() ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key]?.toLongOrNull() ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl(this)

    @Synchronized
    private fun persist() {
        try {
            val props = java.util.Properties()
            data.forEach { (k, v) -> props[k] = v }
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                props.store(writer, null)
            }
        } catch (_: Exception) {}
    }

    private class EditorImpl(private val parent: DesktopSharedPreferences) : SharedPreferences.Editor {
        private val actions = mutableListOf<() -> Unit>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            actions.add { if (value != null) parent.data[key] = value else parent.data.remove(key) }
            return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            actions.add { parent.data[key] = value.toString() }
            return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            actions.add { parent.data[key] = value.toString() }
            return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            actions.add { parent.data[key] = value.toString() }
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            actions.add { parent.data.remove(key) }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            actions.add { parent.data.clear() }
            return this
        }
        override fun apply() {
            actions.forEach { it() }
            actions.clear()
            parent.persist()
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
    }
}

class ContentResolver {
    fun openInputStream(uri: Uri): java.io.InputStream? {
        val path = uri.path ?: uri.toString()
        val file = File(path)
        return if (file.exists()) file.inputStream() else null
    }

    fun openOutputStream(uri: Uri): java.io.OutputStream? {
        val path = uri.path ?: uri.toString()
        val file = File(path)
        file.parentFile?.mkdirs()
        return file.outputStream()
    }

    fun openFileDescriptor(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val path = uri.path ?: uri.toString()
        val file = File(path)
        return if (file.exists()) android.os.ParcelFileDescriptor(file) else null
    }

    fun openAssetFileDescriptor(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? {
        val path = uri.path ?: uri.toString()
        val file = File(path)
        return if (file.exists()) android.content.res.AssetFileDescriptor(file) else null
    }

    fun loadThumbnail(uri: Uri, size: Any?, signal: Any?): Any? = null
}

class ClipboardManager {
    fun setPrimaryClip(clip: ClipData) {
        val stringSelection = java.awt.datatransfer.StringSelection(clip.text.toString())
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, null)
    }
}

class ClipData(val text: CharSequence) {
    companion object {
        fun newPlainText(label: CharSequence, text: CharSequence): ClipData = ClipData(text)
    }
}

open class Context {
    open val applicationContext: Context get() = this
    open val packageName: String = "com.example.myplayer"
    open val packageManager: android.content.pm.PackageManager = android.content.pm.PackageManager()
    open val applicationInfo: android.content.pm.ApplicationInfo = android.content.pm.ApplicationInfo()
    open val contentResolver: ContentResolver = ContentResolver()
    open val resources: Any get() = this

    open val filesDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".myplayer/files")
        dir.mkdirs()
        dir
    }

    open val cacheDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".myplayer/cache")
        dir.mkdirs()
        dir
    }

    open fun getExternalFilesDir(type: String?): File {
        val dir = File(System.getProperty("user.home"), ".myplayer/files/${type ?: ""}")
        dir.mkdirs()
        return dir
    }

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return DesktopSharedPreferences(name)
    }

    open fun getSystemService(name: String): Any? {
        return when (name) {
            TELEPHONY_SERVICE -> android.telephony.TelephonyManager()
            SENSOR_SERVICE -> android.hardware.SensorManager()
            CONNECTIVITY_SERVICE -> android.net.ConnectivityManager()
            NOTIFICATION_SERVICE -> android.app.NotificationManager()
            CLIPBOARD_SERVICE -> ClipboardManager()
            POWER_SERVICE -> android.os.PowerManager()
            WIFI_SERVICE -> android.net.wifi.WifiManager()
            NSD_SERVICE -> android.net.nsd.NsdManager()
            AUDIO_SERVICE -> android.media.AudioManager()
            else -> null
        }
    }

    open fun checkPermission(permission: String, pid: Int, uid: Int): Int = 0
    open fun startService(intent: Intent?): Any? = null
    open fun stopService(intent: Intent?): Boolean = true
    open fun startActivity(intent: Intent?) {
        try {
            intent?.data?.let { uri ->
                val str = uri.toString()
                if (str.startsWith("http://") || str.startsWith("https://")) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(str))
                }
            }
        } catch (e: Exception) {
            println("Cannot open URL on desktop: ${e.message}")
        }
    }

    companion object {
        const val MODE_PRIVATE = 0
        const val POWER_SERVICE = "power"
        const val WIFI_SERVICE = "wifi"
        const val NSD_SERVICE = "servicediscovery"
        const val AUDIO_SERVICE = "audio"
        const val CLIPBOARD_SERVICE = "clipboard"
        const val TELEPHONY_SERVICE = "phone"
        const val SENSOR_SERVICE = "sensor"
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val NOTIFICATION_SERVICE = "notification"

        val DEFAULT = Context()
    }
}

class ComponentName(val pkg: String, val cls: String) {
    constructor(context: Context, cls: Class<*>) : this(context.packageName, cls.name)
}

class Intent(var action: String? = null, var data: Uri? = null) {
    constructor(context: Context, cls: Class<*>) : this(cls.name, null)

    private val extras = mutableMapOf<String, Any?>()

    fun putExtra(name: String, value: String?): Intent { extras[name] = value; return this }
    fun putExtra(name: String, value: Int): Intent { extras[name] = value; return this }
    fun putExtra(name: String, value: Boolean): Intent { extras[name] = value; return this }
    fun putExtra(name: String, value: Long): Intent { extras[name] = value; return this }
    fun putExtra(name: String, value: Array<String>?): Intent { extras[name] = value; return this }
    fun putExtra(name: String, value: java.io.Serializable?): Intent { extras[name] = value; return this }

    fun getStringExtra(name: String): String? = extras[name] as? String
    fun getIntExtra(name: String, default: Int): Int = (extras[name] as? Int) ?: default
    fun getBooleanExtra(name: String, default: Boolean): Boolean = (extras[name] as? Boolean) ?: default
    fun getLongExtra(name: String, default: Long): Long = (extras[name] as? Long) ?: default

    fun addFlags(flags: Int): Intent = this

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_SENDTO = "android.intent.action.SENDTO"
        const val EXTRA_EMAIL = "android.intent.extra.EMAIL"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000

        @JvmStatic
        fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}

object Environment {
    const val DIRECTORY_MUSIC = "Music"
    const val DIRECTORY_DOWNLOADS = "Download"
}

open class ContextWrapper(base: Context? = null) : Context()
