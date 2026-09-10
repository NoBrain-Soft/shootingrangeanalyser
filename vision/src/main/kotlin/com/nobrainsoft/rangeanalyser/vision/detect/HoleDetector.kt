package com.nobrainsoft.rangeanalyser.vision.detect

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.vision.calibration.RectifiedImage
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One hole the detector believes it found. */
data class DetectedHole(
    val positionMm: PointMm,
    val diameterMm: Double,
    /** 0..1. Anything low is flagged in the review screen rather than hidden. */
    val confidence: Double,
    /** Where it sits in the rectified image, for drawing and for cropping a thumbnail. */
    val pixelCentre: Point,
    val pixelRadius: Double,
    /**
     * How many shots the blob this came from was judged to hold.
     *
     * Overlapping holes are normal on a good group, and reporting one hole where there were three
     * quietly flatters both the score and the group size. Above one, the positions are inferred from
     * the blob's shape rather than measured, and the confidence is reduced to match.
     */
    val shotsInBlob: Int = 1,
) {
    val isEstimatedFromCluster: Boolean get() = shotsInBlob > 1
}

data class DetectionResult(
    val holes: List<DetectedHole>,
    /** What the detector assumed, so the UI can explain a poor result instead of just showing one. */
    val expectedHoleDiameterPx: Double,
    val paperLevel: Double,
    val inkLevel: Double,
    val calibreKnown: Boolean,
) {
    val uncertain: List<DetectedHole>
        get() = holes.filter { it.confidence < HoleDetector.LOW_CONFIDENCE }
}

/**
 * Finds bullet holes in a rectified target photograph.
 *
 * The pipeline is shaped by one awkward fact: a hole looks completely different depending on what it
 * went through. On white paper it is a dark crater with plenty of contrast. Inside the black aiming
 * mark the crater is invisible - ink and shadow are the same colour - and the only evidence is a
 * thin ring of torn paper fibres around the rim, which is *lighter* than its surroundings.
 *
 * Three decisions follow, and each replaced something that failed a test:
 *
 * 1. **Compare against a synthesised clean target, with its lighting fitted to the photograph.**
 *    Absolute difference catches both polarities. The synthetic face is evenly lit and a real
 *    photograph never is, so a smooth gain field - the ratio of heavily blurred versions of the two
 *    - absorbs vignetting and uneven range lighting that would otherwise light up whole corners.
 *
 * 2. **Fill contours by enclosed area, rather than closing with a kernel.** The torn ring is a
 *    pixel or two thick and encircles a gap as wide as the bullet, so a closing kernel would have to
 *    be wider than a hole - and at that width it starts welding holes to the printed ring lines
 *    beside them. There is no kernel size that does both jobs. Filling an external contour turns any
 *    enclosed ring into a disc whatever its size, and the printed rings are then rejected on area,
 *    since a scoring ring encloses thousands of times a bullet's worth of paper.
 *
 * 3. **Split a merged blob at the peaks of its distance transform.** A tight group is one region,
 *    and the middle of each hole in it is a local maximum of distance-from-the-edge - which survives
 *    its neighbours overlapping it. Reading those peaks back gives the holes in two dimensions and
 *    gives their number as a side effect. Area decides only whether a blob holds more than one shot;
 *    where they are is measured, not inferred, and the fallback for a blob with no clear peaks is
 *    still to space them along its long axis.
 *
 * The filling and bridging steps are deliberately tight for the same reason. Generous versions of
 * either weld a good group into a single mass: eleven shots in a club target came back as one,
 * because the pockets between adjacent holes were being flooded and the whole group became one blob
 * too wide to be a hole and too small to be printing.
 */
object HoleDetector {

    const val LOW_CONFIDENCE = 0.6

    /** Below about this many pixels across, a hole cannot be told from noise at all. */
    const val MIN_HOLE_DIAMETER_PX = 6.0

    /** Bridges noise-induced gaps in the torn ring without disturbing anything larger. */
    private const val BRIDGE_KERNEL_FRACTION = 0.12

    /** Anything narrower than this fraction of a hole is printing, not a shot. */
    private const val THIN_STRUCTURE_FRACTION = 0.45

    private const val MIN_RESIDUAL_THRESHOLD = 20.0
    private const val RESIDUAL_CONTRAST_FRACTION = 0.15

    /** A blob smaller than this fraction of one hole is a speck of noise. */
    private const val MIN_BLOB_AREA_FRACTION = 0.30

    /**
     * How far the synthetic face may sit from the photograph without counting as a difference.
     *
     * Deliberately about one pixel's worth. It needs to cover printed line weight and sub-pixel
     * calibration error and nothing more: widening it to half a millimetre also erased the torn ring
     * of any hole that happened to overlap a white ring line inside the aiming mark, losing exactly
     * the central hits the shooter cares most about.
     */
    private const val REGISTRATION_TOLERANCE_MM = 0.15

    /**
     * A single hole covers rather more paper than its bullet's cross-section, because the torn rim
     * is part of what the camera sees. Blur adds a little more. Counting shots in a blob divides by
     * this apparent size rather than by the bullet's, or every slightly blurred hole reads as two.
     */
    private const val APPARENT_HOLE_AREA_FACTOR = 1.32

    /**
     * Below this multiple of one apparent hole, the blob is a single shot.
     *
     * Sits between the two cases it has to separate: a single hole reads about 1.0 to 1.3 apparent
     * holes depending on blur, and two pellets overlapping half their width read about 1.8.
     */
    private const val SPLIT_AREA_FRACTION = 1.5
    private const val MAX_SHOTS_PER_BLOB = 8

    /** Largest enclosed region, in holes' worth of area, that is treated as a hole and filled in. */
    private const val MAX_FILLED_SHOTS = 2.0

    /**
     * A hole reads larger than its bullet, because the torn rim is part of what you see. Anything
     * within this fraction of the expected size counts as a full match.
     */
    private const val SIZE_TOLERANCE = 0.30

    /** An arc of printed ring line has a low ratio of area to convex hull; a hole does not. */
    private const val MIN_SOLIDITY = 0.75

    /**
     * Bounds on the short axis of a blob, as a multiple of one hole's diameter.
     *
     * This is what separates a bullet hole from the target's own printing, and neither area nor
     * contour shape can do it: the outer boundary of a printed ring is a circle, and so is a filled
     * hole, with identical circularity. What differs is width. However many shots overlap into one
     * blob, the blob stays about one bullet wide across its short axis, whereas a scoring ring is as
     * wide as the ring is across.
     */
    private const val MIN_BLOB_WIDTH_FACTOR = 0.4
    private const val MAX_BLOB_WIDTH_FACTOR = 2.2

    /** Longest a blob may be, in hole diameters, before it is printing rather than a group. */
    private const val MAX_BLOB_SPAN_FACTOR = 8.0

    /**
     * How much of its own bounding box a wide blob must fill to be a clump of holes.
     *
     * Discs packed together fill roughly three quarters of the box around them; an arc of a printed
     * ring fills a third or less, because most of its box is the empty middle of the curve.
     */
    private const val MIN_CLUSTER_FILL = 0.55

    /** Neighbourhood a pixel must dominate to count as the middle of a hole. */
    private const val PEAK_NEIGHBOURHOOD_FRACTION = 0.5

    /** How deep inside the blob a peak must be, as a fraction of one hole's diameter. */
    private const val MIN_PEAK_DISTANCE_FRACTION = 0.22

    /** Two peaks closer than this are the same hole found twice. */
    private const val MIN_PEAK_SEPARATION_FRACTION = 0.7

    fun detect(
        rectified: RectifiedImage,
        spec: TargetSpec,
        caliber: Caliber?,
        marginMm: Double = 5.0,
    ): DetectionResult {
        val levels = ExpectedTarget.estimateLevels(rectified, spec)
        val expected = ExpectedTarget.render(rectified, spec, levels)
        val predicted = fitLighting(rectified.image, expected)
        expected.release()

        val residual = residualWithTolerance(rectified.image, predicted, rectified.pixelsPerMm)
        predicted.release()

        val threshold = max(MIN_RESIDUAL_THRESHOLD, RESIDUAL_CONTRAST_FRACTION * levels.contrast)
        val binary = Mat()
        Imgproc.threshold(residual, binary, threshold, 255.0, Imgproc.THRESH_BINARY)

        val expectedDiameterPx = caliber
            ?.let { rectified.expectedHoleDiameterPx(it.bulletDiameterMm) }
            ?: estimateHoleDiameter(binary, rectified)

        if (expectedDiameterPx < MIN_HOLE_DIAMETER_PX) {
            residual.release()
            binary.release()
            return emptyResult(expectedDiameterPx, levels, caliber)
        }

        // Seal noise gaps so a torn ring is a closed curve, fill what such curves enclose, then
        // erode away everything too thin to be a hole. After this the target's own printing is gone
        // and each hole stands alone - including one that landed on a ring line, which is otherwise
        // welded to it and rejected for being the width of the whole ring.
        bridgeGaps(binary, expectedDiameterPx)
        fillEnclosedHoles(binary, expectedDiameterPx)
        removeThinStructures(binary, expectedDiameterPx)

        val holes = candidatesFrom(binary, expectedDiameterPx)
            .flatMap { candidate ->
                toHoles(candidate, residual, rectified, spec, expectedDiameterPx, marginMm, threshold)
            }

        binary.release()
        residual.release()

        return DetectionResult(
            holes = holes.sortedByDescending { it.confidence },
            expectedHoleDiameterPx = expectedDiameterPx,
            paperLevel = levels.paper,
            inkLevel = levels.ink,
            calibreKnown = caliber != null,
        )
    }

    /**
     * Scales the synthetic target to match the photograph's actual lighting.
     *
     * The gain is the ratio of heavily blurred versions of the two images, which tracks how
     * brightness varies across the frame without following anything as small as a bullet hole. The
     * blur radius is tied to the image size, because vignetting and range lighting vary on the scale
     * of the whole picture rather than on the scale of a hole.
     */
    private fun fitLighting(actual: Mat, expected: Mat): Mat {
        val sigma = max(actual.cols(), actual.rows()) / 8.0

        val actualBlur = smoothLargeScale(actual, sigma)
        val expectedBlur = smoothLargeScale(expected, sigma)

        val actualFloat = Mat()
        val expectedFloat = Mat()
        actualBlur.convertTo(actualFloat, CvType.CV_32F)
        expectedBlur.convertTo(expectedFloat, CvType.CV_32F)
        actualBlur.release()
        expectedBlur.release()

        // Keep the denominator away from zero on a very dark target.
        Core.add(expectedFloat, Scalar(1.0), expectedFloat)

        val gain = Mat()
        Core.divide(actualFloat, expectedFloat, gain)
        actualFloat.release()
        expectedFloat.release()

        // A gain far from 1 means the model and the photograph disagree about something structural,
        // not about lighting; clamping stops that becoming a huge false residual.
        Core.min(gain, Scalar(2.5), gain)
        Core.max(gain, Scalar(0.4), gain)

        val scaled = Mat()
        expected.convertTo(scaled, CvType.CV_32F)
        Core.multiply(scaled, gain, scaled)
        gain.release()

        val predicted = Mat()
        scaled.convertTo(predicted, CvType.CV_8U)
        scaled.release()
        return predicted
    }

    /**
     * Difference from the expected face, forgiving a small misregistration.
     *
     * A plain absolute difference demands that the synthetic target line up with the photograph to
     * the pixel, and it never does: printed ring line weight is not known, and the calibration is
     * good but not perfect. Those mismatches leave thin closed rings in the residual, which the fill
     * step then turns into discs - and at a large calibre a filled scoring ring is exactly the size
     * of a plausible bullet hole. That produced a phantom shot in the middle of every target.
     *
     * Comparing against the range of expected values within a couple of pixels removes the whole
     * class of problem: anything explainable by a slight shift produces no residual at all, while a
     * bullet hole, being far wider than the tolerance, still produces its full signal.
     */
    private fun residualWithTolerance(actual: Mat, predicted: Mat, pixelsPerMm: Double): Mat {
        val radius = (REGISTRATION_TOLERANCE_MM * pixelsPerMm).toInt().coerceIn(1, 3)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()),
        )

        val darkest = Mat()
        val brightest = Mat()
        Imgproc.erode(predicted, darkest, kernel)
        Imgproc.dilate(predicted, brightest, kernel)
        kernel.release()

        // Unsigned subtraction saturates at zero, which is exactly the one-sided difference wanted.
        val above = Mat()
        val below = Mat()
        Core.subtract(actual, brightest, above)
        Core.subtract(darkest, actual, below)
        darkest.release()
        brightest.release()

        val residual = Mat()
        Core.add(above, below, residual)
        above.release()
        below.release()
        return residual
    }

    /**
     * Heavy blur, done cheaply.
     *
     * A Gaussian wide enough to model lighting across a whole frame needs a kernel roughly as wide
     * as the image, and running that directly is quadratic enough to take minutes on a full
     * resolution photograph - it dominated everything else in this pipeline until it was measured.
     * Shrinking first, blurring by the same factor less, then scaling back gives a visually
     * identical field for a fraction of the work, which is all a lighting model needs.
     */
    private fun smoothLargeScale(source: Mat, sigma: Double): Mat {
        val factor = 16
        val width = (source.cols() / factor).coerceAtLeast(8)
        val height = (source.rows() / factor).coerceAtLeast(8)

        val small = Mat()
        Imgproc.resize(source, small, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), (sigma / factor).coerceAtLeast(1.0))

        val restored = Mat()
        Imgproc.resize(small, restored, source.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        return restored
    }

    internal fun bridgeGaps(binary: Mat, expectedDiameterPx: Double) {
        val size = (expectedDiameterPx * BRIDGE_KERNEL_FRACTION)
            .toInt()
            .coerceAtLeast(3)
            .let { if (it % 2 == 0) it + 1 else it }
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(size.toDouble(), size.toDouble()),
        )
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
    }

    /**
     * Fills regions enclosed by a curve, but only ones small enough to be bullet holes.
     *
     * This is the step that makes a hole in the black work. Its evidence is a thin ring of torn
     * paper with nothing inside; filling what the ring encloses turns it into the disc the crater
     * would have been if it had been visible. The size limit is what stops the same operation
     * flooding the inside of every printed scoring ring.
     */
    internal fun fillEnclosedHoles(binary: Mat, expectedDiameterPx: Double) {
        // Deliberately tighter than the candidate size band. On a face whose rings are unknown - a
        // custom target, or one photographed without a spec - an unmodelled ring line is still a
        // closed curve, and a generous limit here would flood its interior and lose every shot
        // inside it.
        val expectedArea = Math.PI * expectedDiameterPx * expectedDiameterPx / 4.0
        val maximumArea = expectedArea * MAX_FILLED_SHOTS

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binary,
            contours,
            hierarchy,
            Imgproc.RETR_CCOMP,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        for (index in contours.indices) {
            // A contour with a parent is the boundary of a gap inside a blob.
            val parent = hierarchy.get(0, index)?.getOrNull(3) ?: -1.0
            if (parent >= 0 && Imgproc.contourArea(contours[index]) <= maximumArea) {
                Imgproc.drawContours(binary, contours, index, Scalar(255.0), -1)
            }
        }

        hierarchy.release()
        contours.forEach { it.release() }
    }

    /**
     * Erases anything narrower than a hole.
     *
     * Printed ring lines, the edge of the aiming mark and any residual left by a slight mismatch
     * between the synthetic face and the real one are all thin. Opening removes them outright, which
     * matters most for a shot that landed on a ring line: the hole survives as a compact disc while
     * the line it was joined to disappears.
     */
    internal fun removeThinStructures(binary: Mat, expectedDiameterPx: Double) {
        val size = (expectedDiameterPx * THIN_STRUCTURE_FRACTION)
            .toInt()
            .coerceAtLeast(3)
            .let { if (it % 2 == 0) it + 1 else it }
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(size.toDouble(), size.toDouble()),
        )
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
        kernel.release()
    }

    internal data class Candidate(
        val enclosedArea: Double,
        val centroid: Point,
        val axisAngleRad: Double,
        val extentMin: Double,
        val extentMax: Double,
        /** The blob's boundary, copied out so its lifetime is not tied to the contour Mat. */
        val outline: List<Point> = emptyList(),
    )

    /**
     * External contours that could plausibly be holes.
     *
     * Working from the enclosed area of the contour, rather than from the lit pixels inside it, is
     * what makes a hole in the black work: the torn ring is a thin outline, but the region it
     * encloses is exactly the hole.
     */
    internal fun candidatesFrom(binary: Mat, expectedDiameterPx: Double): List<Candidate> {
        val contours = outerContours(binary)

        val expectedArea = Math.PI * expectedDiameterPx * expectedDiameterPx / 4.0
        val minimumArea = expectedArea * MIN_BLOB_AREA_FRACTION
        // Generous at the top so a genuine cluster survives; a printed scoring ring encloses orders
        // of magnitude more than this and is thrown out.
        val maximumArea = expectedArea * MAX_SHOTS_PER_BLOB * 2.0

        val candidates = ArrayList<Candidate>()
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minimumArea || area > maximumArea) {
                contour.release()
                continue
            }
            if (solidityOf(contour, area) < MIN_SOLIDITY) {
                contour.release()
                continue
            }
            if (!isHoleShaped(contour, area, expectedDiameterPx)) {
                contour.release()
                continue
            }
            candidates += describe(contour, area)
            contour.release()
        }
        return candidates
    }

    /**
     * The outer boundary of every connected blob, however deeply nested in the target's printing.
     *
     * Retrieval mode matters more here than it looks. `RETR_EXTERNAL` returns only top-level
     * contours, and on a scoring face the outermost printed ring encloses the entire target - so
     * every bullet hole is nested inside it and silently discarded, and the detector finds nothing
     * at all. `RETR_CCOMP` puts a component sitting inside another component's hole back at the top
     * level, which is exactly the relationship a bullet hole has to the rings around it. Keeping
     * only parentless contours then gives one boundary per blob, with no duplicate for the inside of
     * a torn ring.
     */
    private fun outerContours(binary: Mat): List<MatOfPoint> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binary,
            contours,
            hierarchy,
            Imgproc.RETR_CCOMP,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val outer = ArrayList<MatOfPoint>(contours.size)
        for (index in contours.indices) {
            val parent = hierarchy.get(0, index)?.getOrNull(3) ?: -1.0
            if (parent < 0) outer += contours[index] else contours[index].release()
        }
        hierarchy.release()
        return outer
    }

    /**
     * Whether the blob's shape is consistent with holes made by this calibre.
     *
     * Width alone used to decide this, and it cost the shooter their group. A tight group merges
     * into one blob that is several holes wide in *both* directions, and a ceiling on the short axis
     * rejects it outright - so the closer someone shoots, the less the app finds, which is the exact
     * opposite of useful. Eleven shots in a club target came back as one.
     *
     * What the ceiling was really for is printed line work: an arc of a scoring ring. That is thin
     * and long, so it fails on the short axis or on solidity. A clump of holes is wide but *full* -
     * it fills most of the box around it - and that is what separates the two.
     */
    private fun isHoleShaped(contour: MatOfPoint, area: Double, expectedDiameterPx: Double): Boolean {
        val points = MatOfPoint2f(*contour.toArray())
        val box = Imgproc.minAreaRect(points)
        points.release()

        val shortSide = min(box.size.width, box.size.height)
        val longSide = max(box.size.width, box.size.height)

        // Thinner than this in any direction and it is printing, not a shot.
        if (shortSide < expectedDiameterPx * MIN_BLOB_WIDTH_FACTOR) return false
        // Longer than a plausible clump could ever be: a ring, or several welded to one.
        if (longSide > expectedDiameterPx * MAX_BLOB_SPAN_FACTOR) return false
        // One hole, or a short chain of them touching end to end.
        if (shortSide <= expectedDiameterPx * MAX_BLOB_WIDTH_FACTOR) return true

        val boxArea = shortSide * longSide
        return boxArea > 0.0 && area / boxArea >= MIN_CLUSTER_FILL
    }

    /** Area over convex hull area. A fragment of ring line scores low; a hole scores near one. */
    private fun solidityOf(contour: MatOfPoint, area: Double): Double {
        val hullIndices = MatOfInt()
        Imgproc.convexHull(contour, hullIndices)

        val points = contour.toArray()
        val indices = hullIndices.toArray()
        hullIndices.release()
        if (indices.size < 3) return 0.0

        val hull = MatOfPoint(*indices.map { points[it] }.toTypedArray())
        val hullArea = Imgproc.contourArea(hull)
        hull.release()

        return if (hullArea <= 0.0) 0.0 else area / hullArea
    }

    /** Centroid and long axis of a contour, from its image moments. */
    private fun describe(contour: MatOfPoint, area: Double): Candidate {
        val moments = Imgproc.moments(contour)
        val centreX = if (moments.m00 != 0.0) moments.m10 / moments.m00 else 0.0
        val centreY = if (moments.m00 != 0.0) moments.m01 / moments.m00 else 0.0

        val axisAngle = 0.5 * atan2(2.0 * moments.mu11, moments.mu20 - moments.mu02)
        val axisX = cos(axisAngle)
        val axisY = sin(axisAngle)

        var minProjection = Double.MAX_VALUE
        var maxProjection = -Double.MAX_VALUE
        for (point in contour.toArray()) {
            val projection = (point.x - centreX) * axisX + (point.y - centreY) * axisY
            if (projection < minProjection) minProjection = projection
            if (projection > maxProjection) maxProjection = projection
        }

        return Candidate(
            enclosedArea = area,
            centroid = Point(centreX, centreY),
            axisAngleRad = axisAngle,
            extentMin = minProjection,
            extentMax = maxProjection,
            outline = contour.toArray().toList(),
        )
    }

    private fun toHoles(
        candidate: Candidate,
        residual: Mat,
        rectified: RectifiedImage,
        spec: TargetSpec,
        expectedDiameterPx: Double,
        marginMm: Double,
        threshold: Double,
    ): List<DetectedHole> {
        val expectedArea = Math.PI * expectedDiameterPx * expectedDiameterPx / 4.0
        val apparentArea = expectedArea * APPARENT_HOLE_AREA_FACTOR
        val shots = if (candidate.enclosedArea > apparentArea * SPLIT_AREA_FRACTION) {
            (candidate.enclosedArea / apparentArea).roundToInt().coerceIn(1, MAX_SHOTS_PER_BLOB)
        } else {
            1
        }

        // Peaks in the distance transform sit at the middle of each hole, wherever it is. Spreading
        // along the long axis - the only option before - put a two-dimensional group in a straight
        // line, which is wrong for the case that matters most: a tight group near the centre.
        val peaks = if (shots > 1) peaksWithin(candidate, expectedDiameterPx) else emptyList()
        val centres = when {
            shots == 1 -> listOf(candidate.centroid)
            peaks.size >= 2 -> peaks
            else -> spreadAlongAxis(candidate, shots)
        }
        val shotsFound = if (shots == 1) 1 else centres.size

        val measuredDiameter = if (shotsFound == 1) {
            2.0 * sqrt(candidate.enclosedArea / Math.PI)
        } else {
            expectedDiameterPx
        }

        val strength = residualStrength(residual, candidate.centroid, expectedDiameterPx / 2.0, threshold)
        val sizeMatch = sizeMatch(measuredDiameter, expectedDiameterPx)
        // Inferred positions claim less than measured ones.
        val clusterPenalty = if (shotsFound == 1) 1.0 else 0.6
        val limitMm = (spec.outerRadiusMm ?: Double.MAX_VALUE) + marginMm

        return centres.mapNotNull { centre ->
            val positionMm = rectified.toTargetMm(centre)
            if (positionMm.radius > limitMm) return@mapNotNull null

            DetectedHole(
                positionMm = positionMm,
                diameterMm = measuredDiameter * rectified.millimetresPerPixel,
                confidence = ((0.55 * sizeMatch + 0.45 * strength) * clusterPenalty)
                    .coerceIn(0.0, 1.0),
                pixelCentre = centre,
                pixelRadius = measuredDiameter / 2.0,
                shotsInBlob = shotsFound,
            )
        }
    }

    /**
     * How well a measured hole matches the expected size, with a flat band around the target.
     *
     * The band exists because a hole is genuinely bigger than its bullet - the torn rim is part of
     * what the camera sees - and by an amount that depends on the paper and the backing. Demanding
     * an exact match would mark every real hole down.
     */
    private fun sizeMatch(measuredDiameter: Double, expectedDiameter: Double): Double {
        if (expectedDiameter <= 0.0) return 0.0
        val error = abs(measuredDiameter - expectedDiameter) / expectedDiameter
        if (error <= SIZE_TOLERANCE) return 1.0
        return (1.0 - (error - SIZE_TOLERANCE) / (1.0 - SIZE_TOLERANCE)).coerceIn(0.0, 1.0)
    }

    /**
     * Centres of the holes inside one blob, found as peaks in its distance transform.
     *
     * Every point inside a blob is scored by how far it is from the edge; the middle of each hole is
     * a local maximum of that, and stays one even when its neighbour overlaps it. Reading the peaks
     * back gives the holes where they actually are, in two dimensions, and gives their number as a
     * side effect - which is better evidence than dividing the blob's area by one hole's.
     *
     * Returns an empty list when the blob has no interior worth transforming, leaving the caller to
     * fall back on the area estimate.
     */
    internal fun peaksWithin(candidate: Candidate, expectedDiameterPx: Double): List<Point> {
        if (candidate.outline.size < 3) return emptyList()

        val pad = 2
        val minX = candidate.outline.minOf { it.x }.toInt() - pad
        val minY = candidate.outline.minOf { it.y }.toInt() - pad
        val width = (candidate.outline.maxOf { it.x }.toInt() - minX) + pad + 1
        val height = (candidate.outline.maxOf { it.y }.toInt() - minY) + pad + 1
        if (width <= 0 || height <= 0) return emptyList()

        val mask = Mat.zeros(height, width, CvType.CV_8U)
        val shifted = MatOfPoint(
            *candidate.outline.map { Point(it.x - minX, it.y - minY) }.toTypedArray(),
        )
        Imgproc.drawContours(mask, listOf(shifted), -1, Scalar(255.0), -1)
        shifted.release()

        val distance = Mat()
        Imgproc.distanceTransform(mask, distance, Imgproc.DIST_L2, 3)
        mask.release()

        // A peak is a pixel no lower than anything nearby. Comparing against a dilation is the
        // cheap way to ask that of every pixel at once.
        val kernelSize = (expectedDiameterPx * PEAK_NEIGHBOURHOOD_FRACTION)
            .toInt().coerceAtLeast(3).let { if (it % 2 == 0) it + 1 else it }
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(kernelSize.toDouble(), kernelSize.toDouble()),
        )
        val dilated = Mat()
        Imgproc.dilate(distance, dilated, kernel)
        kernel.release()

        val minimumDistance = expectedDiameterPx * MIN_PEAK_DISTANCE_FRACTION
        val found = ArrayList<Triple<Double, Int, Int>>()
        val distanceRow = FloatArray(width)
        val dilatedRow = FloatArray(width)
        for (y in 0 until height) {
            distance.get(y, 0, distanceRow)
            dilated.get(y, 0, dilatedRow)
            for (x in 0 until width) {
                val value = distanceRow[x].toDouble()
                if (value >= minimumDistance && value >= dilatedRow[x] - 1e-4) {
                    found += Triple(value, x, y)
                }
            }
        }
        distance.release()
        dilated.release()

        // A plateau produces a run of equal peaks; keep the strongest and drop anything within one
        // hole's width of an accepted one.
        val separation = expectedDiameterPx * MIN_PEAK_SEPARATION_FRACTION
        val accepted = ArrayList<Point>()
        for ((_, x, y) in found.sortedByDescending { it.first }) {
            val point = Point((x + minX).toDouble(), (y + minY).toDouble())
            if (accepted.none { hypotenuse(it, point) < separation }) accepted += point
            if (accepted.size >= MAX_SHOTS_PER_BLOB) break
        }
        return accepted
    }

    private fun hypotenuse(a: Point, b: Point): Double = sqrt(
        (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y),
    )

    /** Places [count] centres evenly along a blob's long axis. */
    internal fun spreadAlongAxis(candidate: Candidate, count: Int): List<Point> {
        val axisX = cos(candidate.axisAngleRad)
        val axisY = sin(candidate.axisAngleRad)
        val span = candidate.extentMax - candidate.extentMin
        if (span <= 0.0) return List(count) { candidate.centroid }

        return (0 until count).map { index ->
            val offset = candidate.extentMin + span * (index + 0.5) / count
            Point(
                candidate.centroid.x + axisX * offset,
                candidate.centroid.y + axisY * offset,
            )
        }
    }

    /**
     * How strongly the pixels under a candidate differ from the clean target, relative to the
     * threshold that let them through. Weak evidence lowers confidence rather than causing a
     * rejection, so the review screen can show it and let the user decide.
     */
    private fun residualStrength(
        residual: Mat,
        centre: Point,
        radius: Double,
        threshold: Double,
    ): Double {
        val half = max(1.0, radius * 0.7)
        val left = (centre.x - half).toInt().coerceIn(0, residual.cols() - 1)
        val right = (centre.x + half).toInt().coerceIn(0, residual.cols() - 1)
        val top = (centre.y - half).toInt().coerceIn(0, residual.rows() - 1)
        val bottom = (centre.y + half).toInt().coerceIn(0, residual.rows() - 1)
        if (right <= left || bottom <= top) return 0.0

        val patch = residual.submat(top, bottom + 1, left, right + 1)
        val mean = Core.mean(patch).`val`[0]
        patch.release()

        // Twice the threshold is taken as unambiguous.
        return (mean / (threshold * 2.0)).coerceIn(0.0, 1.0)
    }

    /**
     * Guesses hole size when the calibre is unknown, from the typical size of plausible blobs.
     *
     * Everything the target has printed on it is either far too small to be a hole (speckle) or far
     * too large (scoring rings), so the median of what is left is a decent estimate. Being told the
     * calibre is much better, which is why [DetectionResult.calibreKnown] is reported.
     */
    private fun estimateHoleDiameter(binary: Mat, rectified: RectifiedImage): Double {
        val contours = outerContours(binary)

        // Anything from a small airgun pellet to a shotgun slug, in pixels.
        val smallest = 3.0 * rectified.pixelsPerMm
        val largest = 20.0 * rectified.pixelsPerMm
        val minimumArea = Math.PI * smallest * smallest / 4.0
        val maximumArea = Math.PI * largest * largest / 4.0

        val diameters = ArrayList<Double>()
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area in minimumArea..maximumArea && solidityOf(contour, area) >= MIN_SOLIDITY) {
                diameters += 2.0 * sqrt(area / Math.PI)
            }
            contour.release()
        }

        if (diameters.isEmpty()) return 0.0
        diameters.sort()
        return diameters[diameters.size / 2]
    }

    private fun emptyResult(
        expectedDiameterPx: Double,
        levels: ExpectedTarget.Levels,
        caliber: Caliber?,
    ) = DetectionResult(
        holes = emptyList(),
        expectedHoleDiameterPx = expectedDiameterPx,
        paperLevel = levels.paper,
        inkLevel = levels.ink,
        calibreKnown = caliber != null,
    )

    /** Cuts a square thumbnail around a hole, for the review screen and the live shot list. */
    fun cropAround(rectified: RectifiedImage, hole: DetectedHole, sizePx: Int = 128): Mat {
        val half = sizePx / 2
        val left = (hole.pixelCentre.x - half).toInt()
        val top = (hole.pixelCentre.y - half).toInt()

        val fitsWholly = left >= 0 && top >= 0 &&
            left + sizePx <= rectified.image.cols() &&
            top + sizePx <= rectified.image.rows()

        if (fitsWholly) {
            return rectified.image.submat(top, top + sizePx, left, left + sizePx).clone()
        }

        // A hole near the edge of the frame still deserves a thumbnail; pad the missing part.
        val padded = Mat(sizePx, sizePx, rectified.image.type(), Scalar(255.0))
        val sourceLeft = left.coerceIn(0, max(0, rectified.image.cols() - 1))
        val sourceTop = top.coerceIn(0, max(0, rectified.image.rows() - 1))
        val width = min(sizePx, rectified.image.cols() - sourceLeft)
        val height = min(sizePx, rectified.image.rows() - sourceTop)
        if (width <= 0 || height <= 0) return padded

        val source = rectified.image.submat(sourceTop, sourceTop + height, sourceLeft, sourceLeft + width)
        source.copyTo(padded.submat(0, height, 0, width))
        source.release()
        return padded
    }
}
