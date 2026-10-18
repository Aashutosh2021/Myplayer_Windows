package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath
import java.io.File
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

fun preferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> {
    return object : ReadOnlyProperty<Context, DataStore<Preferences>> {
        private var instance: DataStore<Preferences>? = null

        override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<Preferences> {
            return instance ?: synchronized(this) {
                instance ?: PreferenceDataStoreFactory.createWithPath(
                    produceFile = {
                        val dir = File(System.getProperty("user.home"), ".myplayer/datastore")
                        dir.mkdirs()
                        File(dir, "$name.preferences_pb").absolutePath.toPath()
                    }
                ).also { instance = it }
            }
        }
    }
}
