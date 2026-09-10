@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

object Log {
    fun v(tag: String, msg: String, tr: Throwable? = null): Int = 0
    fun d(tag: String, msg: String, tr: Throwable? = null): Int = 0
    fun i(tag: String, msg: String, tr: Throwable? = null): Int = 0
    fun w(tag: String, msg: String, tr: Throwable? = null): Int = 0
    fun e(tag: String, msg: String, tr: Throwable? = null): Int = 0
}

class Size(val width: Int, val height: Int)
