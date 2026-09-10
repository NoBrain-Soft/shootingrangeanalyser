package com.nobrainsoft.rangeanalyser.vision.calibration

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Warps a photographed target into a flat, square-on view at a known scale.
 *
 * Detection runs on the rectified image rather than the original for a practical reason: once every
 * millimetre is the same number of pixels everywhere, a bullet hole is the same size everywhere too,
 * and the detector can use one fixed kernel instead of guessing at scale per region.
 */
object Rectifier {

    /** Ten pixels to the millimetre resolves a 4.5 mm pellet hole into a 45 px disc. */
    const val DEFAULT_PIXELS_PER_MM = 10.0

    /** Beyond this the rectified image gets unreasonably large; scale is reduced to fit. */
    private const val MAX_DIMENSION_PX = 4000

    fun rectify(
        image: Mat,
        imageToTargetMm: Transform2d,
        spec: TargetSpec,
        marginMm: Double = 10.0,
        pixelsPerMm: Double = DEFAULT_PIXELS_PER_MM,
    ): RectifiedImage? {
        val radiusMm = spec.outerRadiusMm ?: return null
        val halfExtent = radiusMm + marginMm
        return rectify(image, imageToTargetMm, halfExtent, halfExtent, pixelsPerMm)
    }

    fun rectify(
        image: Mat,
        imageToTargetMm: Transform2d,
        halfWidthMm: Double,
        halfHeightMm: Double,
        pixelsPerMm: Double = DEFAULT_PIXELS_PER_MM,
    ): RectifiedImage? {
        if (halfWidthMm <= 0.0 || halfHeightMm <= 0.0 || pixelsPerMm <= 0.0) return null

        val scale = fitScale(halfWidthMm, halfHeightMm, pixelsPerMm)
        val width = (2 * halfWidthMm * scale).toInt().coerceAtLeast(1)
        val height = (2 * halfHeightMm * scale).toInt().coerceAtLeast(1)
        val centre = Point(width / 2.0, height / 2.0)

        // Millimetres to rectified pixels: centre the origin, and flip y back to image convention.
        val millimetresToPixels = Transform2d.translation(centre.x, centre.y) *
            Transform2d.scale(scale, -scale)
        val imageToRectified = millimetresToPixels * imageToTargetMm

        val output = Mat()
        val matrix = imageToRectified.toMat()
        Imgproc.warpPerspective(
            image,
            output,
            matrix,
            Size(width.toDouble(), height.toDouble()),
            Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE,
            org.opencv.core.Scalar(255.0),
        )
        matrix.release()

        return RectifiedImage(
            image = output,
            pixelsPerMm = scale,
            centre = centre,
            imageToRectified = imageToRectified,
        )
    }

    private fun fitScale(halfWidthMm: Double, halfHeightMm: Double, requested: Double): Double {
        val largestSideMm = 2 * maxOf(halfWidthMm, halfHeightMm)
        val requestedPx = largestSideMm * requested
        if (requestedPx <= MAX_DIMENSION_PX) return requested
        return MAX_DIMENSION_PX / largestSideMm
    }
}

/**
 * A target rendered flat and square on, with a known pixels-per-millimetre everywhere.
 */
data class RectifiedImage(
    val image: Mat,
    val pixelsPerMm: Double,
    /** Where the target centre landed in the rectified image. */
    val centre: Point,
    /** Original image pixels to rectified pixels. */
    val imageToRectified: Transform2d,
) {
    val millimetresPerPixel: Double get() = 1.0 / pixelsPerMm

    fun toTargetMm(pixel: Point): PointMm = PointMm(
        x = (pixel.x - centre.x) / pixelsPerMm,
        y = (centre.y - pixel.y) / pixelsPerMm,
    )

    fun toPixel(millimetres: PointMm): Point = Point(
        centre.x + millimetres.x * pixelsPerMm,
        centre.y - millimetres.y * pixelsPerMm,
    )

    /** How many pixels across a hole of this calibre should be. Sizes the detector's kernels. */
    fun expectedHoleDiameterPx(bulletDiameterMm: Double): Double = bulletDiameterMm * pixelsPerMm

    fun release() {
        image.release()
    }
}
