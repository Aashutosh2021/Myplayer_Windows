package coil.fetch

import coil.decode.DataSource

interface FetchResult

class DrawableResult(
    val drawable: Any?,
    val isSampled: Boolean,
    val dataSource: DataSource
) : FetchResult

interface Fetcher {
    suspend fun fetch(): FetchResult?

    fun interface Factory<T : Any> {
        fun create(data: T, options: coil.request.Options, imageLoader: coil.ImageLoader): Fetcher?
    }
}
