package com.nobrainsoft.rangeanalyser.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The only place in the app that knows about both Android imaging and OpenCV.
 *
 * `:vision` is deliberately kept free of `android.*` so its algorithms can be tested on a desktop
 * JVM; everything Android-specific about images lives here instead.
 */
object ImageBridge {

    /**
     * Extracts the luminance plane from a camera frame.
     *
     * Camera frames arrive as YUV, whose first plane *is* a greyscale image - so live analysis reads
     * it directly rather than converting to RGB and back. That saves a full-frame colour conversion
     * on every frame, which is most of the budget at 30 fps.
     */
    fun lumaOf(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val mat = Mat(height, width, CvType.CV_8UC1)

        // The hardware pads rows out to an alignment boundary, so the stride is often wider than
        // the image. Copying the buffer wholesale without accounting for that produces a picture
        // that shears progressively down the frame.
        val rowStride = plane.rowStride
        if (rowStride == width) {
            val bytes = ByteArray(buffer.remaining())
            buffer.rewind()
            buffer.get(bytes)
            mat.put(0, 0, bytes)
        } else {
            val row = ByteArray(width)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, width)
                mat.put(y, 0, row)
            }
        }
        buffer.rewind()
        return mat
    }

    /**
     * Converts a decoded photograph to the greyscale matrix everything downstream expects.
     *
     * [Utils.bitmapToMat] only accepts ARGB_8888 and RGB_565 and throws on anything else, and a
     * picture that arrives from another app can be neither - a hardware bitmap from a modern
     * gallery is the common case. Copying into a known configuration first costs one allocation and
     * removes a crash that the user cannot do anything about.
     */
    fun greyscaleOf(bitmap: Bitmap): Mat {
        require(bitmap.width > 0 && bitmap.height > 0) { "the photograph decoded to nothing" }

        val usable = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalArgumentException("this photograph could not be read")
        }

        val colour = Mat()
        try {
            Utils.bitmapToMat(usable, colour)
            val grey = Mat()
            Imgproc.cvtColor(colour, grey, Imgproc.COLOR_RGBA2GRAY)
            return grey
        } finally {
            colour.release()
            if (usable !== bitmap) usable.recycle()
        }
    }

    fun toBitmap(mat: Mat): Bitmap {
        require(!mat.empty()) { "there is no image to show" }
        val rgba = Mat()
        when (mat.channels()) {
            1 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_RGB2RGBA)
            else -> mat.copyTo(rgba)
        }
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    fun toImageBitmap(mat: Mat): ImageBitmap = toBitmap(mat).asImageBitmap()
}

/**
 * Feeds camera frames to a callback as greyscale matrices.
 *
 * Frames arrive on a background executor and the [Mat] is released as soon as [onLuma] returns, so
 * the callback must not hold on to it. Anything worth keeping should be cloned.
 */
class LumaAnalyzer(
    private val onLuma: (luma: Mat, timestampMs: Long, rotationDegrees: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        image.use { frame ->
            val luma = ImageBridge.lumaOf(frame)
            try {
                // The rotation travels with the frame because the overlay has to undo it: the
                // analyser sees the sensor's own orientation, the preview shows it upright.
                onLuma(
                    luma,
                    frame.imageInfo.timestamp / 1_000_000L,
                    frame.imageInfo.rotationDegrees,
                )
            } finally {
                luma.release()
            }
        }
    }
}
