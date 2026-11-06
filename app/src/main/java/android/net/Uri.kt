package android.net

import java.io.File
import java.net.URI
import java.net.URLDecoder

class Uri private constructor(private val uriString: String) {
    val scheme: String?
        get() = try {
            when {
                uriString.startsWith("http://", ignoreCase = true) -> "http"
                uriString.startsWith("https://", ignoreCase = true) -> "https"
                uriString.startsWith("file:", ignoreCase = true) -> "file"
                uriString.startsWith("content://", ignoreCase = true) -> "content"
                uriString.startsWith("online://", ignoreCase = true) -> "online"
                uriString.matches(Regex("^[a-zA-Z]:[\\\\/].*")) -> "file"
                else -> URI.create(uriString).scheme
            }
        } catch (_: Exception) { null }

    val path: String?
        get() = normalizeToFilePath(uriString)

    val host: String?
        get() = try { URI.create(uriString).host } catch (_: Exception) { null }

    val authority: String?
        get() = try { URI.create(uriString).authority } catch (_: Exception) { null }

    val encodedPath: String?
        get() = path

    val lastPathSegment: String?
        get() = path?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')

    override fun toString(): String = uriString

    override fun equals(other: Any?): Boolean = other is Uri && other.uriString == uriString
    override fun hashCode(): Int = uriString.hashCode()

    fun buildUpon(): Builder = Builder(uriString)

    class Builder(private var current: String = "") {
        fun appendPath(pathSegment: String): Builder {
            current = if (current.endsWith("/")) "$current$pathSegment" else "$current/$pathSegment"
            return this
        }
        fun appendQueryParameter(key: String, value: String): Builder {
            val delim = if (current.contains("?")) "&" else "?"
            current = "$current$delim$key=$value"
            return this
        }
        fun build(): Uri = Uri(current)
    }

    companion object {
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun parse(uriString: String?): Uri {
            if (uriString.isNullOrBlank()) return EMPTY
            return Uri(uriString)
        }

        /**
         * On Desktop (Windows/Linux/macOS), preserve the clean native filesystem path
         * (`C:\Users\...\Song Name.m4a`) instead of converting via `file.toURI().toString()`
         * which produces broken `file:/C:/...%20...` strings on Windows.
         */
        @JvmStatic
        fun fromFile(file: File): Uri {
            return Uri(file.absolutePath)
        }

        @JvmStatic
        fun normalizeToFilePath(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("online://", ignoreCase = true)
            ) {
                return try { URI.create(raw).path ?: raw } catch (_: Exception) { raw }
            }
            if (raw.startsWith("file:", ignoreCase = true)) {
                try {
                    return File(URI(raw)).absolutePath
                } catch (_: Exception) {}
                val stripped = raw
                    .removePrefix("file:///")
                    .removePrefix("file://")
                    .removePrefix("file:/")
                    .removePrefix("file:")
                val decoded = try {
                    URLDecoder.decode(stripped, "UTF-8")
                } catch (_: Exception) {
                    stripped.replace("%20", " ")
                }
                val winNormalized = if (decoded.matches(Regex("^/[a-zA-Z]:/.*"))) {
                    decoded.substring(1)
                } else {
                    decoded
                }
                return File(winNormalized).path
            }
            return raw
        }

        @JvmStatic
        fun encode(s: String?): String {
            if (s == null) return ""
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8.name())
        }

        @JvmStatic
        fun decode(s: String?): String {
            if (s == null) return ""
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8.name())
        }
    }
}
