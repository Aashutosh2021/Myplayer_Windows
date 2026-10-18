package android.net.wifi

class WifiManager {
    fun createWifiLock(lockType: Int, tag: String): WifiLock = WifiLock()
    fun createWifiLock(tag: String): WifiLock = WifiLock()
    fun createMulticastLock(tag: String): MulticastLock = MulticastLock()

    class WifiLock {
        fun setReferenceCounted(b: Boolean) {}
        fun acquire() {}
        fun release() {}
        val isHeld: Boolean get() = false
        @JvmName("getIsHeldMethod")
        fun isHeld(): Boolean = false
    }

    class MulticastLock {
        fun setReferenceCounted(b: Boolean) {}
        fun acquire() {}
        fun release() {}
        val isHeld: Boolean get() = false
        @JvmName("getIsHeldMethod")
        fun isHeld(): Boolean = false
    }

    companion object {
        const val WIFI_MODE_FULL_HIGH_PERF = 3
        const val WIFI_MODE_FULL_LOW_LATENCY = 4
    }
}
