package android.content.pm

class ApplicationInfo {
    var sourceDir: String = ""
    var flags: Int = 0

    companion object {
        const val FLAG_DEBUGGABLE = 2
    }
}

class InstallSourceInfo {
    val installingPackageName: String? = "com.android.vending"
}

class ServiceInfo {
    companion object {
        const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1
        const val FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 2
    }
}

class PackageManager {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo = PackageInfo()
    fun getInstallerPackageName(packageName: String): String? = "com.android.vending"
    fun getInstallSourceInfo(packageName: String): InstallSourceInfo = InstallSourceInfo()
    fun getPackagesForUid(uid: Int): Array<String>? = arrayOf("com.example.myplayer")
    fun checkPermission(permName: String, pkgName: String): Int = PERMISSION_GRANTED
    fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo = ApplicationInfo()

    companion object {
        const val GET_SIGNING_CERTIFICATES = 0x08000000
        const val GET_SIGNATURES = 0x00000040
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
    }

    class NameNotFoundException(msg: String? = null) : Exception(msg)
}

class PackageInfo {
    var versionName: String? = "1.0.0"
    var versionCode: Long = 1L
    var signingInfo: SigningInfo? = SigningInfo()
    var signatures: Array<Signature>? = arrayOf(Signature(byteArrayOf(1, 2, 3)))
}

class SigningInfo {
    fun hasMultipleSigners(): Boolean = false
    fun getSigningCertificateHistory(): Array<Signature> = arrayOf(Signature(byteArrayOf(1, 2, 3)))
    val apkContentsSigners: Array<Signature> = arrayOf(Signature(byteArrayOf(1, 2, 3)))
}

class Signature(private val bytes: ByteArray) {
    fun toByteArray(): ByteArray = bytes
}
