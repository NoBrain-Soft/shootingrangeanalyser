package com.nobrainsoft.rangeanalyser.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.nobrainsoft.rangeanalyser.core.stats.Trend
import com.nobrainsoft.rangeanalyser.core.stats.TrendDirection
import java.util.Locale
import kotlin.math.abs

/**
 * Colours for series that stand for different things - two sessions being compared, two loads.
 *
 * Assigned in this fixed order and never cycled. Checked for colour-vision separation against both
 * light and dark backgrounds rather than picked by eye; the worst adjacent pair is well clear of the
 * confusion threshold under deuteranopia, protanopia and tritanopia, and every colour clears 3:1
 * contrast on both surfaces.
 */
object SeriesColours {
    val blue = Color(0xFF2E7DD1)
    val amber = Color(0xFFC2670A)
    val purple = Color(0xFF7B4FD1)
    val green = Color(0xFF2E9E5B)

    private val order = listOf(blue, amber, purple, green)

    /**
     * The colour for series [index].
     *
     * Beyond the fourth series, colour stops being a usable code - so the caller is expected to
     * stop rather than invent a fifth hue. This wraps only so a stray index cannot crash a screen.
     */
    fun at(index: Int): Color = order[index % order.size]

    val maximumDistinguishable = order.size
}

/**
 * A metric plotted across range trips.
 *
 * One measure per chart. Two measures on two y-axes is the commonest way to make a chart that looks
 * informative and cannot be read, and there is no version of it here.
 *
 * The fitted trend line is drawn only when the data supports a direction. A line through three
 * noisy points will always slope somewhere, and drawing it invites the shooter to believe in a
 * change the numbers do not show.
 */
@Composable
fun TrendChart(
    trend: Trend,
    valueLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    lowerIsBetter: Boolean = true,
) {
    val textMeasurer = rememberTextMeasurer()
    var selected by remember(trend) { mutableStateOf<Int?>(null) }

    val axis = Color(0xFF9AA5B1)
    val ink = Color(0xFF5A6472)

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(trend) {
                    // Tap to inspect: a phone has no hover, and a value on every point would be
                    // unreadable.
                    detectTapGestures { tap ->
                        // `size` here is the pointer-input IntSize, not the DrawScope's Size.
                        val plot = Plot(size.width.toFloat(), size.height.toFloat(), trend)
                        selected = plot.nearestIndex(tap.x)
                    }
                },
        ) {
            val plot = Plot(size.width, size.height, trend)

            // Recessive gridlines: three is enough to read a level from, more is clutter.
            repeat(3) { step ->
                val y = plot.top + plot.height * step / 2f
                drawLine(
                    color = axis.copy(alpha = 0.25f),
                    start = Offset(plot.left, y),
                    end = Offset(plot.right, y),
                    strokeWidth = 1f,
                )
            }

            plot.drawBand(this, trend, lowerIsBetter)
            plot.drawSeries(this, trend)
            plot.drawLabels(this, trend, textMeasurer, valueLabel, ink, selected)
        }
    }
}

private class Plot(canvasWidth: Float, canvasHeight: Float, trend: Trend) {
    val left = 8f
    val right = canvasWidth - 8f
    val top = 16f
    val bottom = canvasHeight - 28f
    val width = right - left
    val height = bottom - top

    private val values = trend.points.map { it.value }
    private val minimum: Double
    private val maximum: Double

    init {
        val low = values.minOrNull() ?: 0.0
        val high = values.maxOrNull() ?: 1.0
        // A little headroom so markers are never clipped against the frame.
        val pad = ((high - low) * 0.15).takeIf { it > 0.0 } ?: (high * 0.1 + 1.0)
        minimum = low - pad
        maximum = high + pad
    }

    fun x(index: Int, count: Int): Float =
        if (count <= 1) left + width / 2f else left + width * index / (count - 1).toFloat()

    fun y(value: Double): Float {
        val span = (maximum - minimum).takeIf { abs(it) > 1e-9 } ?: 1.0
        return bottom - (height * ((value - minimum) / span)).toFloat()
    }

    fun nearestIndex(tapX: Float): Int? {
        if (values.isEmpty()) return null
        return values.indices.minByOrNull { abs(x(it, values.size) - tapX) }
    }

    /** The uncertainty band around the fit, drawn behind everything. */
    fun drawBand(scope: DrawScope, trend: Trend, lowerIsBetter: Boolean) {
        val interval = trend.slopeCi ?: return
        if (trend.points.size < 2) return

        val count = trend.points.size
        val colour = when (trend.direction) {
            TrendDirection.IMPROVING -> SeriesColours.green
            TrendDirection.WORSENING -> SeriesColours.amber
            TrendDirection.NO_CHANGE_DETECTED -> SeriesColours.blue
        }

        val upper = Path()
        val lower = Path()
        for (index in 0 until count) {
            val base = trend.intercept
            val high = base + interval.upper * index
            val low = base + interval.lower * index
            val pointX = x(index, count)
            if (index == 0) {
                upper.moveTo(pointX, y(high))
                lower.moveTo(pointX, y(low))
            } else {
                upper.lineTo(pointX, y(high))
                lower.lineTo(pointX, y(low))
            }
        }

        // Closing the two edges into one shape gives the band; drawn faintly so the data wins.
        for (index in count - 1 downTo 0) {
            upper.lineTo(x(index, count), y(trend.intercept + interval.lower * index))
        }
        upper.close()
        scope.drawPath(upper, colour.copy(alpha = 0.12f))

        // The fitted line itself is only shown when the band says there is a real direction.
        if (trend.direction != TrendDirection.NO_CHANGE_DETECTED) {
            scope.drawLine(
                color = colour.copy(alpha = 0.7f),
                start = Offset(x(0, count), y(trend.fittedValueAt(0))),
                end = Offset(x(count - 1, count), y(trend.fittedValueAt(count - 1))),
                strokeWidth = 2f,
            )
        }
    }

    fun drawSeries(scope: DrawScope, trend: Trend) {
        val count = trend.points.size
        val path = Path()
        trend.points.forEachIndexed { index, point ->
            val pointX = x(index, count)
            val pointY = y(point.value)
            if (index == 0) path.moveTo(pointX, pointY) else path.lineTo(pointX, pointY)
        }
        scope.drawPath(path, SeriesColours.blue, style = Stroke(width = 2f))

        trend.points.forEachIndexed { index, point ->
            val centre = Offset(x(index, count), y(point.value))
            // A ring of surface colour around each marker keeps overlapping points readable.
            scope.drawCircle(Color.White, radius = 6f, center = centre)
            scope.drawCircle(SeriesColours.blue, radius = 4.5f, center = centre)
        }
    }

    /**
     * Labels the first and last points, plus whichever one the user tapped.
     *
     * A number on every point is noise; the ends give the change and the tap gives the detail.
     */
    fun drawLabels(
        scope: DrawScope,
        trend: Trend,
        textMeasurer: TextMeasurer,
        valueLabel: (Double) -> String,
        ink: Color,
        selected: Int?,
    ) {
        val count = trend.points.size
        val toLabel = listOfNotNull(0, count - 1, selected).distinct().filter { it in 0 until count }

        for (index in toLabel) {
            val point = trend.points[index]
            val layout = textMeasurer.measure(
                valueLabel(point.value),
                TextStyle(fontSize = 11.sp, color = ink),
            )
            val centre = Offset(x(index, count), y(point.value))
            val textX = (centre.x - layout.size.width / 2f).coerceIn(left, right - layout.size.width)
            scope.drawText(layout, topLeft = Offset(textX, centre.y - layout.size.height - 10f))
        }

        // Session count along the bottom, as the axis.
        val axisLabel = textMeasurer.measure(
            "${count} sessions",
            TextStyle(fontSize = 10.sp, color = ink.copy(alpha = 0.8f)),
        )
        scope.drawText(axisLabel, topLeft = Offset(left, bottom + 8f))
    }
}

/** Formats a millimetre value for a chart label. */
fun millimetreLabel(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
