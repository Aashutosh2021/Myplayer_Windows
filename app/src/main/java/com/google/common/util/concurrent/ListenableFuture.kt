package com.google.common.util.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.Future

interface ListenableFuture<V> : Future<V> {
    fun addListener(listener: Runnable, executor: Executor)
}

class ImmediateFuture<V>(private val value: V) : ListenableFuture<V> {
    override fun addListener(listener: Runnable, executor: Executor) {
        executor.execute(listener)
    }
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): V = value
    override fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): V = value
}

object MoreExecutors {
    @JvmStatic fun directExecutor(): Executor = Executor { it.run() }
}

object Futures {
    @JvmStatic fun <V> immediateFuture(value: V): ListenableFuture<V> = ImmediateFuture(value)
}
