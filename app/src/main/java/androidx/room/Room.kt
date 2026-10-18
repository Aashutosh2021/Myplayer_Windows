package androidx.room

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Dao

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Database(
    val entities: Array<KClass<*>>,
    val version: Int,
    val exportSchema: Boolean = true
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Entity(
    val tableName: String = "",
    val primaryKeys: Array<String> = [],
    val indices: Array<Index> = []
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Index(
    val value: Array<String> = [],
    val unique: Boolean = false
)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class PrimaryKey(val autoGenerate: Boolean = false)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class ColumnInfo(val name: String = "", val defaultValue: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Query(val value: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Insert(val onConflict: Int = OnConflictStrategy.ABORT)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Update(val onConflict: Int = OnConflictStrategy.ABORT)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Delete

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Transaction

object OnConflictStrategy {
    const val NONE = 0
    const val REPLACE = 1
    const val ABORT = 2
    const val FAIL = 3
    const val IGNORE = 4
    const val ROLLBACK = 5
}

abstract class RoomDatabase {
    open fun clearAllTables() {}
    open fun close() {}
}

suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = block()

object Room {
    fun <T : RoomDatabase> databaseBuilder(
        context: android.content.Context,
        klass: Class<T>,
        name: String
    ): Builder<T> = Builder(klass)

    class Builder<T : RoomDatabase>(private val klass: Class<T>) {
        fun addMigrations(vararg migrations: Any): Builder<T> = this
        fun fallbackToDestructiveMigration(): Builder<T> = this
        @Suppress("UNCHECKED_CAST")
        fun build(): T {
            databaseFactory?.let { return it(klass) as T }
            return try {
                klass.getDeclaredConstructor().newInstance()
            } catch (e: Exception) {
                try {
                    val implClass = Class.forName("com.example.myplayer.data.local.DesktopAppDatabase")
                    implClass.getDeclaredConstructor().newInstance() as T
                } catch (_: Exception) {
                    throw IllegalStateException("Cannot instantiate Room database for ${klass.name}")
                }
            }
        }
    }

    var databaseFactory: ((Class<*>) -> RoomDatabase)? = null
}
