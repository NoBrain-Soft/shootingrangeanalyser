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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.target.ScoringModel
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.core.target.ZoneShape

/**
 * One set of shots drawn together. Comparison overlays several; everything else uses one.
 */
data class ShotLayer(
    val label: String,
    val shots: List<Shot>,
    val colour: Color,
    val numbered: Boolean = true,
    val showCentroid: Boolean = false,
    /** Drawn hollow, for the "before" side of a comparison. */
    val outlined: Boolean = false,
)

/** Where a tap landed: on a shot, or on bare target. */
sealed interface TargetTap {
    data class OnShot(val shotId: String, val positionMm: PointMm) : TargetTap

    data class OnTarget(val positionMm: PointMm) : TargetTap
}

/**
 * The target face with shots on it, pannable and zoomable.
 *
 * Built once and used by the review editor, the results screen, the live overlay and the
 * comparison view. Keeping one implementation means a shot is drawn in the same place, at the same
 * size, wherever the user sees it - which matters more than it sounds when someone is checking a
 * doubtful call against a photograph.
 *
 * The component reports taps and leaves policy to the screen. Notably it does *not* implement
 * drag-to-move: on a phone at a range, dragging a 5 mm marker with cold hands is a poor gesture, and
 * it fights pan and zoom for the same pointer. Screens that allow editing use select-then-place
 * instead, which is unambiguous and works with gloves on.
 */
@Composable
fun TargetView(
    spec: TargetSpec,
    layers: List<ShotLayer>,
    modifier: Modifier = Modifier,
    pointOfAim: PointMm = PointMm.ORIGIN,
    selectedShotId: String? = null,
    /** Draws the widest pair, as measured by the statistics layer. */
    extremeSpreadPair: Pair<PointMm, PointMm>? = null,
    showRings: Boolean = true,
    onTap: ((TargetTap) -> Unit)? = null,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()

    val faceRadiusMm = (spec.outerRadiusMm ?: 100.0).toFloat()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(spec.id) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    pan += panChange
                }
            }
            .pointerInput(spec.id, layers, onTap) {
                if (onTap == null) return@pointerInput
                detectTapGestures { offset ->
                    val projection = Projection(size.toSize(), faceRadiusMm, zoom, pan)
                    val positionMm = projection.toMillimetres(offset)
                    val hit = nearestShot(layers, positionMm, projection.hitRadiusMm())
                    onTap(
                        if (hit != null) {
                            TargetTap.OnShot(hit.id, positionMm)
                        } else {
                            TargetTap.OnTarget(positionMm)
                        },
                    )
                }
            },
    ) {
        val projection = Projection(size, faceRadiusMm, zoom, pan)

        if (showRings) drawFace(spec, projection, textMeasurer)
        drawAimPoint(pointOfAim, projection)
        extremeSpreadPair?.let { drawSpreadLine(it, projection) }

        for (layer in layers) {
            drawLayer(layer, projection, selectedShotId, textMeasurer)
        }
    }
}

/**
 * Maps target millimetres to canvas pixels, honouring pan and zoom.
 *
 * Held as a value rather than a matrix so hit testing and drawing cannot drift apart.
 */
private class Projection(
    canvasSize: Size,
    faceRadiusMm: Float,
    private val zoom: Float,
    private val pan: Offset,
) {
    private val centre = Offset(canvasSize.width / 2f, canvasSize.height / 2f) + pan

    /** Fit the whole face with a little room to spare. */
    val pixelsPerMm: Float =
        (minOf(canvasSize.width, canvasSize.height) / 2f) / (faceRadiusMm * FACE_MARGIN) * zoom

    fun toCanvas(positionMm: PointMm): Offset = Offset(
        centre.x + positionMm.x.toFloat() * pixelsPerMm,
        // Target coordinates count upwards; canvas rows count down.
        centre.y - positionMm.y.toFloat() * pixelsPerMm,
    )

    fun toMillimetres(offset: Offset): PointMm = PointMm(
        x = ((offset.x - centre.x) / pixelsPerMm).toDouble(),
        y = ((centre.y - offset.y) / pixelsPerMm).toDouble(),
    )

    fun lengthPx(millimetres: Double): Float = millimetres.toFloat() * pixelsPerMm

    /** How close a tap has to be to count as hitting a shot: a fingertip, in target units. */
    fun hitRadiusMm(): Double = (TAP_RADIUS_PX / pixelsPerMm).toDouble()

    private companion object {
        const val FACE_MARGIN = 1.08f
        const val TAP_RADIUS_PX = 48f
    }
}

private fun nearestShot(layers: List<ShotLayer>, positionMm: PointMm, withinMm: Double): Shot? =
    layers
        .flatMap { it.shots }
        .filter { it.position.distanceTo(positionMm) <= withinMm }
        .minByOrNull { it.position.distanceTo(positionMm) }

private fun DrawScope.drawFace(
    spec: TargetSpec,
    projection: Projection,
    textMeasurer: TextMeasurer,
) {
    val paper = Color(0xFFF2EFEA)
    val ink = Color(0xFF1B1B1B)
    val line = Color(0xFF6B6B6B)

    // The paper itself, so the target reads as a target rather than as a chart.
    val faceRadius = projection.lengthPx(spec.outerRadiusMm ?: 100.0)
    drawCircle(paper, radius = faceRadius * 1.06f, center = projection.toCanvas(PointMm.ORIGIN))

    spec.blackDiameterMm?.let { black ->
        drawCircle(ink, projection.lengthPx(black / 2.0), projection.toCanvas(PointMm.ORIGIN))
    }

    when (val model = spec.scoring) {
        is ScoringModel.Rings -> {
            for (ring in model.rings) {
                val onInk = spec.blackDiameterMm?.let { ring.diameterMm <= it } == true
                drawCircle(
                    color = if (onInk) paper.copy(alpha = 0.85f) else line,
                    radius = projection.lengthPx(ring.radiusMm),
                    center = projection.toCanvas(PointMm.ORIGIN),
                    style = Stroke(width = 1.5f),
                )
            }
            drawRingLabels(model, spec, projection, textMeasurer, paper, ink)
        }

        is ScoringModel.Zones -> drawZones(model, projection, line, textMeasurer)
        ScoringModel.NoScoring -> Unit
    }
}

private fun DrawScope.drawRingLabels(
    model: ScoringModel.Rings,
    spec: TargetSpec,
    projection: Projection,
    textMeasurer: TextMeasurer,
    paper: Color,
    ink: Color,
) {
    // Only label rings with room for a number, and only every other one on a crowded face.
    val labelled = model.byValueDescending.filterIndexed { index, ring ->
        projection.lengthPx(ring.radiusMm) > 26f && index % 2 == 0
    }
    for (ring in labelled) {
        val onInk = spec.blackDiameterMm?.let { ring.diameterMm <= it } == true
        val at = projection.toCanvas(PointMm(0.0, -ring.radiusMm + ring.radiusMm * 0.06))
        val layout = textMeasurer.measure(
            ring.value.toString(),
            TextStyle(fontSize = 10.sp, color = if (onInk) paper else ink),
        )
        drawText(
            layout,
            topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
        )
    }
}

private fun DrawScope.drawZones(
    model: ScoringModel.Zones,
    projection: Projection,
    line: Color,
    textMeasurer: TextMeasurer,
) {
    for (zone in model.zones) {
        when (val shape = zone.shape) {
            is ZoneShape.Circle -> drawCircle(
                color = line,
                radius = projection.lengthPx(shape.diameterMm / 2.0),
                center = projection.toCanvas(shape.centre),
                style = Stroke(width = 2f),
            )

            is ZoneShape.Rect -> {
                val topLeft = projection.toCanvas(
                    PointMm(
                        shape.centre.x - shape.widthMm / 2,
                        shape.centre.y + shape.heightMm / 2,
                    ),
                )
                drawRect(
                    color = line,
                    topLeft = topLeft,
                    size = Size(
                        projection.lengthPx(shape.widthMm),
                        projection.lengthPx(shape.heightMm),
                    ),
                    style = Stroke(width = 2f),
                )
            }

            is ZoneShape.Polygon -> {
                val points = shape.vertices.map { projection.toCanvas(it) }
                for (index in points.indices) {
                    drawLine(
                        color = line,
                        start = points[index],
                        end = points[(index + 1) % points.size],
                        strokeWidth = 2f,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawAimPoint(pointOfAim: PointMm, projection: Projection) {
    if (pointOfAim == PointMm.ORIGIN) return
    // A deliberate hold-off deserves to be visible: every zeroing number is measured from here.
    val at = projection.toCanvas(pointOfAim)
    val arm = 10f
    val colour = Color(0xFF3B82F6)
    drawLine(colour, Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), strokeWidth = 2f)
    drawLine(colour, Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), strokeWidth = 2f)
}

private fun DrawScope.drawSpreadLine(pair: Pair<PointMm, PointMm>, projection: Projection) {
    drawLine(
        color = Color(0xFF3B82F6).copy(alpha = 0.7f),
        start = projection.toCanvas(pair.first),
        end = projection.toCanvas(pair.second),
        strokeWidth = 2f,
    )
}

private fun DrawScope.drawLayer(
    layer: ShotLayer,
    projection: Projection,
    selectedShotId: String?,
    textMeasurer: TextMeasurer,
) {
    val markerRadius = MARKER_RADIUS_PX

    for ((index, shot) in layer.shots.withIndex()) {
        val at = projection.toCanvas(shot.position)
        val colour = if (shot.excluded) layer.colour.copy(alpha = 0.35f) else layer.colour

        if (layer.outlined) {
            drawCircle(colour, markerRadius, at, style = Stroke(width = 2.5f))
        } else {
            drawCircle(colour, markerRadius, at)
        }

        // A doubtful detection gets a ring around it so the eye goes straight to what needs checking.
        if (shot.confidence < UNCERTAIN_BELOW && !shot.excluded) {
            drawCircle(
                color = Color(0xFFE8A33D),
                radius = markerRadius + 6f,
                center = at,
                style = Stroke(width = 2.5f),
            )
        }

        if (shot.id == selectedShotId) {
            drawCircle(
                color = Color.White,
                radius = markerRadius + 11f,
                center = at,
                style = Stroke(width = 3f),
            )
        }

        if (layer.numbered) {
            val layout = textMeasurer.measure(
                (index + 1).toString(),
                TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold),
            )
            drawText(
                layout,
                topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
            )
        }
    }

    if (layer.showCentroid && layer.shots.isNotEmpty()) {
        val centroid = layer.shots
            .filterNot { it.excluded }
            .map { it.position }
            .takeIf { it.isNotEmpty() }
            ?.let { positions ->
                PointMm(positions.sumOf { it.x } / positions.size, positions.sumOf { it.y } / positions.size)
            }
        centroid?.let {
            val at = projection.toCanvas(it)
            drawCircle(layer.colour, 5f, at, style = Stroke(width = 2f))
            drawLine(layer.colour, Offset(at.x - 14f, at.y), Offset(at.x + 14f, at.y), strokeWidth = 1.5f)
            drawLine(layer.colour, Offset(at.x, at.y - 14f), Offset(at.x, at.y + 14f), strokeWidth = 1.5f)
        }
    }
}

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 12f
private const val MARKER_RADIUS_PX = 9f
private const val UNCERTAIN_BELOW = 0.6
