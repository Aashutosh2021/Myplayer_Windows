package android.net.nsd

import java.net.InetAddress

class NsdServiceInfo {
    var serviceName: String = ""
    var serviceType: String = ""
    var port: Int = 0
        @JvmName("setPortProperty") set
    var host: InetAddress? = null

    fun setPort(p: Int) {
        port = p
    }
}

class NsdManager {
    interface RegistrationListener {
        fun onServiceRegistered(serviceInfo: NsdServiceInfo)
        fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onServiceUnregistered(serviceInfo: NsdServiceInfo)
        fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
    }

    interface DiscoveryListener {
        fun onDiscoveryStarted(regType: String)
        fun onDiscoveryStopped(serviceType: String)
        fun onServiceFound(serviceInfo: NsdServiceInfo)
        fun onServiceLost(serviceInfo: NsdServiceInfo)
        fun onStartDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)
    }

    interface ResolveListener {
        fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onServiceResolved(serviceInfo: NsdServiceInfo)
    }

    fun registerService(serviceInfo: NsdServiceInfo, protocolType: Int, listener: RegistrationListener) {}
    fun unregisterService(listener: RegistrationListener) {}
    fun discoverServices(serviceType: String, protocolType: Int, listener: DiscoveryListener) {}
    fun stopServiceDiscovery(listener: DiscoveryListener) {}
    fun resolveService(serviceInfo: NsdServiceInfo, listener: ResolveListener) {}

    companion object {
        const val PROTOCOL_DNS_SD = 0x0001
    }
}
