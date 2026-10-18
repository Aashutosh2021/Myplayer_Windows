package coil.memory

class MemoryCache {
    class Builder(context: Any?) {
        fun maxSizePercent(percent: Double): Builder = this
        fun build(): MemoryCache = MemoryCache()
    }
}
