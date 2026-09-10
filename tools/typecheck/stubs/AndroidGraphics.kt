@file:Suppress("unused", "UNUSED_PARAMETER")

package android.graphics

import android.content.stub
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class Bitmap {
    val width: Int get() = stub()
    val height: Int get() = stub()

    /** Nullable in the real API: a hardware bitmap reports no configuration at all. */
    val config: Config? get() = stub()
    fun copy(config: Config, isMutable: Boolean): Bitmap? = stub()
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean = stub()
    fun recycle(): Unit = stub()

    enum class Config { ARGB_8888, RGB_565 }

    enum class CompressFormat { PNG, JPEG, WEBP }

    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = stub()
        fun createBitmap(
            source: Bitmap,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            m: Matrix?,
            filter: Boolean,
        ): Bitmap = stub()
    }
}

object BitmapFactory {
    fun decodeStream(stream: InputStream?): Bitmap? = stub()
    fun decodeStream(stream: InputStream?, outPadding: Rect?, options: Options?): Bitmap? = stub()
    fun decodeFile(path: String): Bitmap? = stub()
    fun decodeFile(path: String, options: Options?): Bitmap? = stub()

    class Options {
        var inJustDecodeBounds: Boolean = false
        var inSampleSize: Int = 1
        var inPreferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
        var outWidth: Int = 0
        var outHeight: Int = 0
        var outMimeType: String? = null
    }
}

class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0)

class Matrix {
    fun postRotate(degrees: Float): Boolean = stub()
    fun postScale(sx: Float, sy: Float): Boolean = stub()
}
