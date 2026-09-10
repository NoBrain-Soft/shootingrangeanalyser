@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import android.net.Uri
import java.io.File

open class Context {
    val packageName: String get() = stub()
    val applicationContext: Context get() = stub()
    val filesDir: File get() = stub()
    val cacheDir: File get() = stub()
    val contentResolver: ContentResolver get() = stub()
    fun startActivity(intent: Intent): Unit = stub()
    fun getSystemService(name: String): Any? = stub()
    fun getString(id: Int): String = stub()
}

class Intent(action: String? = null) {
    var type: String? = null
    fun putExtra(name: String, value: Uri): Intent = stub()
    fun putExtra(name: String, value: String): Intent = stub()
    fun addFlags(flags: Int): Intent = stub()

    companion object {
        const val ACTION_SEND: String = "android.intent.action.SEND"
        const val EXTRA_STREAM: String = "android.intent.extra.STREAM"
        const val EXTRA_TEXT: String = "android.intent.extra.TEXT"
        const val FLAG_GRANT_READ_URI_PERMISSION: Int = 1
        fun createChooser(target: Intent, title: CharSequence?): Intent = stub()
    }
}

internal fun <T> stub(): T = throw UnsupportedOperationException("type-check stub")
