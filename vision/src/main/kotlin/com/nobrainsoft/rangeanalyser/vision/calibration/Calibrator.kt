package com.nobrainsoft.rangeanalyser.vision.calibration

import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.CalibrationMethod
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Works out how many millimetres a pixel is worth, and where the target sits in the frame.
 *
 * There is no QR code to lean on, so every method here trades convenience against reliability:
 *
 * - [fromRingGeometry] measures the target's own printed aiming mark. Needs nothing from the user
 *   and is the most accurate, but only works on a face whose dimensions are known.
 * - [fromPaperQuad] finds the sheet outline. Recovers full perspective, so it survives an off-axis
 *   photo that the others do not.
 * - [fromReferenceLength] is the universal fallback: the user marks two points and says how far
 *   apart they really are.
 * - [fromBulletHoles] infers scale from hole size against a known calibre. Deliberately marked low
 *   confidence - paper tears and springs back, so a hole is not the bullet's diameter.
 * - [fromOptics] uses the camera's own focal length and a known target distance. Good for live
 *   mode, where there may be nothing on the target big enough to measure.
 */
object Calibrator {

    /** Below this the app should say the calibration is shaky rather than quietly using it. */
    const val LOW_CONFIDENCE = 0.45

    /** Two methods differing by more than this raise a warning instead of one being chosen. */
    const val DISAGREEMENT_THRESHOLD = 0.08

    /**
     * Below this a detection is refused outright rather than returned with a low badge.
     *
     * The failure it guards against is specific and nasty: when the target is small in frame, the
     * ring lines blur together into one dark blob, and the detector happily fits an ellipse to the
     * *outermost* ring while believing it is the aiming mark. That yields a confidently-shaped
     * result with a badly wrong scale. A low confidence number is easy to ignore; refusing to
     * answer is not.
     */
    const val MIN_USABLE_CONFIDENCE = 0.15

    private const val MIN_BULL_AXIS_PX = 40.0
    private const val MIN_QUAD_AREA_FRACTION = 0.15

    /**
     * Fits the target's printed aiming mark and derives scale from its known diameter.
     *
     * Recovering a full projective homography from a single ellipse is under-determined - one conic
     * does not pin down all eight degrees of freedom - so this builds the *affine* rectification
     * that maps the fitted ellipse back to a circle. That is exact for a camera far enough away
     * that perspective is weak, which is the normal case for a target photographed from a few
     * metres, and it correctly removes the ellipse-shaped distortion of a tilted shot. A strongly
     * angled close-up needs [fromPaperQuad] or four tapped corners instead.
     */
    fun fromRingGeometry(grayImage: Mat, spec: TargetSpec): CalibrationAttempt? {
        val knownDiameterMm = spec.blackDiameterMm ?: return null
        val bull = findAimingMark(grayImage) ?: return null

        val majorAxis = max(bull.size.width, bull.size.height)
        val minorAxis = min(bull.size.width, bull.size.height)
        if (minorAxis < MIN_BULL_AXIS_PX) return null

        val transform = ellipseToCircleTransform(bull, knownDiameterMm / 2.0)

        val confidence = Confidence.clamp(
            Confidence.ramp(minorAxis, MIN_BULL_AXIS_PX, 160.0) *
                Confidence.tiltScore(minorAxis, majorAxis),
        )
        if (confidence < MIN_USABLE_CONFIDENCE) return null

        return CalibrationAttempt(
            method = CalibrationMethod.RING_GEOMETRY,
            transform = transform,
            millimetresPerPixel = transform.millimetresPerPixelAtCentre(),
            confidence = confidence,
            correctsPerspective = true,
            evidence = CalibrationEvidence.Ellipse(bull, knownDiameterMm),
        )
    }

    /**
     * Finds the sheet outline and uses its known paper size.
     *
     * Four corresponding points is exactly what a projective homography needs, so unlike the ellipse
     * method this genuinely removes perspective however steep the angle.
     */
    fun fromPaperQuad(grayImage: Mat, spec: TargetSpec): CalibrationAttempt? {
        val widthMm = spec.sheetWidthMm ?: return null
        val heightMm = spec.sheetHeightMm ?: return null
        val corners = findSheetCorners(grayImage) ?: return null
        return fromCorners(corners, widthMm, heightMm, confidence = 0.75)
    }

    /**
     * Builds a homography from four corners of a known-size rectangle, tapped or detected.
     */
    fun fromCorners(
        corners: List<Point>,
        widthMm: Double,
        heightMm: Double,
        confidence: Double = 0.9,
    ): CalibrationAttempt? {
        if (corners.size != 4) return null
        val ordered = orderCorners(corners)

        val source = MatOfPoint2f(*ordered.toTypedArray())
        // Target frame: origin at the centre of the sheet, +y up.
        val destination = MatOfPoint2f(
            Point(-widthMm / 2, heightMm / 2),
            Point(widthMm / 2, heightMm / 2),
            Point(widthMm / 2, -heightMm / 2),
            Point(-widthMm / 2, -heightMm / 2),
        )

        val matrix = Imgproc.getPerspectiveTransform(source, destination)
        val transform = Transform2d.fromMat(matrix)
        matrix.release()
        source.release()
        destination.release()

        return CalibrationAttempt(
            method = CalibrationMethod.PAPER_QUAD,
            transform = transform,
            millimetresPerPixel = transform.millimetresPerPixelAtCentre(),
            confidence = Confidence.clamp(confidence),
            correctsPerspective = true,
            evidence = CalibrationEvidence.Quad(ordered, widthMm, heightMm),
        )
    }

    /**
     * Scale from two points the user marked and the real distance between them.
     *
     * Gives scale only, so [targetCentre] has to come from somewhere else - the detected aiming
     * mark, or the user tapping the middle. The wizard does the latter when nothing is detectable.
     */
    fun fromReferenceLength(
        from: Point,
        to: Point,
        realDistanceMm: Double,
        targetCentre: Point,
    ): CalibrationAttempt? {
        val pixelDistance = kotlin.math.hypot(to.x - from.x, to.y - from.y)
        if (pixelDistance < 5.0 || realDistanceMm <= 0.0) return null

        val millimetresPerPixel = realDistanceMm / pixelDistance

        return CalibrationAttempt(
            method = CalibrationMethod.MANUAL_REFERENCE,
            transform = scaleOnlyTransform(targetCentre, millimetresPerPixel),
            millimetresPerPixel = millimetresPerPixel,
            // A long reference line is measured more precisely than a short one.
            confidence = Confidence.clamp(0.55 + Confidence.ramp(pixelDistance, 50.0, 600.0) * 0.4),
            correctsPerspective = false,
            evidence = CalibrationEvidence.Reference(from, to, realDistanceMm),
        )
    }

    /**
     * Scale inferred from how big the bullet holes are.
     *
     * Always low confidence, and not because the maths is shaky. Paper is elastic: it tears, then
     * springs back, so a hole measures smaller than the bullet that made it, by an amount that
     * depends on the paper, the backing, the velocity and the projectile shape. This is a useful
     * sanity check on another method and a last resort on its own.
     */
    fun fromBulletHoles(
        holeDiametersPx: List<Double>,
        caliber: Caliber,
        targetCentre: Point,
    ): CalibrationAttempt? {
        val usable = holeDiametersPx.filter { it > 2.0 }.sorted()
        if (usable.isEmpty()) return null

        // Median rather than mean: one merged double-hole would drag an average badly.
        val medianPx = usable[usable.size / 2]
        val millimetresPerPixel = caliber.bulletDiameterMm / medianPx

        return CalibrationAttempt(
            method = CalibrationMethod.BULLET_CALIBER,
            transform = scaleOnlyTransform(targetCentre, millimetresPerPixel),
            millimetresPerPixel = millimetresPerPixel,
            // More holes narrow the median, but the systematic error does not go away.
            confidence = Confidence.clamp(0.2 + Confidence.ramp(usable.size.toDouble(), 1.0, 10.0) * 0.2),
            correctsPerspective = false,
            evidence = CalibrationEvidence.Holes(usable, caliber.bulletDiameterMm),
        )
    }

    /**
     * Scale from the camera's optics and a known distance to the target.
     *
     * Assumes the target plane is square on and the distance is right; both are reasonable on a
     * marked range, and this is often the only method available in live mode where the target may
     * be too far away for anything on it to be measured.
     */
    fun fromOptics(
        distanceM: Double,
        focalLengthMm: Double,
        pixelPitchMm: Double,
        zoomRatio: Double,
        targetCentre: Point,
    ): CalibrationAttempt? {
        if (distanceM <= 0.0 || focalLengthMm <= 0.0 || pixelPitchMm <= 0.0 || zoomRatio <= 0.0) {
            return null
        }

        val millimetresPerPixel = pixelPitchMm * (distanceM * 1000.0) / (focalLengthMm * zoomRatio)

        return CalibrationAttempt(
            method = CalibrationMethod.OPTICAL_DISTANCE,
            transform = scaleOnlyTransform(targetCentre, millimetresPerPixel),
            millimetresPerPixel = millimetresPerPixel,
            confidence = 0.5,
            correctsPerspective = false,
        )
    }

    /**
     * Picks the best attempt and reports whether the others agree with it.
     *
     * Preference goes to perspective-correcting methods, then to confidence. A material
     * disagreement between two independent methods is surfaced, never resolved silently - the whole
     * app's numbers hang off this one value.
     */
    fun reconcile(attempts: List<CalibrationAttempt>): CalibrationOutcome {
        val usable = attempts.filter { it.millimetresPerPixel > 0.0 && it.millimetresPerPixel.isFinite() }
        if (usable.isEmpty()) return CalibrationOutcome(null, emptyList(), null)

        val ranked = usable.sortedWith(
            compareByDescending<CalibrationAttempt> { it.correctsPerspective }
                .thenByDescending { it.confidence },
        )
        val best = ranked.first()
        val rest = ranked.drop(1)

        // Compare against the most trustworthy of the others rather than all of them, so a
        // deliberately rough cross-check does not raise a warning on its own.
        val challenger = rest.maxByOrNull { it.confidence }
        val disagreement = challenger
            ?.takeIf { it.confidence >= LOW_CONFIDENCE }
            ?.let {
                val difference = Confidence.relativeDifference(
                    best.millimetresPerPixel,
                    it.millimetresPerPixel,
                )
                if (difference > DISAGREEMENT_THRESHOLD) {
                    CalibrationDisagreement(listOf(best.method, it.method), difference)
                } else {
                    null
                }
            }

        return CalibrationOutcome(best, rest, disagreement)
    }

    /**
     * Where the target's aiming mark sits in the frame, if one can be found.
     *
     * The scale-only methods need an origin from somewhere: a reference length tells you how big a
     * pixel is but not where the middle of the target is. This gives them a real answer instead of
     * assuming the middle of the photograph, which is only right when the shooter framed perfectly.
     */
    fun findTargetCentre(grayImage: Mat): Point? = findAimingMark(grayImage)?.center

    // --- Detection helpers ------------------------------------------------------------------------

    /**
     * Finds the printed aiming mark: the largest dark, roughly round, fully visible blob.
     */
    internal fun findAimingMark(grayImage: Mat): RotatedRect? {
        val blurred = Mat()
        Imgproc.GaussianBlur(grayImage, blurred, Size(5.0, 5.0), 0.0)

        val binary = Mat()
        Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        blurred.release()

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(binary, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        binary.release()

        var best: RotatedRect? = null
        var bestScore = 0.0

        for (contour in contours) {
            if (contour.total() < 5) continue

            val bounds = Imgproc.boundingRect(contour)
            // A blob running off the edge of the frame is the background, not the aiming mark.
            val touchesBorder = bounds.x <= 1 || bounds.y <= 1 ||
                bounds.x + bounds.width >= grayImage.cols() - 1 ||
                bounds.y + bounds.height >= grayImage.rows() - 1
            if (touchesBorder) continue

            val area = Imgproc.contourArea(contour)
            if (area < 200.0) continue

            val points = MatOfPoint2f(*contour.toArray())
            val ellipse = Imgproc.fitEllipse(points)
            points.release()

            val ellipseArea = Math.PI * ellipse.size.width * ellipse.size.height / 4.0
            if (ellipseArea <= 0.0) continue

            // A solid disc fills its fitted ellipse. Ring lines and text do not.
            val fill = area / ellipseArea
            if (fill < 0.80 || fill > 1.25) continue

            val score = area * fill
            if (score > bestScore) {
                bestScore = score
                best = ellipse
            }
        }

        contours.forEach { it.release() }
        return best
    }

    /**
     * Finds the sheet: the largest four-sided contour covering a decent share of the frame.
     */
    internal fun findSheetCorners(grayImage: Mat): List<Point>? {
        val blurred = Mat()
        Imgproc.GaussianBlur(grayImage, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)
        blurred.release()

        // Close small gaps so a slightly broken outline still forms one contour.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        edges.release()

        val frameArea = (grayImage.rows() * grayImage.cols()).toDouble()
        var best: List<Point>? = null
        var bestArea = frameArea * MIN_QUAD_AREA_FRACTION

        for (contour in contours) {
            val points = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(points, true)
            val approximated = MatOfPoint2f()
            Imgproc.approxPolyDP(points, approximated, 0.02 * perimeter, true)

            if (approximated.total() == 4L) {
                val quad = approximated.toArray().toList()
                val area = kotlin.math.abs(Imgproc.contourArea(MatOfPoint(*quad.toTypedArray())))
                if (area > bestArea) {
                    bestArea = area
                    best = quad
                }
            }
            points.release()
            approximated.release()
        }

        contours.forEach { it.release() }
        return best
    }

    /** Orders four corners as top-left, top-right, bottom-right, bottom-left in image space. */
    internal fun orderCorners(corners: List<Point>): List<Point> {
        require(corners.size == 4) { "need exactly four corners" }
        val topLeft = corners.minByOrNull { it.x + it.y }!!
        val bottomRight = corners.maxByOrNull { it.x + it.y }!!
        val topRight = corners.maxByOrNull { it.x - it.y }!!
        val bottomLeft = corners.minByOrNull { it.x - it.y }!!
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * Maps a fitted ellipse back onto the circle it must have been.
     *
     * Undo the ellipse's rotation, stretch the short axis back out to match the long one, scale to
     * millimetres, and flip y because image rows count downwards while the target frame counts up.
     */
    internal fun ellipseToCircleTransform(ellipse: RotatedRect, knownRadiusMm: Double): Transform2d {
        val semiWidth = ellipse.size.width / 2.0
        val semiHeight = ellipse.size.height / 2.0

        val toOrigin = Transform2d.translation(-ellipse.center.x, -ellipse.center.y)
        val unrotate = Transform2d.rotation(-ellipse.angle)
        val toUnitCircle = Transform2d.scale(1.0 / semiWidth, 1.0 / semiHeight)
        val toMillimetres = Transform2d.scale(knownRadiusMm, knownRadiusMm)
        val flipY = Transform2d.scale(1.0, -1.0)

        return flipY * toMillimetres * toUnitCircle * unrotate * toOrigin
    }

    /** Scale about a known centre, with no perspective correction. */
    internal fun scaleOnlyTransform(centre: Point, millimetresPerPixel: Double): Transform2d =
        Transform2d.scale(millimetresPerPixel, -millimetresPerPixel) *
            Transform2d.translation(-centre.x, -centre.y)
}
