package androidx.paging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope

class PagingConfig(val pageSize: Int = 20, val enablePlaceholders: Boolean = false)

abstract class PagingSource<Key : Any, Value : Any> {
    abstract suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value>
    open fun getRefreshKey(state: PagingState<Key, Value>): Key? = null

    sealed class LoadParams<Key : Any>(val key: Key?, val loadSize: Int) {
        class Refresh<Key : Any>(key: Key?, loadSize: Int) : LoadParams<Key>(key, loadSize)
        class Append<Key : Any>(key: Key, loadSize: Int) : LoadParams<Key>(key, loadSize)
    }

    sealed class LoadResult<Key : Any, Value : Any> {
        data class Page<Key : Any, Value : Any>(
            val data: List<Value>,
            val prevKey: Key?,
            val nextKey: Key?
        ) : LoadResult<Key, Value>()

        data class Error<Key : Any, Value : Any>(val throwable: Throwable) : LoadResult<Key, Value>()
    }
}

class PagingState<Key : Any, Value : Any>

class PagingData<T : Any>(val items: List<T>) {
    companion object {
        fun <T : Any> empty(): PagingData<T> = PagingData(emptyList())
        fun <T : Any> from(data: List<T>): PagingData<T> = PagingData(data)
    }
}

class Pager<Key : Any, Value : Any>(
    val config: PagingConfig,
    val pagingSourceFactory: () -> PagingSource<Key, Value>
) {
    val flow: Flow<PagingData<Value>> = flow {
        try {
            val source = pagingSourceFactory()
            val result = source.load(PagingSource.LoadParams.Refresh(null, config.pageSize))
            if (result is PagingSource.LoadResult.Page) {
                emit(PagingData(result.data))
            } else {
                emit(PagingData(emptyList()))
            }
        } catch (_: Exception) {
            emit(PagingData(emptyList()))
        }
    }
}

fun <T : Any> Flow<PagingData<T>>.cachedIn(scope: CoroutineScope): Flow<PagingData<T>> = this

sealed class LoadState {
    object Loading : LoadState()
    class NotLoading(val endOfPaginationReached: Boolean) : LoadState()
    class Error(val error: Throwable) : LoadState()
}

data class CombinedLoadStates(
    val refresh: LoadState = LoadState.NotLoading(false),
    val prepend: LoadState = LoadState.NotLoading(false),
    val append: LoadState = LoadState.NotLoading(false),
    val source: CombinedLoadStates? = null
)
