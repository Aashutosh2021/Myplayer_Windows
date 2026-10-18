package androidx.media3.datasource.cache

import java.io.File

class SimpleCache(val cacheDir: File, val evictor: Any)

class LeastRecentlyUsedCacheEvictor(val maxBytes: Long)

class CacheDataSource {
    class Factory {
        fun setCache(cache: Any): Factory = this
        fun setUpstreamDataSourceFactory(factory: Any): Factory = this
        fun setFlags(flags: Int): Factory = this
    }

    companion object {
        const val FLAG_IGNORE_CACHE_ON_ERROR = 1
    }
}
