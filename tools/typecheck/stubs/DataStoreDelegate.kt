@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.datastore.preferences

import android.content.Context
import android.content.stub
import androidx.datastore.preferences.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlin.properties.ReadOnlyProperty

fun preferencesDataStore(
    name: String,
): ReadOnlyProperty<Context, DataStore<Preferences>> =
    ReadOnlyProperty { _, _ -> stub() }
