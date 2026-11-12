package com.example.myplayer.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Windows Desktop Spotify-Style 9-Second Chunk Streaming & Local File Loopback Server.
 *
 * 1. Online Streams (`/stream?id=...&url=...`):
 *    Fetches audio from the upstream CDN in **160 KB (~9-second) HTTP Range chunks** (`Range: bytes=start-end`)
 *    with 4x exponential-backoff retries, disk caching (`~/.myplayer/cache/audio_chunks/<songKey>/chunk_<idx>.bin`),
 *    and a rolling 3-chunk prefetch window (~27s).
 *
 * 2. Downloaded / Local Files (`/local?id=...`):
 *    Serves local `.m4a` / `.mp3` / `.webm` files over `http://127.0.0.1:<port>/local?id=<id>` directly from
 *    disk via [RandomAccessFile] with full HTTP `206 Partial Content` byte-range support!
 *    Why this fixes Downloaded Music on Windows 100%:
 *    - Works **100% offline** (uses internal `127.0.0.1` loopback, zero internet required).
 *    - Completely bypasses libVLC's Windows native file-path bugs (`file:/C:/...`, `%20` URL-encoded spaces,
 *      special Unicode/Hindi/Emoji characters in song titles, and JNA Windows ANSI `Cp1252` path encoding issues).
 */
object DesktopChunkStreamProxy {
    private const val TAG = "DesktopChunkStreamProxy"
    const val CHUNK_SIZE_BYTES = 160 * 1024L // 160 KB (~9 seconds at 128-140 kbps)
    private const val ROLLING_PREFETCH_CHUNKS = 2 // Prefetch next 2 chunks (total 3 chunks = ~27s rolling window)
    private const val MAX_CHUNK_RETRIES = 4
    private const val USER_AGENT =
        "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip"

    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val cacheRootDir: File by lazy {
        File(Context.DEFAULT.cacheDir, "audio_chunks").apply { mkdirs() }
    }

    private val urlToSongKey = ConcurrentHashMap<String, String>()
    private val totalLengthCache = ConcurrentHashMap<String, Long>()
    private val contentTypeCache = ConcurrentHashMap<String, String>()
    private val inFlightChunks = ConcurrentHashMap<String, Any>()
    private val localFileRegistry = ConcurrentHashMap<String, File>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = -1

    @Synchronized
    private fun ensureServerStarted(): Int {
        if (boundPort > 0 && serverSocket?.isClosed == false) return boundPort
        return try {
            val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            boundPort = socket.localPort
            proxyScope.launch {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        proxyScope.launch { handleClient(client) }
                    } catch (_: Throwable) {
                        if (socket.isClosed) break
                    }
                }
            }
            Log.i(TAG, "9-Second Chunk & Local Audio Proxy listening on 127.0.0.1:$boundPort")
            boundPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local chunk stream proxy: ${e.message}")
            -1
        }
    }

    fun registerSongStream(songId: String, streamUrl: String) {
        val cleanKey = sanitizeKey(songId)
        if (cleanKey.isNotBlank() && streamUrl.startsWith("http")) {
            urlToSongKey[streamUrl] = cleanKey
        }
    }

    /**
     * Wraps a local downloaded audio [File] on Windows into a pure-ASCII localhost HTTP URL
     * (`http://127.0.0.1:<port>/local?id=<id>`) so libVLC streams it directly from disk
     * with full HTTP Range seeking and zero Windows path / `%20` / Unicode encoding errors.
     */
    fun wrapLocalFile(file: File): String {
        val port = ensureServerStarted()
        if (port <= 0) return file.absolutePath
        val fileId = "local_${file.absolutePath.hashCode().toUInt().toString(16)}"
        localFileRegistry[fileId] = file
        return "http://127.0.0.1:$port/local?id=$fileId"
    }

    /**
     * If [url] is a localhost proxy URL wrapping an upstream URL, extracts the original upstream URL.
     */
    fun unwrapUpstreamUrlIfProxy(url: String?): String? {
        if (url.isNullOrBlank()) return url
        if (!url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:")) {
            return url
        }
        val query = url.substringAfter("?", "")
        val params = parseQueryParams(query)
        return params["url"] ?: url
    }

    /**
     * Wraps an external `http://` or `https://` audio stream URL into our local loopback
     * 9-second chunk proxy URL. Local files and already-wrapped URLs are returned unchanged.
     */
    fun wrapUrlIfNeeded(pathOrUrl: String, songIdHint: String? = null): String {
        if (!pathOrUrl.startsWith("http://") && !pathOrUrl.startsWith("https://")) {
            return pathOrUrl
        }
        if (pathOrUrl.startsWith("http://127.0.0.1:") || pathOrUrl.startsWith("http://localhost:")) {
            return pathOrUrl
        }
        val port = ensureServerStarted()
        if (port <= 0) return pathOrUrl

        val key = sanitizeKey(
            songIdHint?.takeIf { it.isNotBlank() }
                ?: urlToSongKey[pathOrUrl]
                ?: extractVideoIdOrHash(pathOrUrl)
        )
        urlToSongKey[pathOrUrl] = key
        val encodedUrl = URLEncoder.encode(pathOrUrl, "UTF-8")
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        return "http://127.0.0.1:$port/stream?id=$encodedKey&url=$encodedUrl"
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 35_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val pathAndQuery = parts[1]

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val query = pathAndQuery.substringAfter("?", "")
            val params = parseQueryParams(query)

            // Route 1: Local / Downloaded File Streaming (`/local?id=...`)
            if (pathAndQuery.startsWith("/local")) {
                handleLocalFileRequest(client, method, params, rangeHeader)
                return
            }

            // Route 2: Online 9-Second Chunk Streaming (`/stream?id=...&url=...`)
            val upstreamUrl = params["url"] ?: return
            val songKey = sanitizeKey(params["id"] ?: extractVideoIdOrHash(upstreamUrl))

            // Ensure metadata (total size & content-type) is known by fetching chunk 0 if needed
            val songDir = File(cacheRootDir, songKey).apply { mkdirs() }
            loadCachedMeta(songKey, songDir)

            if (getOrFetchChunk(songKey, upstreamUrl, songDir, 0L) == null && totalLengthCache[songKey] == null) {
                writeErrorResponse(client.getOutputStream(), 502, "Bad Gateway")
                return
            }

            val totalLength = totalLengthCache[songKey] ?: -1L
            val contentType = contentTypeCache[songKey] ?: "audio/mp4"

            var startOffset = 0L
            var endOffset = if (totalLength > 0) totalLength - 1 else Long.MAX_VALUE
            val isPartial = !rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")

            if (isPartial && rangeHeader != null) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                val dashIdx = rangeSpec.indexOf('-')
                if (dashIdx >= 0) {
                    startOffset = rangeSpec.substring(0, dashIdx).trim().toLongOrNull() ?: 0L
                    val endStr = rangeSpec.substring(dashIdx + 1).trim()
                    if (endStr.isNotEmpty()) {
                        endOffset = endStr.toLongOrNull() ?: endOffset
                    }
                }
            }
            if (totalLength > 0) {
                startOffset = startOffset.coerceIn(0L, (totalLength - 1).coerceAtLeast(0L))
                endOffset = endOffset.coerceIn(startOffset, totalLength - 1)
            }

            val out = client.getOutputStream()
            if (isPartial && totalLength > 0) {
                val contentLength = (endOffset - startOffset + 1).coerceAtLeast(0L)
                val headers = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Range: bytes $startOffset-$endOffset/$totalLength\r\n")
                    append("Content-Length: $contentLength\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.UTF_8))
            } else {
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (totalLength > 0) {
                        append("Content-Length: $totalLength\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.UTF_8))
            }
            out.flush()

            if (method == "HEAD") return

            // Stream 9-second chunks sequentially and trigger rolling prefetch for next chunks!
            var cursor = startOffset
            while (cursor <= endOffset) {
                val chunkIndex = cursor / CHUNK_SIZE_BYTES
                val chunkOffset = (cursor % CHUNK_SIZE_BYTES).toInt()

                // Trigger rolling background prefetch for the next 2 chunks (~18s ahead)
                for (ahead in 1..ROLLING_PREFETCH_CHUNKS) {
                    val nextIdx = chunkIndex + ahead
                    val nextStart = nextIdx * CHUNK_SIZE_BYTES
                    val knownTotal = totalLengthCache[songKey] ?: -1L
                    if (knownTotal <= 0L || nextStart < knownTotal) {
                        proxyScope.launch {
                            getOrFetchChunk(songKey, upstreamUrl, songDir, nextIdx)
                        }
                    }
                }

                val chunkBytes = getOrFetchChunk(songKey, upstreamUrl, songDir, chunkIndex)
                    ?: break
                if (chunkBytes.isEmpty() || chunkOffset >= chunkBytes.size) break

                val maxToSend = (endOffset - cursor + 1).coerceAtMost((chunkBytes.size - chunkOffset).toLong()).toInt()
                if (maxToSend <= 0) break

                try {
                    out.write(chunkBytes, chunkOffset, maxToSend)
                    out.flush()
                } catch (_: IOException) {
                    // Client (VLC) closed connection or seeked to a new range
                    break
                }

                cursor += maxToSend
                val updatedTotal = totalLengthCache[songKey] ?: -1L
                if (updatedTotal > 0L && cursor >= updatedTotal) break
                if (chunkBytes.size < CHUNK_SIZE_BYTES.toInt()) break
            }
        }
    }

    /**
     * Streams a local downloaded audio file from disk with full HTTP Range (`206 Partial Content`)
     * support so libVLC plays downloaded songs reliably on Windows regardless of spaces, backslashes,
     * or Unicode characters in the file path.
     */
    private fun handleLocalFileRequest(
        client: Socket,
        method: String,
        params: Map<String, String>,
        rangeHeader: String?
    ) {
        val out = client.getOutputStream()
        val fileId = params["id"]
        val localFile = if (fileId != null) localFileRegistry[fileId] else null

        if (localFile == null || !localFile.exists() || !localFile.isFile || localFile.length() <= 0L) {
            writeErrorResponse(out, 404, "Not Found")
            return
        }

        val totalLength = localFile.length()
        val ext = localFile.extension.lowercase()
        val contentType = when (ext) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> "audio/webm"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            else -> "audio/mp4"
        }

        var startOffset = 0L
        var endOffset = totalLength - 1L
        val isPartial = !rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")

        if (isPartial && rangeHeader != null) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = rangeSpec.indexOf('-')
            if (dashIdx >= 0) {
                startOffset = rangeSpec.substring(0, dashIdx).trim().toLongOrNull() ?: 0L
                val endStr = rangeSpec.substring(dashIdx + 1).trim()
                if (endStr.isNotEmpty()) {
                    endOffset = endStr.toLongOrNull() ?: endOffset
                }
            }
        }

        startOffset = startOffset.coerceIn(0L, (totalLength - 1L).coerceAtLeast(0L))
        endOffset = endOffset.coerceIn(startOffset, totalLength - 1L)
        val contentLength = (endOffset - startOffset + 1L).coerceAtLeast(0L)

        val headers = if (isPartial) {
            buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: $contentType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Range: bytes $startOffset-$endOffset/$totalLength\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Connection: close\r\n\r\n")
            }
        } else {
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $contentType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $totalLength\r\n")
                append("Connection: close\r\n\r\n")
            }
        }

        out.write(headers.toByteArray(Charsets.UTF_8))
        out.flush()

        if (method == "HEAD") return

        RandomAccessFile(localFile, "r").use { raf ->
            raf.seek(startOffset)
            val buffer = ByteArray(64 * 1024)
            var remaining = contentLength
            while (remaining > 0L) {
                val toRead = min(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                try {
                    out.write(buffer, 0, read)
                } catch (_: IOException) {
                    break
                }
                remaining -= read
            }
            try {
                out.flush()
            } catch (_: IOException) {}
        }
    }

    /**
     * Returns the 160 KB (~9-second) chunk at [chunkIndex], loading from disk cache if available
     * or downloading from the upstream CDN with 4x exponential backoff retries.
     */
    private suspend fun getOrFetchChunk(
        songKey: String,
        upstreamUrl: String,
        songDir: File,
        chunkIndex: Long
    ): ByteArray? {
        val chunkFile = File(songDir, "chunk_$chunkIndex.bin")
        if (chunkFile.exists() && chunkFile.length() > 0L) {
            return runCatching { chunkFile.readBytes() }.getOrNull()
        }

        val knownTotal = totalLengthCache[songKey] ?: -1L
        val startByte = chunkIndex * CHUNK_SIZE_BYTES
        if (knownTotal > 0L && startByte >= knownTotal) return ByteArray(0)

        val lockKey = "$songKey:$chunkIndex"
        val lock = inFlightChunks.computeIfAbsent(lockKey) { Any() }

        return try {
            // Re-check disk cache if another coroutine just finished downloading this chunk
            if (chunkFile.exists() && chunkFile.length() > 0L) {
                return chunkFile.readBytes()
            }

            val endByte = if (knownTotal > 0L) {
                min(startByte + CHUNK_SIZE_BYTES - 1L, knownTotal - 1L)
            } else {
                startByte + CHUNK_SIZE_BYTES - 1L
            }

            var attempt = 0
            val backoffDelays = longArrayOf(350L, 700L, 1400L, 2500L)
            while (attempt < MAX_CHUNK_RETRIES) {
                try {
                    val req = Request.Builder()
                        .url(upstreamUrl)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Range", "bytes=$startByte-$endByte")
                        .build()

                    httpClient.newCall(req).execute().use { response ->
                        if (response.code == 416) {
                            return ByteArray(0)
                        }
                        if (!response.isSuccessful && response.code != 206) {
                            throw IOException("HTTP ${response.code}")
                        }

                        val cType = response.header("Content-Type")
                        if (!cType.isNullOrBlank()) {
                            contentTypeCache[songKey] = cType
                        }

                        val contentRange = response.header("Content-Range")
                        if (!contentRange.isNullOrBlank()) {
                            val totalStr = contentRange.substringAfterLast("/").trim()
                            val parsedTotal = totalStr.toLongOrNull()
                            if (parsedTotal != null && parsedTotal > 0L) {
                                totalLengthCache[songKey] = parsedTotal
                                saveCachedMeta(songKey, songDir)
                            }
                        } else if (chunkIndex == 0L && response.code == 200) {
                            val fullLen = response.body?.contentLength() ?: -1L
                            if (fullLen > 0L) {
                                totalLengthCache[songKey] = fullLen
                                saveCachedMeta(songKey, songDir)
                            }
                        }

                        val bytes = response.body?.bytes() ?: throw IOException("Empty chunk body")
                        if (bytes.isNotEmpty()) {
                            synchronized(lock) {
                                val tmpFile = File(songDir, "chunk_${chunkIndex}_${System.nanoTime()}.tmp")
                                tmpFile.writeBytes(bytes)
                                if (!tmpFile.renameTo(chunkFile)) {
                                    if (chunkFile.exists() && chunkFile.length() > 0) {
                                        tmpFile.delete()
                                        return chunkFile.readBytes()
                                    }
                                    tmpFile.copyTo(chunkFile, overwrite = true)
                                    tmpFile.delete()
                                }
                            }
                        }
                        return bytes
                    }
                } catch (e: Exception) {
                    attempt++
                    Log.w(TAG, "Chunk $chunkIndex for $songKey failed (attempt $attempt/$MAX_CHUNK_RETRIES): ${e.message}")
                    if (attempt < MAX_CHUNK_RETRIES) {
                        delay(backoffDelays[(attempt - 1).coerceAtMost(backoffDelays.lastIndex)])
                    }
                }
            }
            null
        } finally {
            inFlightChunks.remove(lockKey)
        }
    }

    private fun loadCachedMeta(songKey: String, songDir: File) {
        if (totalLengthCache.containsKey(songKey)) return
        val metaFile = File(songDir, "meta.properties")
        if (!metaFile.exists()) return
        try {
            val props = Properties()
            metaFile.inputStream().use { props.load(it) }
            props.getProperty("totalLength")?.toLongOrNull()?.let {
                if (it > 0L) totalLengthCache[songKey] = it
            }
            props.getProperty("contentType")?.takeIf { it.isNotBlank() }?.let {
                contentTypeCache[songKey] = it
            }
        } catch (_: Exception) {}
    }

    private fun saveCachedMeta(songKey: String, songDir: File) {
        try {
            val props = Properties()
            totalLengthCache[songKey]?.let { props.setProperty("totalLength", it.toString()) }
            contentTypeCache[songKey]?.let { props.setProperty("contentType", it) }
            File(songDir, "meta.properties").outputStream().use { props.store(it, null) }
        } catch (_: Exception) {}
    }

    private fun writeErrorResponse(out: OutputStream, code: Int, message: String) {
        try {
            val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {}
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                k to v
            } else null
        }.toMap()
    }

    private fun extractVideoIdOrHash(url: String): String {
        val idMatch = Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
        return idMatch?.take(24) ?: "stream_${url.hashCode().toUInt().toString(16)}"
    }

    private fun sanitizeKey(raw: String): String {
        return raw.removePrefix("online://")
            .removePrefix("spotify:track:")
            .removePrefix("spotify:")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(48)
            .ifBlank { "default_track" }
    }
}
