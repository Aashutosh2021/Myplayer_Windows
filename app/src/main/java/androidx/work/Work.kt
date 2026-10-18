package androidx.work

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import java.util.concurrent.TimeUnit

class Data(val map: MutableMap<String, Any?> = mutableMapOf()) {
    fun getInt(key: String, default: Int): Int = (map[key] as? Number)?.toInt() ?: default
    fun getString(key: String): String? = map[key] as? String
    fun getLong(key: String, default: Long): Long = (map[key] as? Number)?.toLong() ?: default
    fun getFloat(key: String, default: Float): Float = (map[key] as? Number)?.toFloat() ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = (map[key] as? Boolean) ?: default
    val keyValueMap: Map<String, Any?> get() = map

    class Builder {
        private val data = Data()
        fun putInt(key: String, value: Int): Builder { data.map[key] = value; return this }
        fun putString(key: String, value: String?): Builder { data.map[key] = value; return this }
        fun putLong(key: String, value: Long): Builder { data.map[key] = value; return this }
        fun putFloat(key: String, value: Float): Builder { data.map[key] = value; return this }
        fun putBoolean(key: String, value: Boolean): Builder { data.map[key] = value; return this }
        fun putAll(other: Data): Builder { data.map.putAll(other.map); return this }
        fun build(): Data = data
    }
}

fun workDataOf(vararg pairs: Pair<String, Any?>): Data {
    val data = Data()
    pairs.forEach { (k, v) -> data.map[k] = v }
    return data
}

class WorkerParameters(
    val id: UUID = UUID.randomUUID(),
    val inputData: Data = Data(),
    val runAttemptCount: Int = 0
)

class ForegroundInfo(
    val id: Int,
    val notification: Any?,
    val foregroundServiceType: Int = 0
) {
    constructor(id: Int, notification: Any?) : this(id, notification, 0)
}

abstract class ListenableWorker(val applicationContext: Context, val workerParams: WorkerParameters) {
    val id: UUID get() = workerParams.id
    val inputData: Data get() = workerParams.inputData
    val runAttemptCount: Int get() = workerParams.runAttemptCount
    val isStopped: Boolean get() = false

    sealed class Result {
        class Success(val outputData: Data = Data()) : Result()
        class Failure(val outputData: Data = Data()) : Result()
        class Retry : Result()

        companion object {
            @JvmStatic fun success(): Result = Success()
            @JvmStatic fun success(outputData: Data): Result = Success(outputData)
            @JvmStatic fun failure(): Result = Failure()
            @JvmStatic fun failure(outputData: Data): Result = Failure(outputData)
            @JvmStatic fun retry(): Result = Retry()
        }
    }
}

abstract class CoroutineWorker(appContext: Context, params: WorkerParameters) : ListenableWorker(appContext, params) {
    abstract suspend fun doWork(): Result
    open suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(0, null)
    suspend fun setForeground(info: ForegroundInfo) {}
    suspend fun setProgress(data: Data) {}
}

class WorkInfo(
    val id: UUID,
    val state: State,
    val tags: Set<String> = emptySet(),
    val progress: Data = Data()
) {
    enum class State {
        ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED;
        val isFinished: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
    }
}

class Constraints {
    class Builder {
        fun setRequiredNetworkType(type: Any?): Builder = this
        fun setRequiresBatteryNotLow(b: Boolean): Builder = this
        fun build(): Constraints = Constraints()
    }
}

enum class NetworkType { CONNECTED, UNMETERED }
enum class ExistingWorkPolicy { REPLACE, KEEP, APPEND, APPEND_OR_REPLACE }
enum class ExistingPeriodicWorkPolicy { REPLACE, KEEP, UPDATE, CANCEL_AND_REENQUEUE }

open class WorkRequest {
    open val id: UUID = UUID.randomUUID()
}

class OneTimeWorkRequest : WorkRequest() {
    class Builder(val workerClass: Class<out ListenableWorker>) {
        fun setConstraints(c: Constraints): Builder = this
        fun setInputData(d: Data): Builder = this
        fun addTag(tag: String): Builder = this
        fun setExpedited(policy: Any?): Builder = this
        fun build(): OneTimeWorkRequest = OneTimeWorkRequest()
    }
}

inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequest.Builder {
    return OneTimeWorkRequest.Builder(W::class.java)
}

class PeriodicWorkRequest : WorkRequest() {
    class Builder(
        val workerClass: Class<out ListenableWorker>,
        val repeatInterval: Long,
        val repeatIntervalTimeUnit: TimeUnit
    ) {
        fun setConstraints(c: Constraints): Builder = this
        fun setInputData(d: Data): Builder = this
        fun addTag(tag: String): Builder = this
        fun build(): PeriodicWorkRequest = PeriodicWorkRequest()
    }
}

inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(
    repeatInterval: Long,
    repeatIntervalTimeUnit: TimeUnit
): PeriodicWorkRequest.Builder {
    return PeriodicWorkRequest.Builder(W::class.java, repeatInterval, repeatIntervalTimeUnit)
}

enum class OutOfQuotaPolicy { RUN_AS_NON_EXPEDITED_WORK_REQUEST }

class WorkManager private constructor() {
    companion object {
        private val instance = WorkManager()
        @JvmStatic fun getInstance(context: Context): WorkManager = instance
    }

    fun enqueue(request: WorkRequest) {}
    fun enqueueUniqueWork(uniqueWorkName: String, existingWorkPolicy: ExistingWorkPolicy, work: OneTimeWorkRequest) {}
    fun enqueueUniquePeriodicWork(uniqueWorkName: String, existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy, work: PeriodicWorkRequest) {}
    fun getWorkInfosByTagFlow(tag: String): Flow<List<WorkInfo>> = flowOf(emptyList())
    fun getWorkInfosForUniqueWork(uniqueWorkName: String): com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>> =
        com.google.common.util.concurrent.ImmediateFuture(emptyList())
    fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>> = flowOf(emptyList())
    fun cancelAllWorkByTag(tag: String) {}
    fun cancelUniqueWork(uniqueWorkName: String) {}
}

open class Configuration {
    interface Provider {
        val workManagerConfiguration: Configuration
    }

    class Builder {
        fun setWorkerFactory(workerFactory: Any?): Builder = this
        fun setMinimumLoggingLevel(level: Int): Builder = this
        fun build(): Configuration = Configuration()
    }
}

abstract class WorkerFactory
