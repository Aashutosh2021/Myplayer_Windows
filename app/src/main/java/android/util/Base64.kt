package android.util

import java.util.Base64 as JBase64

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val encoder = if ((flags and URL_SAFE) != 0) JBase64.getUrlEncoder() else JBase64.getEncoder()
        val result = encoder.encodeToString(input)
        return if ((flags and NO_PADDING) != 0) result.trimEnd('=') else result
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray {
        val encoder = if ((flags and URL_SAFE) != 0) JBase64.getUrlEncoder() else JBase64.getEncoder()
        return encoder.encode(input)
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val decoder = if ((flags and URL_SAFE) != 0) JBase64.getUrlDecoder() else JBase64.getDecoder()
        return decoder.decode(str.trim())
    }

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray {
        val decoder = if ((flags and URL_SAFE) != 0) JBase64.getUrlDecoder() else JBase64.getDecoder()
        return decoder.decode(input)
    }
}
