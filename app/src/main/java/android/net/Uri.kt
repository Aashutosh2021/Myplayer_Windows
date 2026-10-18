package android.net

import java.io.File
import java.net.URI

class Uri private constructor(private val uriString: String) {
    val scheme: String?
        get() = try { URI.create(uriString).scheme } catch (_: Exception) { null }

    val path: String?
        get() = try {
            val p = URI.create(uriString).path
            if (p.isNullOrEmpty() && !uriString.startsWith("http") && !uriString.startsWith("file")) uriString else p
        } catch (_: Exception) { uriString }

    val host: String?
        get() = try { URI.create(uriString).host } catch (_: Exception) { null }

    val authority: String?
        get() = try { URI.create(uriString).authority } catch (_: Exception) { null }

    val encodedPath: String?
        get() = path

    val lastPathSegment: String?
        get() = path?.trimEnd('/')?.substringAfterLast('/')

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

        @JvmStatic
        fun fromFile(file: File): Uri {
            return Uri(file.toURI().toString())
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
