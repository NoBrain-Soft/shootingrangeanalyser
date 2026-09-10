@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity

import android.app.Application
import android.content.Context
import android.content.stub
import android.view.Window
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner

open class ComponentActivity : Context(), LifecycleOwner, ViewModelStoreOwner {
    val window: Window get() = stub()
    open fun onCreate(savedInstanceState: android.os.Bundle?) {}
    val application: Application get() = stub()
}
