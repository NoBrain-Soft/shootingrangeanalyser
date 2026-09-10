@file:Suppress("unused", "UNUSED_PARAMETER")

package android.app

import android.content.Context

open class Application : Context() {
    open fun onCreate() {}
}
