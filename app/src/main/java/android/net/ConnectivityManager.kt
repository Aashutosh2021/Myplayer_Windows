package android.net

import java.net.NetworkInterface

class Network

class NetworkCapabilities(private val isOnline: Boolean = true) {
    fun hasCapability(capability: Int): Boolean = when (capability) {
        NET_CAPABILITY_INTERNET, NET_CAPABILITY_VALIDATED -> isOnline
        else -> true
    }

    fun hasTransport(transport: Int): Boolean = when (transport) {
        TRANSPORT_WIFI, TRANSPORT_ETHERNET -> isOnline
        else -> false
    }

    companion object {
        const val NET_CAPABILITY_INTERNET = 12
        const val NET_CAPABILITY_VALIDATED = 16
        const val TRANSPORT_WIFI = 1
        const val TRANSPORT_CELLULAR = 0
        const val TRANSPORT_ETHERNET = 3
    }
}

class ConnectivityManager {
    private fun hasActiveNetworkInterface(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isUp && !nif.isLoopback && !nif.isVirtual) {
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            return true
                        }
                    }
                }
            }
            false
        } catch (_: Exception) {
            true
        }
    }

    val activeNetwork: Network?
        get() = if (hasActiveNetworkInterface()) Network() else null

    fun getNetworkCapabilities(network: Network?): NetworkCapabilities? {
        if (network == null) return null
        return NetworkCapabilities(hasActiveNetworkInterface())
    }
}
