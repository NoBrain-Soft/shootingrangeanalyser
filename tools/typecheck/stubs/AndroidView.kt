@file:Suppress("unused", "UNUSED_PARAMETER", "ClassName")

package android.view

import android.content.Context
import android.content.stub

open class View(context: Context) {
    var keepScreenOn: Boolean = false
    var tag: Any? = null
}

open class ViewGroup(context: Context) : View(context) {
    class LayoutParams(width: Int, height: Int) {
        companion object {
            const val MATCH_PARENT: Int = -1
            const val WRAP_CONTENT: Int = -2
        }
    }
}

class Window {
    fun addFlags(flags: Int): Unit = stub()
    fun clearFlags(flags: Int): Unit = stub()
}

object WindowManager {
    object LayoutParams {
        const val FLAG_KEEP_SCREEN_ON: Int = 128
    }
}
