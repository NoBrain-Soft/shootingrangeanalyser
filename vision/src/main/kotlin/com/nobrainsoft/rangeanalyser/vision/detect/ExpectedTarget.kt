package com.nobrainsoft.rangeanalyser.vision.detect

import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.vision.calibration.RectifiedImage
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Builds the picture the target *should* look like, so the real one can be subtracted from it.
 *
 * This is what makes holes findable inside the black aiming mark. A dark crater on dark ink has
 * almost no contrast of its own; against a synthetic version of the same face, both the crater and
 * the ring of torn paper around it stand out as differences.
 *
 * Only the aiming mark is drawn, not the ring lines. Lines are a few pixels wide and the detector's
 * morphology removes them anyway, whereas trying to reproduce their exact printed weight would add
 * a new way to be wrong.
 */
object ExpectedTarget {

    /** How light the paper is and how dark the ink is, measured from the photograph itself. */
    data class Levels(val paper: Double, val ink: Double) {
        val contrast: Double get() = (paper - ink).coerceAtLeast(0.0)

        val hasAimingMark: Boolean get() = contrast > 20.0
    }

    /**
     * Reads paper and ink brightness off the image rather than assuming them.
     *
     * Percentiles, not means: bullet holes are dark and would drag an average of the paper down,
     * and the torn paper inside the black would drag the ink level up.
     */
    fun estimateLevels(rectified: RectifiedImage, spec: TargetSpec): Levels {
        val blackRadiusPx = spec.blackDiameterMm?.let { it / 2.0 * rectified.pixelsPerMm }

        if (blackRadiusPx == null || blackRadiusPx < 4.0) {
            val paper = percentileOfMask(rectified.image, null, 0.75)
            return Levels(paper = paper, ink = paper)
        }

        val inside = circleMask(rectified, blackRadiusPx * 0.75, inverted = false)
        val outside = circleMask(rectified, blackRadiusPx * 1.35, inverted = true)

        // Paper is the bright end of what is outside the mark; ink the dark end of what is inside.
        val paper = percentileOfMask(rectified.image, outside, 0.75)
        val ink = percentileOfMask(rectified.image, inside, 0.25)

        inside.release()
        outside.release()

        return Levels(paper = paper, ink = kotlin.math.min(ink, paper))
    }

    /** Renders the clean face at the rectified image's scale and position. */
    fun render(rectified: RectifiedImage, spec: TargetSpec, levels: Levels): Mat {
        val expected = Mat(
            rectified.image.rows(),
            rectified.image.cols(),
            CvType.CV_8UC1,
            Scalar(levels.paper),
        )

        val blackRadiusPx = spec.blackDiameterMm?.let { it / 2.0 * rectified.pixelsPerMm }
        if (blackRadiusPx != null && blackRadiusPx >= 2.0 && levels.hasAimingMark) {
            Imgproc.circle(
                expected,
                rectified.centre,
                blackRadiusPx.toInt(),
                Scalar(levels.ink),
                -1,
                Imgproc.LINE_AA,
            )
        }

        drawRingLines(expected, rectified, spec, levels, blackRadiusPx)
        return expected
    }

    /**
     * Draws the scoring rings onto the expected face.
     *
     * These matter more than their thinness suggests, and the reason is specific: the rings printed
     * *inside* the aiming mark are white on black, so leaving them out puts a full-contrast circle
     * into the residual. The innermost of those encloses little enough area to look like a cluster
     * of holes, and filling it swallows any shot near the middle of the target - which is where the
     * good ones are.
     *
     * The printed line weight is not known, so a nominal weight is used and the resulting mismatch
     * is a band a pixel or two wide. That is far thinner than a bullet hole and the detector's
     * opening step removes it.
     */
    private fun drawRingLines(
        expected: Mat,
        rectified: RectifiedImage,
        spec: TargetSpec,
        levels: Levels,
        blackRadiusPx: Double?,
    ) {
        val rings = spec.rings ?: return
        val thickness = (RING_LINE_WIDTH_MM * rectified.pixelsPerMm).toInt().coerceAtLeast(1)

        for (ring in rings.rings) {
            val radiusPx = ring.radiusMm * rectified.pixelsPerMm
            if (radiusPx < 2.0) continue
            if (radiusPx > kotlin.math.hypot(expected.cols() / 2.0, expected.rows() / 2.0)) continue

            // Inside the aiming mark the lines are printed white so they stay readable on the ink.
            val onInk = blackRadiusPx != null && levels.hasAimingMark && radiusPx <= blackRadiusPx
            Imgproc.circle(
                expected,
                rectified.centre,
                radiusPx.toInt(),
                Scalar(if (onInk) levels.paper else levels.ink),
                thickness,
                Imgproc.LINE_AA,
            )
        }
    }

    /** Nominal printed ring line weight. Real targets vary; the mismatch is handled downstream. */
    private const val RING_LINE_WIDTH_MM = 0.3

    private fun circleMask(rectified: RectifiedImage, radiusPx: Double, inverted: Boolean): Mat {
        val mask = Mat(
            rectified.image.rows(),
            rectified.image.cols(),
            CvType.CV_8UC1,
            Scalar(if (inverted) 255.0 else 0.0),
        )
        Imgproc.circle(
            mask,
            rectified.centre,
            radiusPx.toInt().coerceAtLeast(1),
            Scalar(if (inverted) 0.0 else 255.0),
            -1,
        )
        return mask
    }

    /**
     * Value below which the given fraction of the masked pixels fall.
     *
     * Built from a 256-bin histogram, which is exact for 8-bit data and avoids sorting millions of
     * pixels.
     */
    private fun percentileOfMask(image: Mat, mask: Mat?, fraction: Double): Double {
        val histogram = IntArray(256)
        var total = 0L

        val row = ByteArray(image.cols())
        val maskRow = mask?.let { ByteArray(it.cols()) }

        for (y in 0 until image.rows()) {
            image.get(y, 0, row)
            maskRow?.let { mask.get(y, 0, it) }
            for (x in row.indices) {
                if (maskRow != null && maskRow[x].toInt() == 0) continue
                histogram[row[x].toInt() and 0xFF]++
                total++
            }
        }

        if (total == 0L) return 128.0

        val target = (total * fraction).toLong().coerceAtLeast(1L)
        var running = 0L
        for (value in 0..255) {
            running += histogram[value]
            if (running >= target) return value.toDouble()
        }
        return 255.0
    }
}
