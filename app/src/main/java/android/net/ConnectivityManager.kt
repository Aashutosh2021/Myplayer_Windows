package android.net

class Network

class NetworkCapabilities {
    fun hasCapability(capability: Int): Boolean = true
    fun hasTransport(transport: Int): Boolean = true

    companion object {
        const val NET_CAPABILITY_INTERNET = 12
        const val NET_CAPABILITY_VALIDATED = 16
        const val TRANSPORT_WIFI = 1
        const val TRANSPORT_CELLULAR = 0
        const val TRANSPORT_ETHERNET = 3
    }
}

class ConnectivityManager {
    val activeNetwork: Network? get() = Network()
    fun getNetworkCapabilities(network: Network?): NetworkCapabilities? = NetworkCapabilities()
}
