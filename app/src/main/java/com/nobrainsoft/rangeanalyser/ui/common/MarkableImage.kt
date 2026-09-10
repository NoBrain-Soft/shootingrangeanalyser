package com.nobrainsoft.rangeanalyser.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.math.hypot
import kotlin.math.min

/** A point the user placed on the image, in image pixel coordinates. */
data class ImageMark(val x: Double, val y: Double)

/**
 * An image the user taps to place points on, reporting positions in **image** coordinates.
 *
 * Everything asked for here - two ends of a reference length, the four corners of a sheet, the edge
 * of a scoring ring - is a position on the photograph, and the maths downstream works in image
 * pixels. Doing the conversion once here means no screen has to think about letterboxing, zoom or
 * pan.
 *
 * Pinch zooms and drag pans, because placing a mark on a ring line is a job measured in single
 * pixels and the whole sheet does not fit on a phone at anything like that precision. Once
 * [maximumMarks] are down a tap moves the nearest one rather than being ignored: getting the first
 * of two edges slightly wrong should not mean starting the step again.
 */
@Composable
fun MarkableImage(
    image: ImageBitmap,
    marks: List<ImageMark>,
    onMark: (ImageMark) -> Unit,
    modifier: Modifier = Modifier,
    markLabels: List<String> = emptyList(),
    maximumMarks: Int = Int.MAX_VALUE,
    /**
     * Moves the mark at an index. Without it, taps beyond [maximumMarks] do nothing, which is what
     * the calibration wizard's fixed four-corner step wants.
     */
    onMoveMark: ((index: Int, mark: ImageMark) -> Unit)? = null,
    connectMarks: Boolean = false,
    overlay: (DrawScope.(scale: Float, origin: Offset) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()

    var zoom by remember(image) { mutableFloatStateOf(1f) }
    var pan by remember(image) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val view = View(canvasSize, image.width, image.height, zoom, pan)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(image) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    pan += panChange
                }
            }
            .pointerInput(image, marks, maximumMarks, onMoveMark) {
                detectTapGestures { tap ->
                    val current = View(size, image.width, image.height, zoom, pan)
                    val mark = current.toImage(tap) ?: return@detectTapGestures

                    // Near an existing mark means "fix that one", which is nearly always what a
                    // second tap in the same place is for.
                    val nearest = marks.indices.minByOrNull { index ->
                        current.distanceOnScreen(marks[index], tap)
                    }
                    val grabbing = nearest != null && onMoveMark != null &&
                        current.distanceOnScreen(marks[nearest], tap) <= GRAB_RADIUS_PX

                    when {
                        grabbing -> onMoveMark(nearest, mark)
                        marks.size < maximumMarks -> onMark(mark)
                        // Full, and the tap was not near anything: treat it as correcting whichever
                        // mark is closest rather than silently doing nothing.
                        nearest != null && onMoveMark != null -> onMoveMark(nearest, mark)
                    }
                }
            },
    ) {
        canvasSize = IntSize(size.width.toInt(), size.height.toInt())
        val fit = View(canvasSize, image.width, image.height, zoom, pan)

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
            drawCircle(MARK_COLOUR, radius = 9f, center = at, style = Stroke(width = 3f))
            drawLine(MARK_COLOUR, Offset(at.x - 18f, at.y), Offset(at.x + 18f, at.y), 2f)
            drawLine(MARK_COLOUR, Offset(at.x, at.y - 18f), Offset(at.x, at.y + 18f), 2f)

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
        if (marks.size >= 2 && (connectMarks || maximumMarks == 2)) {
            drawLine(MARK_COLOUR, fit.toCanvas(marks[0]), fit.toCanvas(marks[1]), strokeWidth = 3f)
        }
    }
}

private val MARK_COLOUR = Color(0xFFFF6B35)

/** How close a tap has to land to count as grabbing an existing mark rather than placing one. */
private const val GRAB_RADIUS_PX = 60f

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f

/**
 * Where the image sits inside the canvas, and the coordinate conversion that goes with it.
 *
 * Letterbox fit first, then the user's zoom and pan on top, so image coordinates survive both.
 */
private class View(
    canvas: IntSize,
    imageWidth: Int,
    imageHeight: Int,
    zoom: Float,
    pan: Offset,
) {
    private val baseScale: Float = if (imageWidth <= 0 || imageHeight <= 0 || canvas.width <= 0) {
        1f
    } else {
        min(
            canvas.width.toFloat() / imageWidth,
            canvas.height.toFloat() / imageHeight,
        )
    }

    val scale: Float = baseScale * zoom

    val origin = Offset(
        (canvas.width - imageWidth * scale) / 2f + pan.x,
        (canvas.height - imageHeight * scale) / 2f + pan.y,
    )

    private val width = imageWidth
    private val height = imageHeight

    fun toImage(tap: Offset): ImageMark? {
        if (scale <= 0f) return null
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

    fun distanceOnScreen(mark: ImageMark, tap: Offset): Float {
        val at = toCanvas(mark)
        return hypot(at.x - tap.x, at.y - tap.y)
    }
}
