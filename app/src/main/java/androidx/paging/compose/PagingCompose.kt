package androidx.paging.compose

import androidx.compose.runtime.*
import androidx.paging.*
import kotlinx.coroutines.flow.Flow

class ItemSnapshotList<T>(val items: List<T>) : List<T> by items

class LazyPagingItems<T : Any>(private val pagingData: PagingData<T>) {
    val itemCount: Int get() = pagingData.items.size
    operator fun get(index: Int): T? = pagingData.items.getOrNull(index)
    fun peek(index: Int): T? = pagingData.items.getOrNull(index)

    val itemSnapshotList: ItemSnapshotList<T> get() = ItemSnapshotList(pagingData.items)
    val loadState: CombinedLoadStates = CombinedLoadStates()
    fun retry() {}
    fun refresh() {}
}

fun <T : Any> LazyPagingItems<T>.itemKey(key: (T) -> Any): (Int) -> Any = { index -> get(index)?.let(key) ?: index }

@Composable
fun <T : Any> Flow<PagingData<T>>.collectAsLazyPagingItems(): LazyPagingItems<T> {
    val pagingData by this.collectAsState(initial = PagingData.empty())
    return remember(pagingData) { LazyPagingItems(pagingData) }
}
