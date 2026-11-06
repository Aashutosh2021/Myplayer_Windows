package coil.request

class ImageRequest private constructor(
    val data: Any?,
    val memoryCacheKey: String? = null,
    val diskCacheKey: String? = null
) {
    class Builder(context: Any?) {
        private var data: Any? = null
        private var memoryKey: String? = null
        private var diskKey: String? = null
        fun data(data: Any?): Builder { this.data = data; return this }
        fun size(w: Int, h: Int): Builder = this
        fun precision(p: Any?): Builder = this
        fun allowHardware(b: Boolean): Builder = this
        fun crossfade(ms: Int): Builder = this
        fun crossfade(b: Boolean): Builder = this
        fun memoryCacheKey(k: String): Builder { this.memoryKey = k; return this }
        fun diskCacheKey(k: String): Builder { this.diskKey = k; return this }
        fun memoryCachePolicy(p: Any?): Builder = this
        fun diskCachePolicy(p: Any?): Builder = this
        fun build(): ImageRequest = ImageRequest(data, memoryKey, diskKey)
    }
}

enum class CachePolicy { ENABLED, DISABLED, READ_ONLY, WRITE_ONLY }

class Options(
    val size: coil.size.Size = coil.size.Size(coil.size.Dimension(256), coil.size.Dimension(256))
)
