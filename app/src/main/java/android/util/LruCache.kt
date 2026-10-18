package android.util

open class LruCache<K : Any, V : Any>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, V>?): Boolean {
            return size > this@LruCache.maxSize
        }
    }

    @Synchronized
    open fun get(key: K): V? = map[key]

    @Synchronized
    open fun put(key: K, value: V): V? = map.put(key, value)

    @Synchronized
    open fun remove(key: K): V? = map.remove(key)

    @Synchronized
    open fun evictAll() {
        map.clear()
    }

    @Synchronized
    open fun size(): Int = map.size

    @Synchronized
    open fun maxSize(): Int = maxSize

    @Synchronized
    open fun snapshot(): Map<K, V> = LinkedHashMap(map)
}
