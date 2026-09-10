@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.datastore.preferences.core

import android.content.stub
import kotlinx.coroutines.flow.Flow

class Preferences {
    class Key<T>(val name: String)

    operator fun <T> get(key: Key<T>): T? = stub()
    fun <T> contains(key: Key<T>): Boolean = stub()
}

class MutablePreferences {
    operator fun <T> get(key: Preferences.Key<T>): T? = stub()
    operator fun <T> set(key: Preferences.Key<T>, value: T): Unit = stub()
    fun <T> remove(key: Preferences.Key<T>): T = stub()
    fun clear(): Unit = stub()
}

interface DataStore<T> {
    val data: Flow<T>
}

suspend fun DataStore<Preferences>.edit(
    transform: suspend (MutablePreferences) -> Unit,
): Preferences = stub()

fun stringPreferencesKey(name: String): Preferences.Key<String> = Preferences.Key(name)
fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = Preferences.Key(name)
fun intPreferencesKey(name: String): Preferences.Key<Int> = Preferences.Key(name)
fun floatPreferencesKey(name: String): Preferences.Key<Float> = Preferences.Key(name)
fun doublePreferencesKey(name: String): Preferences.Key<Double> = Preferences.Key(name)
fun longPreferencesKey(name: String): Preferences.Key<Long> = Preferences.Key(name)
