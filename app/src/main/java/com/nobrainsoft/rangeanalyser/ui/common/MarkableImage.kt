package com.nobrainsoft.rangeanalyser.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** A point the user placed on the image, in image pixel coordinates. */
data class ImageMark(val x: Double, val y: Double)

/**
 * An image the user taps to place points on, reporting positions in **image** coordinates.
 *
 * Everything the calibration wizard asks for - two ends of a reference length, the four corners of a
 * sheet - is a position on the photograph, and the maths downstream works in image pixels. Doing
 * the conversion here once means no screen has to think about letterboxing or scale.
 */
@Composable
fun MarkableImage(
    image: ImageBitmap,
    marks: List<ImageMark>,
    onMark: (ImageMark) -> Unit,
    modifier: Modifier = Modifier,
    markLabels: List<String> = emptyList(),
    maximumMarks: Int = Int.MAX_VALUE,
    overlay: (DrawScope.(scale: Float, origin: Offset) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(image, maximumMarks) {
                detectTapGestures { tap ->
                    val fit = Fit(size, image.width, image.height)
                    val mark = fit.toImage(tap) ?: return@detectTapGestures
                    if (marks.size < maximumMarks) onMark(mark)
                }
            },
    ) {
        val fit = Fit(
            IntSize(size.width.toInt(), size.height.toInt()),
            image.width,
            image.height,
        )

        drawImage(
            image = image,
            dstOffset = IntOffset(fit.origin.x.toInt(), fit.origin.y.toInt()),
            dstSize = IntSize(
                (image.width * fit.scale).toInt(),
                (image.height * fit.scale).toInt(),
            ),
        )

        overlay?.invoke(this, fit.scale, fit.origin)

        // Marks are drawn as a crosshair rather than a filled dot: a dot hides the very thing the
        // user is trying to line up with.
        marks.forEachIndexed { index, mark ->
            val at = fit.toCanvas(mark)
            drawCircle(Color(0xFFFF6B35), radius = 9f, center = at, style = Stroke(width = 3f))
            drawLine(Color(0xFFFF6B35), Offset(at.x - 18f, at.y), Offset(at.x + 18f, at.y), 2f)
            drawLine(Color(0xFFFF6B35), Offset(at.x, at.y - 18f), Offset(at.x, at.y + 18f), 2f)

            markLabels.getOrNull(index)?.let { label ->
                val layout = textMeasurer.measure(
                    label,
                    TextStyle(fontSize = 13.sp, color = Color.White),
                )
                translate(at.x + 14f, at.y - 30f) {
                    drawText(layout, topLeft = Offset.Zero)
                }
            }
        }

        // A line between the first two marks, which is what a reference measurement looks like.
        if (marks.size >= 2 && maximumMarks == 2) {
            drawLine(
                Color(0xFFFF6B35),
                fit.toCanvas(marks[0]),
                fit.toCanvas(marks[1]),
                strokeWidth = 3f,
            )
        }
    }
}

/** Letterbox fit of an image inside a canvas, and the coordinate conversion that goes with it. */
private class Fit(canvas: IntSize, imageWidth: Int, imageHeight: Int) {
    val scale: Float = min(
        canvas.width.toFloat() / imageWidth,
        canvas.height.toFloat() / imageHeight,
    )

    val origin = Offset(
        (canvas.width - imageWidth * scale) / 2f,
        (canvas.height - imageHeight * scale) / 2f,
    )

    private val width = imageWidth
    private val height = imageHeight

    fun toImage(tap: Offset): ImageMark? {
        val x = (tap.x - origin.x) / scale
        val y = (tap.y - origin.y) / scale
        // A tap in the letterbox margin is not on the picture and must not be treated as one.
        if (x < 0 || y < 0 || x > width || y > height) return null
        return ImageMark(x.toDouble(), y.toDouble())
    }

    fun toCanvas(mark: ImageMark) = Offset(
        origin.x + mark.x.toFloat() * scale,
        origin.y + mark.y.toFloat() * scale,
    )
}
