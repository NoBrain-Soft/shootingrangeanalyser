package com.nobrainsoft.rangeanalyser.vision

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.target.ScoringModel
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.core.target.ZoneShape
import com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.random.Random

/**
 * Renders target faces with bullet holes in them, so detection can be tested against known truth.
 *
 * The one detail worth getting right is how a hole looks **inside the black aiming mark**. A dark
 * disc on dark ink is nearly invisible; what actually gives it away is the ring of torn paper fibres
 * around the rim, which is lighter than the ink. Rendering that faithfully is what makes these
 * fixtures able to catch a detector that only works on white paper - which is most naive ones.
 */
object SyntheticTarget {

    data class Hole(val positionMm: PointMm, val diameterMm: Double)

    data class Config(
        val pixelsPerMm: Double = 4.0,
        val marginMm: Double = 15.0,
        val holes: List<Hole> = emptyList(),
        /** Shrinks the top edge relative to the bottom, as if shot from above. 0 is square on. */
        val verticalTilt: Double = 0.0,
        /** Shrinks the left edge relative to the right. */
        val horizontalTilt: Double = 0.0,
        val rotationDeg: Double = 0.0,
        val blurSigma: Double = 0.0,
        val noiseSigma: Double = 0.0,
        /** 0 is even lighting; 1 darkens the corners heavily. */
        val vignette: Double = 0.0,
        val paperGray: Double = 238.0,
        val inkGray: Double = 32.0,
        val holeGray: Double = 18.0,
        val seed: Long = 20260707L,
    ) {
        val hasPerspective: Boolean
            get() = verticalTilt != 0.0 || horizontalTilt != 0.0 || rotationDeg != 0.0
    }

    data class Rendered(
        val image: Mat,
        /** Ground truth: rendered image pixels to target millimetres. */
        val imageToTargetMm: Transform2d,
        val pixelsPerMm: Double,
        val holes: List<Hole>,
    ) {
        fun release() = image.release()
    }

    fun render(spec: TargetSpec, config: Config = Config()): Rendered {
        val radiusMm = spec.outerRadiusMm ?: 100.0
        val halfExtentMm = radiusMm + config.marginMm
        val side = (2 * halfExtentMm * config.pixelsPerMm).toInt().coerceAtLeast(32)
        val centre = Point(side / 2.0, side / 2.0)

        val canvas = Mat(side, side, CvType.CV_8UC1, Scalar(config.paperGray))
        drawFace(canvas, spec, centre, config)
        config.holes.forEach { drawHole(canvas, it, spec, centre, config) }

        // Ground truth before any warping: pixels out from the centre, flipped to y-up.
        val flatTransform = Transform2d.scale(1.0 / config.pixelsPerMm, -1.0 / config.pixelsPerMm) *
            Transform2d.translation(-centre.x, -centre.y)

        val (image, transform) = if (config.hasPerspective) {
            val warp = buildWarp(side, config)
            val warped = Mat()
            Imgproc.warpPerspective(
                canvas,
                warped,
                warp.toMat(),
                Size(side.toDouble(), side.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(config.paperGray),
            )
            canvas.release()
            // A point in the warped image came from warp-inverse of itself in the flat canvas.
            warped to (flatTransform * (warp.inverse() ?: Transform2d.IDENTITY))
        } else {
            canvas to flatTransform
        }

        applyOptics(image, config)

        return Rendered(image, transform, config.pixelsPerMm, config.holes)
    }

    // --- Face ----------------------------------------------------------------------------------

    private fun drawFace(canvas: Mat, spec: TargetSpec, centre: Point, config: Config) {
        spec.gridSpacingMm?.let { drawGrid(canvas, it, centre, config) }

        val blackDiameter = spec.blackDiameterMm
        if (blackDiameter != null) {
            Imgproc.circle(
                canvas,
                centre,
                (blackDiameter / 2.0 * config.pixelsPerMm).toInt(),
                Scalar(config.inkGray),
                -1,
                Imgproc.LINE_AA,
            )
        }

        when (val model = spec.scoring) {
            is ScoringModel.Rings -> drawRings(canvas, model, blackDiameter, centre, config)
            is ScoringModel.Zones -> drawZones(canvas, model, centre, config)
            ScoringModel.NoScoring -> Unit
        }
    }

    private fun drawRings(
        canvas: Mat,
        model: ScoringModel.Rings,
        blackDiameterMm: Double?,
        centre: Point,
        config: Config,
    ) {
        val thickness = lineThickness(config)
        for (ring in model.rings) {
            // Ring lines inside the aiming mark are printed white so they stay visible on the ink.
            val onBlack = blackDiameterMm != null && ring.diameterMm <= blackDiameterMm
            val colour = if (onBlack) Scalar(config.paperGray) else Scalar(config.inkGray)
            Imgproc.circle(
                canvas,
                centre,
                (ring.radiusMm * config.pixelsPerMm).toInt(),
                colour,
                thickness,
                Imgproc.LINE_AA,
            )
        }
    }

    private fun drawZones(canvas: Mat, model: ScoringModel.Zones, centre: Point, config: Config) {
        val thickness = lineThickness(config)
        for (zone in model.zones) {
            when (val shape = zone.shape) {
                is ZoneShape.Circle -> Imgproc.circle(
                    canvas,
                    toPixel(shape.centre, centre, config),
                    (shape.diameterMm / 2 * config.pixelsPerMm).toInt(),
                    Scalar(config.inkGray),
                    thickness,
                    Imgproc.LINE_AA,
                )

                is ZoneShape.Rect -> {
                    val halfWidth = shape.widthMm / 2 * config.pixelsPerMm
                    val halfHeight = shape.heightMm / 2 * config.pixelsPerMm
                    val middle = toPixel(shape.centre, centre, config)
                    Imgproc.rectangle(
                        canvas,
                        Point(middle.x - halfWidth, middle.y - halfHeight),
                        Point(middle.x + halfWidth, middle.y + halfHeight),
                        Scalar(config.inkGray),
                        thickness,
                    )
                }

                is ZoneShape.Polygon -> {
                    val points = shape.vertices.map { toPixel(it, centre, config) }
                    for (index in points.indices) {
                        Imgproc.line(
                            canvas,
                            points[index],
                            points[(index + 1) % points.size],
                            Scalar(config.inkGray),
                            thickness,
                        )
                    }
                }
            }
        }
    }

    private fun drawGrid(canvas: Mat, spacingMm: Double, centre: Point, config: Config) {
        val step = spacingMm * config.pixelsPerMm
        if (step < 2.0) return
        var offset = 0.0
        while (offset < canvas.cols()) {
            for (x in listOf(centre.x + offset, centre.x - offset)) {
                Imgproc.line(canvas, Point(x, 0.0), Point(x, canvas.rows().toDouble()), Scalar(190.0), 1)
            }
            for (y in listOf(centre.y + offset, centre.y - offset)) {
                Imgproc.line(canvas, Point(0.0, y), Point(canvas.cols().toDouble(), y), Scalar(190.0), 1)
            }
            offset += step
        }
    }

    /**
     * Draws a bullet hole.
     *
     * Two parts, and the second is the important one: a dark crater, and around its rim a ring of
     * torn paper fibres. On white paper the crater is what you see; inside the black aiming mark the
     * crater is invisible and the torn ring is the only evidence there is a hole there at all.
     */
    private fun drawHole(
        canvas: Mat,
        hole: Hole,
        spec: TargetSpec,
        centre: Point,
        config: Config,
    ) {
        val position = toPixel(hole.positionMm, centre, config)
        val radiusPx = hole.diameterMm / 2.0 * config.pixelsPerMm

        val tornRingColour = Scalar(config.paperGray * 0.82)
        Imgproc.circle(
            canvas,
            position,
            (radiusPx * 1.15).toInt().coerceAtLeast(1),
            tornRingColour,
            -1,
            Imgproc.LINE_AA,
        )

        Imgproc.circle(
            canvas,
            position,
            radiusPx.toInt().coerceAtLeast(1),
            Scalar(config.holeGray),
            -1,
            Imgproc.LINE_AA,
        )
    }

    // --- Camera imperfections ---------------------------------------------------------------------

    private fun applyOptics(image: Mat, config: Config) {
        if (config.blurSigma > 0.0) {
            Imgproc.GaussianBlur(image, image, Size(0.0, 0.0), config.blurSigma)
        }

        if (config.vignette > 0.0) {
            applyVignette(image, config.vignette)
        }

        if (config.noiseSigma > 0.0) {
            addNoise(image, config)
        }
    }

    /** Darkens towards the corners, the way a phone camera and uneven range lighting both do. */
    private fun applyVignette(image: Mat, strength: Double) {
        val centreX = image.cols() / 2.0
        val centreY = image.rows() / 2.0
        val maxRadius = kotlin.math.hypot(centreX, centreY)

        val mask = Mat(image.rows(), image.cols(), CvType.CV_32F)
        val row = FloatArray(image.cols())
        for (y in 0 until image.rows()) {
            for (x in 0 until image.cols()) {
                val distance = kotlin.math.hypot(x - centreX, y - centreY) / maxRadius
                row[x] = (1.0 - strength * distance * distance).toFloat()
            }
            mask.put(y, 0, row)
        }

        val float = Mat()
        image.convertTo(float, CvType.CV_32F)
        Core.multiply(float, mask, float)
        float.convertTo(image, CvType.CV_8U)
        float.release()
        mask.release()
    }

    private fun addNoise(image: Mat, config: Config) {
        val random = Random(config.seed)
        val buffer = ByteArray(image.cols())
        for (y in 0 until image.rows()) {
            image.get(y, 0, buffer)
            for (x in buffer.indices) {
                val value = buffer[x].toInt() and 0xFF
                val noisy = value + gaussian(random) * config.noiseSigma
                buffer[x] = noisy.coerceIn(0.0, 255.0).toInt().toByte()
            }
            image.put(y, 0, buffer)
        }
    }

    private fun gaussian(random: Random): Double {
        var u1 = random.nextDouble()
        while (u1 <= 0.0) u1 = random.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
            kotlin.math.cos(2.0 * Math.PI * random.nextDouble())
    }

    // --- Geometry -----------------------------------------------------------------------------

    /**
     * Builds the warp that fakes an off-axis camera, then fits the result back inside the canvas so
     * nothing is clipped.
     *
     * Note that fitting back inside rescales the content, so a warped render is **not** at the
     * requested pixels-per-millimetre. Tests that care about absolute scale should read it from
     * [Rendered.imageToTargetMm] rather than assuming [Config.pixelsPerMm].
     */
    private fun buildWarp(side: Int, config: Config): Transform2d {
        val centre = side / 2.0
        val corners = listOf(
            Point(0.0, 0.0),
            Point(side.toDouble(), 0.0),
            Point(side.toDouble(), side.toDouble()),
            Point(0.0, side.toDouble()),
        )

        val rotation = Transform2d.translation(centre, centre) *
            Transform2d.rotation(config.rotationDeg) *
            Transform2d.translation(-centre, -centre)

        val tilted = corners.map { corner ->
            // Top edge shrinks by verticalTilt, bottom edge grows by it, and likewise left/right.
            val verticalFactor = 1.0 - config.verticalTilt * (1.0 - 2.0 * corner.y / side)
            val horizontalFactor = 1.0 - config.horizontalTilt * (1.0 - 2.0 * corner.x / side)
            val moved = Point(
                centre + (corner.x - centre) * verticalFactor,
                centre + (corner.y - centre) * horizontalFactor,
            )
            rotation.applyToPoint(moved)
        }

        val fitted = fitInside(tilted, side)

        val source = MatOfPoint2f(*corners.toTypedArray())
        val destination = MatOfPoint2f(*fitted.toTypedArray())
        val matrix = Imgproc.getPerspectiveTransform(source, destination)
        val warp = Transform2d.fromMat(matrix)
        matrix.release()
        source.release()
        destination.release()
        return warp
    }

    private fun fitInside(corners: List<Point>, side: Int): List<Point> {
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }

        val scale = min(
            side / max(maxX - minX, 1e-6),
            side / max(maxY - minY, 1e-6),
        )
        val offsetX = (side - (maxX - minX) * scale) / 2.0
        val offsetY = (side - (maxY - minY) * scale) / 2.0

        return corners.map {
            Point((it.x - minX) * scale + offsetX, (it.y - minY) * scale + offsetY)
        }
    }

    private fun min(a: Double, b: Double) = if (a < b) a else b

    private fun toPixel(millimetres: PointMm, centre: Point, config: Config) = Point(
        centre.x + millimetres.x * config.pixelsPerMm,
        centre.y - millimetres.y * config.pixelsPerMm,
    )

    private fun lineThickness(config: Config): Int =
        max(1, (0.3 * config.pixelsPerMm).toInt())
}
