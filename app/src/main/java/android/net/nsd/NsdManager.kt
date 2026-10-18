package android.net.nsd

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

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

/**
 * Desktop implementation of Android's [NsdManager] powered by **JmDNS (`javax.jmdns.JmDNS`)**
 * for standard RFC 6762 mDNS / DNS-SD (`_myplayersync._tcp.local.`) interoperability with Android
 * and Desktop peers, plus a concurrent **UDP LAN Broadcast & Multicast Beacon (`45199`)** fallback
 * so rooms are discovered instantly across Windows Wi-Fi / Ethernet subnets even when OS firewalls
 * filter port 5353 multicast traffic.
 */
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

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "MyPlayer-JmDNS-Worker").apply { isDaemon = true }
    }

    @Volatile private var jmdnsInstance: JmDNS? = null
    private val jmdnsLock = Any()

    private val registeredServices = ConcurrentHashMap<RegistrationListener, ServiceInfo>()
    private val activeBeaconFlag = ConcurrentHashMap<RegistrationListener, AtomicBoolean>()

    private val discoveryListeners = ConcurrentHashMap<DiscoveryListener, ServiceListener>()
    private val activeDiscoveryFlag = ConcurrentHashMap<DiscoveryListener, AtomicBoolean>()
    private val resolvedCache = ConcurrentHashMap<String, NsdServiceInfo>()

    private fun getOrCreateJmDNS(): JmDNS? {
        jmdnsInstance?.let { return it }
        synchronized(jmdnsLock) {
            jmdnsInstance?.let { return it }
            return try {
                val bindAddr = findBestLanInetAddress() ?: InetAddress.getLocalHost()
                val hostName = "myplayer-${bindAddr.hostAddress.replace('.', '-')}"
                JmDNS.create(bindAddr, hostName).also {
                    jmdnsInstance = it
                    println("[NsdManager/JmDNS] Initialized on ${bindAddr.hostAddress} ($hostName)")
                }
            } catch (e: Throwable) {
                println("[NsdManager/JmDNS] Warning creating JmDNS: ${e.message}")
                null
            }
        }
    }

    private fun toJmDnsType(rawType: String): String {
        val trimmed = rawType.trim().trimEnd('.')
        return if (trimmed.endsWith(".local")) "$trimmed." else "$trimmed.local."
    }

    fun registerService(serviceInfo: NsdServiceInfo, protocolType: Int, listener: RegistrationListener) {
        executor.execute {
            val localAddr = findBestLanInetAddress()
            serviceInfo.host = localAddr
            val jmType = toJmDnsType(serviceInfo.serviceType)

            var registeredWithJmDns = false
            try {
                val jmdns = getOrCreateJmDNS()
                if (jmdns != null) {
                    val jmService = ServiceInfo.create(
                        jmType,
                        serviceInfo.serviceName,
                        serviceInfo.port,
                        0,
                        0,
                        "MyPlayer Sync Play Room"
                    )
                    jmdns.registerService(jmService)
                    registeredServices[listener] = jmService
                    registeredWithJmDns = true
                }
            } catch (e: Throwable) {
                println("[NsdManager/JmDNS] JmDNS registerService fallback to UDP beacon: ${e.message}")
            }

            // Start concurrent UDP LAN Broadcast/Multicast Beacon so PC-to-PC and firewall-restricted subnets discover immediately
            val running = AtomicBoolean(true)
            activeBeaconFlag[listener] = running
            startUdpBroadcastBeacon(serviceInfo, localAddr, running)

            // Notify registration success (either JmDNS or UDP LAN beacon is live)
            if (registeredWithJmDns || localAddr != null) {
                listener.onServiceRegistered(serviceInfo)
            } else {
                listener.onRegistrationFailed(serviceInfo, FAILURE_INTERNAL_ERROR)
            }
        }
    }

    fun unregisterService(listener: RegistrationListener) {
        activeBeaconFlag.remove(listener)?.set(false)
        val jmService = registeredServices.remove(listener)
        executor.execute {
            try {
                if (jmService != null) {
                    jmdnsInstance?.unregisterService(jmService)
                }
                val info = NsdServiceInfo().apply {
                    serviceName = jmService?.name ?: ""
                    serviceType = jmService?.type ?: SYNC_DEFAULT_TYPE
                }
                listener.onServiceUnregistered(info)
            } catch (e: Throwable) {
                listener.onUnregistrationFailed(NsdServiceInfo(), FAILURE_INTERNAL_ERROR)
            }
        }
    }

    fun discoverServices(serviceType: String, protocolType: Int, listener: DiscoveryListener) {
        val jmType = toJmDnsType(serviceType)
        val running = AtomicBoolean(true)
        activeDiscoveryFlag[listener] = running

        executor.execute {
            listener.onDiscoveryStarted(serviceType)

            // 1. JmDNS ServiceListener for standard mDNS (_myplayersync._tcp.local.)
            try {
                val jmdns = getOrCreateJmDNS()
                if (jmdns != null) {
                    val jmListener = object : ServiceListener {
                        override fun serviceAdded(event: ServiceEvent) {
                            jmdns.requestServiceInfo(event.type, event.name, 1500L)
                        }

                        override fun serviceRemoved(event: ServiceEvent) {
                            val lost = NsdServiceInfo().apply {
                                serviceName = event.name
                                this.serviceType = serviceType
                            }
                            listener.onServiceLost(lost)
                        }

                        override fun serviceResolved(event: ServiceEvent) {
                            val info = event.info ?: return
                            val addr = info.inet4Addresses.firstOrNull()
                                ?: info.inetAddresses.firstOrNull()
                                ?: return
                            val nsdInfo = NsdServiceInfo().apply {
                                serviceName = event.name
                                this.serviceType = serviceType
                                port = info.port
                                host = addr
                            }
                            resolvedCache[event.name] = nsdInfo
                            listener.onServiceFound(nsdInfo)
                        }
                    }
                    discoveryListeners[listener] = jmListener
                    jmdns.addServiceListener(jmType, jmListener)

                    // Also query any already-cached services on the network
                    val existing = jmdns.list(jmType, 1500L)
                    for (svc in existing) {
                        val addr = svc.inet4Addresses.firstOrNull() ?: svc.inetAddresses.firstOrNull() ?: continue
                        val nsdInfo = NsdServiceInfo().apply {
                            serviceName = svc.name
                            this.serviceType = serviceType
                            port = svc.port
                            host = addr
                        }
                        resolvedCache[svc.name] = nsdInfo
                        listener.onServiceFound(nsdInfo)
                    }
                }
            } catch (e: Throwable) {
                println("[NsdManager/JmDNS] JmDNS discovery warning: ${e.message}")
            }

            // 2. Concurrent UDP LAN Broadcast & Multicast Listener
            startUdpDiscoveryListener(serviceType, listener, running)
        }
    }

    fun stopServiceDiscovery(listener: DiscoveryListener) {
        activeDiscoveryFlag.remove(listener)?.set(false)
        val jmListener = discoveryListeners.remove(listener)
        executor.execute {
            try {
                if (jmListener != null) {
                    jmdnsInstance?.removeServiceListener(toJmDnsType(SYNC_DEFAULT_TYPE), jmListener)
                }
            } catch (_: Throwable) {}
            listener.onDiscoveryStopped(SYNC_DEFAULT_TYPE)
        }
    }

    fun resolveService(serviceInfo: NsdServiceInfo, listener: ResolveListener) {
        executor.execute {
            try {
                // Fast path: already resolved via JmDNS serviceResolved or UDP LAN beacon
                if (serviceInfo.host != null && serviceInfo.port > 0) {
                    listener.onServiceResolved(serviceInfo)
                    return@execute
                }
                val cached = resolvedCache[serviceInfo.serviceName]
                if (cached != null && cached.host != null && cached.port > 0) {
                    listener.onServiceResolved(cached)
                    return@execute
                }

                // Query JmDNS directly
                val jmdns = getOrCreateJmDNS()
                val jmType = toJmDnsType(serviceInfo.serviceType.ifBlank { SYNC_DEFAULT_TYPE })
                val info = jmdns?.getServiceInfo(jmType, serviceInfo.serviceName, 2500L)
                val addr = info?.inet4Addresses?.firstOrNull() ?: info?.inetAddresses?.firstOrNull()
                if (info != null && addr != null && info.port > 0) {
                    val resolved = NsdServiceInfo().apply {
                        serviceName = serviceInfo.serviceName
                        serviceType = serviceInfo.serviceType
                        port = info.port
                        host = addr
                    }
                    resolvedCache[serviceInfo.serviceName] = resolved
                    listener.onServiceResolved(resolved)
                } else {
                    listener.onResolveFailed(serviceInfo, FAILURE_INTERNAL_ERROR)
                }
            } catch (_: Throwable) {
                listener.onResolveFailed(serviceInfo, FAILURE_INTERNAL_ERROR)
            }
        }
    }

    private fun startUdpBroadcastBeacon(
        serviceInfo: NsdServiceInfo,
        localAddr: InetAddress?,
        running: AtomicBoolean
    ) {
        executor.execute {
            val hostIp = localAddr?.hostAddress ?: return@execute
            val payload = "MYPLAYER_SYNC|${serviceInfo.serviceName}|$hostIp|${serviceInfo.port}"
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val multicastAddr = InetAddress.getByName(BEACON_MULTICAST_GROUP)

                while (running.get()) {
                    try {
                        socket.send(DatagramPacket(bytes, bytes.size, broadcastAddr, BEACON_PORT))
                        socket.send(DatagramPacket(bytes, bytes.size, multicastAddr, BEACON_PORT))
                    } catch (_: Throwable) {}
                    Thread.sleep(1200L)
                }
            } catch (_: Throwable) {
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun startUdpDiscoveryListener(
        serviceType: String,
        listener: DiscoveryListener,
        running: AtomicBoolean
    ) {
        executor.execute {
            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1500
                    bind(InetSocketAddress(BEACON_PORT))
                }
                try {
                    val group = InetAddress.getByName(BEACON_MULTICAST_GROUP)
                    socket.joinGroup(group)
                } catch (_: Throwable) {}

                val buf = ByteArray(512)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        if (msg.startsWith("MYPLAYER_SYNC|")) {
                            val parts = msg.split("|")
                            if (parts.size >= 4) {
                                val name = parts[1]
                                val ip = parts[2]
                                val port = parts[3].toIntOrNull() ?: continue
                                val addr = InetAddress.getByName(ip)
                                val info = NsdServiceInfo().apply {
                                    serviceName = name
                                    this.serviceType = serviceType
                                    this.port = port
                                    this.host = addr
                                }
                                resolvedCache[name] = info
                                listener.onServiceFound(info)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal timeout tick
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        const val PROTOCOL_DNS_SD = 0x0001
        const val FAILURE_INTERNAL_ERROR = 0
        private const val SYNC_DEFAULT_TYPE = "_myplayersync._tcp."
        private const val BEACON_MULTICAST_GROUP = "239.255.45.200"
        private const val BEACON_PORT = 45199

        /**
         * Selects the best non-loopback physical LAN IPv4 address (Wi-Fi or Ethernet),
         * ignoring virtual adapters such as WSL, Hyper-V, VirtualBox, VMware, and VPN tunnels.
         */
        fun findBestLanInetAddress(): InetAddress? {
            return try {
                val virtualKeywords = listOf("vethernet", "wsl", "hyper-v", "virtualbox", "vmware", "vbox", "docker", "loopback", "tap", "tun")
                val preferredKeywords = listOf("wi-fi", "wifi", "wlan", "wireless", "ethernet", "eth", "en0", "en1")
                val candidates = mutableListOf<Pair<Int, InetAddress>>()

                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                    val nameLower = (intf.name + " " + intf.displayName).lowercase()
                    val isVirtual = virtualKeywords.any { nameLower.contains(it) }
                    val isPreferred = preferredKeywords.any { nameLower.contains(it) }

                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                            val score = when {
                                isVirtual -> 1
                                isPreferred -> 10
                                else -> 5
                            }
                            candidates.add(score to addr)
                        }
                    }
                }
                candidates.maxByOrNull { it.first }?.second
            } catch (_: Throwable) {
                null
            }
        }
    }
}
