@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = stub()
    fun openOutputStream(uri: Uri): OutputStream? = stub()
}
