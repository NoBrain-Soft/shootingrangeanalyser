@file:Suppress("unused", "UNUSED_PARAMETER")

package android.media

import android.content.stub
import java.io.InputStream

class ExifInterface(stream: InputStream) {
    fun getAttributeInt(tag: String, defaultValue: Int): Int = stub()

    companion object {
        const val TAG_ORIENTATION: String = "Orientation"
        const val ORIENTATION_NORMAL: Int = 1
        const val ORIENTATION_ROTATE_90: Int = 6
        const val ORIENTATION_ROTATE_180: Int = 3
        const val ORIENTATION_ROTATE_270: Int = 8
    }
}
