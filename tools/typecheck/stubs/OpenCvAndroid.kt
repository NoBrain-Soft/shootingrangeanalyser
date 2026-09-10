@file:Suppress("unused", "UNUSED_PARAMETER")

// org.openpnp:opencv carries the desktop natives and the whole org.opencv.* Java API except
// this Android-only bridge class, which the AAR adds.

package org.opencv.android

import android.graphics.Bitmap
import org.opencv.core.Mat

object Utils {
    fun bitmapToMat(bitmap: Bitmap, mat: Mat, unPremultiplyAlpha: Boolean = false) {}
    fun matToBitmap(mat: Mat, bitmap: Bitmap, premultiplyAlpha: Boolean = false) {}
}

object OpenCVLoader {
    const val OPENCV_VERSION: String = "4.9.0"
    fun initLocal(): Boolean = true
    fun initDebug(): Boolean = true
}
