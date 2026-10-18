package com.google.android.gms.tasks

class Task<T>(private val result: T) {
    fun addOnSuccessListener(listener: (T) -> Unit): Task<T> {
        listener(result)
        return this
    }
    fun addOnFailureListener(listener: (Exception) -> Unit): Task<T> = this
}

object Tasks {
    @JvmStatic
    fun <T> forResult(result: T): Task<T> = Task(result)
}
