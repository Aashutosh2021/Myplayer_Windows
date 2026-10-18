package coil

import coil.fetch.Fetcher
import coil.request.Options
import java.io.File

interface ImageLoaderFactory {
    fun newImageLoader(): ImageLoader
}

class ImageLoader {
    val components = ComponentRegistry()

    class ComponentRegistry {
        fun newFetcher(data: Any, options: Options, imageLoader: ImageLoader): Pair<Fetcher, Any>? = null

        class Builder {
            fun add(factory: Any): Builder = this
        }
    }

    class Builder(context: Any?) {
        fun components(block: ComponentRegistry.Builder.() -> Unit): Builder = this
        fun memoryCache(block: () -> Any?): Builder = this
        fun diskCache(block: () -> Any?): Builder = this
        fun allowHardware(allow: Boolean): Builder = this
        fun respectCacheHeaders(respect: Boolean): Builder = this
        fun build(): ImageLoader = ImageLoader()
    }
}
