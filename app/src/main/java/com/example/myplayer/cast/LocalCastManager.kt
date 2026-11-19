package com.example.myplayer.cast

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

data class CastSpeakerDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val avTransportControlUrl: String?,
    val protocol: String
)

/**
 * Zero-dependency Local HTTP CastServer + DLNA/UPnP SSDP Discovery for MyPlayer Windows Desktop.
 */
@Singleton
class LocalCastManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }

    private val _devices = MutableStateFlow<List<CastSpeakerDevice>>(emptyList())
    val devices: StateFlow<List<CastSpeakerDevice>> = _devices.asStateFlow()

    private val _activeDevice = MutableStateFlow<CastSpeakerDevice?>(null)
    val activeDevice: StateFlow<CastSpeakerDevice?> = _activeDevice.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    @Volatile
    private var currentSourceFile: File? = null
    @Volatile
    private var currentRemoteUrl: String? = null

    suspend fun discoverLanSpeakers() = withContext(Dispatchers.IO) {
        val found = mutableMapOf<String, CastSpeakerDevice>()
        val query = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)

        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2_500
                val packet = DatagramPacket(query, query.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT)
                socket.send(packet)

                val buf = ByteArray(4096)
                val endAt = System.currentTimeMillis() + 2_500L
                while (System.currentTimeMillis() < endAt) {
                    val respPacket = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(respPacket)
                        val headers = String(respPacket.data, 0, respPacket.length, Charsets.UTF_8)
                        val location = Regex("""(?i)^LOCATION:\s*(.+)$""", RegexOption.MULTILINE)
                            .find(headers)?.groupValues?.get(1)?.trim() ?: continue
                        parseDlnaDescription(location)?.let { dev ->
                            found[dev.id] = dev
                            _devices.value = found.values.toList()
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
    }

    private fun parseDlnaDescription(locationUrl: String): CastSpeakerDevice? {
        return try {
            okHttpClient.newCall(Request.Builder().url(locationUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val friendlyName = Regex("""<friendlyName>([^<]+)</friendlyName>""", RegexOption.IGNORE_CASE)
                    .find(xml)?.groupValues?.get(1)?.trim() ?: "LAN Smart Speaker"
                val controlPath = Regex(
                    """<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>[\s\S]*?<controlURL>([^<]+)</controlURL>""",
                    RegexOption.IGNORE_CASE
                ).find(xml)?.groupValues?.get(1)?.trim() ?: return null

                val baseUri = java.net.URI(locationUrl)
                val resolvedControl = baseUri.resolve(controlPath).toString()
                CastSpeakerDevice(
                    id = resolvedControl,
                    name = friendlyName,
                    host = baseUri.host,
                    port = if (baseUri.port > 0) baseUri.port else 80,
                    avTransportControlUrl = resolvedControl,
                    protocol = "DLNA"
                )
            }
        } catch (_: Exception) { null }
    }

    suspend fun castTrackToDevice(
        scope: CoroutineScope,
        device: CastSpeakerDevice,
        title: String,
        artist: String,
        localFile: File? = null,
        remoteStreamUrl: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        currentSourceFile = localFile
        currentRemoteUrl = remoteStreamUrl
        val port = ensureHttpServerRunning(scope)
        val lanIp = findLocalIpv4() ?: return@withContext false
        val streamUrl = "http://$lanIp:$port/stream.mp3"
        val controlUrl = device.avTransportControlUrl ?: return@withContext false

        val soapSetUri = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>$streamUrl</CurrentURI>
                  <CurrentURIMetaData></CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val soapPlay = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <Speed>1</Speed>
                </u:Play>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val setOk = sendSoapAction(controlUrl, "SetAVTransportURI", soapSetUri)
        val playOk = if (setOk) sendSoapAction(controlUrl, "Play", soapPlay) else false
        if (playOk) _activeDevice.value = device
        playOk
    }

    private fun sendSoapAction(controlUrl: String, action: String, xmlBody: String): Boolean {
        val req = Request.Builder()
            .url(controlUrl)
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .post(xmlBody.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            okHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun ensureHttpServerRunning(scope: CoroutineScope): Int {
        serverSocket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val ss = ServerSocket(0)
        serverSocket = ss
        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive && !ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) { handleHttpClient(client) }
            }
        }
        return ss.localPort
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val reqLine = reader.readLine() ?: return
            var rangeStart = 0L
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeStart = Regex("""bytes=(\d+)-""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }
            }

            val out = s.getOutputStream()
            val file = currentSourceFile
            if (file != null && file.exists()) {
                val total = file.length()
                val rem = (total - rangeStart).coerceAtLeast(0L)
                val headers = buildString {
                    append(if (rangeStart > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: audio/mpeg\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: $rem\r\n")
                    if (rangeStart > 0) append("Content-Range: bytes $rangeStart-${total - 1}/$total\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.UTF_8))
                if (!reqLine.startsWith("HEAD")) {
                    FileInputStream(file).use { fis ->
                        if (rangeStart > 0) fis.skip(rangeStart)
                        fis.copyTo(out, 16 * 1024)
                    }
                }
            } else {
                val remote = currentRemoteUrl ?: return
                val reqBuilder = Request.Builder().url(remote)
                if (rangeStart > 0) reqBuilder.header("Range", "bytes=$rangeStart-")
                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val mime = resp.header("Content-Type") ?: "audio/mp4"
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nConnection: close\r\n\r\n".toByteArray())
                    resp.body?.byteStream()?.copyTo(out, 16 * 1024)
                }
            }
            out.flush()
        }
    }

    private fun findLocalIpv4(): String? {
        return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }
}
