@file:Suppress("unused", "UNUSED_PARAMETER")

package android.os

class Bundle {
    fun getString(key: String): String? = null
    fun getString(key: String, defaultValue: String): String = defaultValue
    fun getInt(key: String, defaultValue: Int = 0): Int = defaultValue
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean = defaultValue
}
