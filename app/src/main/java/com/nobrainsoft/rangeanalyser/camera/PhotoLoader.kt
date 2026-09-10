package com.nobrainsoft.rangeanalyser.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

/**
 * Reads a photograph the user picked, at a size the analysis can work with.
 *
 * Shared by photo analysis and the custom target editor, because every failure here is one neither
 * of them can prevent and both have to survive: a URI the other app has already revoked, a format
 * that will not decode, a hardware bitmap whose pixels OpenCV cannot address, or simply a picture
 * too large for the heap.
 */
object PhotoLoader {

    /** Longest edge kept. Detection resamples to a fixed pixels-per-millimetre anyway. */
    const val MAX_DIMENSION = 2400

    /**
     * Decodes [uri] to a bitmap no larger than [MAX_DIMENSION] on its long edge.
     *
     * Returns null when the picture cannot be read at all.
     */
    fun decode(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        // A failed bounds pass leaves these at zero, and the loop below would then never subsample -
        // handing a full-resolution phone photograph to the decoder and running the heap out.
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sample = 1
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // OpenCV needs a readable configuration; without this a modern gallery can hand back a
            // hardware bitmap whose pixels cannot be addressed at all.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /**
     * Loads [uri] as the greyscale matrix the analysis works in.
     *
     * Off the main thread, and every failure comes back as a message worth showing rather than an
     * exception. The caller owns the returned [Mat] and must release it.
     */
    suspend fun loadGreyscale(context: Context, uri: Uri): Result<Mat> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = decode(context, uri) ?: error("That picture could not be read.")
                val image = try {
                    ImageBridge.greyscaleOf(bitmap)
                } finally {
                    bitmap.recycle()
                }
                if (image.empty()) {
                    image.release()
                    error("That picture came through empty. Try taking it again.")
                }
                image
            }.onFailure { Log.w(TAG, "could not load a photograph", it) }
        }

    /** The reason to show the user for a failed load. */
    fun reasonFor(thrown: Throwable): String = when (thrown) {
        is OutOfMemoryError -> "That photograph is too large for this device to open."
        else -> thrown.message?.takeIf { it.isNotBlank() } ?: "That picture could not be read."
    }

    private const val TAG = "RangeAnalyser"
}
