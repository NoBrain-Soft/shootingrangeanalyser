package com.nobrainsoft.rangeanalyser.vision.geometry

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point

/**
 * A 3x3 projective transform, stored row-major.
 *
 * Kept as plain doubles rather than as an OpenCV `Mat` so it can be composed, inverted and
 * serialised without native allocation - transforms are held on profiles and passed across module
 * boundaries far more often than they are handed to OpenCV.
 */
data class Transform2d(val values: DoubleArray) {

    init {
        require(values.size == 9) { "a 3x3 transform needs 9 values, got ${values.size}" }
    }

    operator fun get(row: Int, column: Int): Double = values[row * 3 + column]

    /** Applies the transform to a point, dividing through by the homogeneous coordinate. */
    fun apply(x: Double, y: Double): Pair<Double, Double> {
        val w = this[2, 0] * x + this[2, 1] * y + this[2, 2]
        val safeW = if (w == 0.0 || !w.isFinite()) 1e-12 else w
        return Pair(
            (this[0, 0] * x + this[0, 1] * y + this[0, 2]) / safeW,
            (this[1, 0] * x + this[1, 1] * y + this[1, 2]) / safeW,
        )
    }

    fun applyToPoint(point: Point): Point {
        val (x, y) = apply(point.x, point.y)
        return Point(x, y)
    }

    /** Image pixels to target millimetres. */
    fun toTargetMm(point: Point): PointMm {
        val (x, y) = apply(point.x, point.y)
        return PointMm(x, y)
    }

    /** Target millimetres back to image pixels. */
    fun toImagePoint(point: PointMm): Point {
        val (x, y) = apply(point.x, point.y)
        return Point(x, y)
    }

    operator fun times(other: Transform2d): Transform2d {
        val result = DoubleArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                var sum = 0.0
                for (k in 0..2) {
                    sum += this[row, k] * other[k, column]
                }
                result[row * 3 + column] = sum
            }
        }
        return Transform2d(result)
    }

    fun inverse(): Transform2d? {
        val a = values
        val cofactor00 = a[4] * a[8] - a[5] * a[7]
        val cofactor01 = a[5] * a[6] - a[3] * a[8]
        val cofactor02 = a[3] * a[7] - a[4] * a[6]

        val determinant = a[0] * cofactor00 + a[1] * cofactor01 + a[2] * cofactor02
        if (determinant == 0.0 || !determinant.isFinite()) return null

        val inverted = doubleArrayOf(
            cofactor00,
            a[2] * a[7] - a[1] * a[8],
            a[1] * a[5] - a[2] * a[4],
            cofactor01,
            a[0] * a[8] - a[2] * a[6],
            a[2] * a[3] - a[0] * a[5],
            cofactor02,
            a[1] * a[6] - a[0] * a[7],
            a[0] * a[4] - a[1] * a[3],
        )
        for (index in inverted.indices) inverted[index] /= determinant
        return Transform2d(inverted)
    }

    /**
     * Millimetres per pixel implied by this transform, measured at the target centre.
     *
     * A projective transform has no single scale factor, so this is the local scale where it
     * matters most. It is the number the UI shows and the detector sizes its kernels with.
     */
    fun millimetresPerPixelAtCentre(): Double {
        val determinant =
            this[0, 0] * this[1, 1] - this[0, 1] * this[1, 0]
        return kotlin.math.sqrt(kotlin.math.abs(determinant))
    }

    fun toMat(): Mat {
        val mat = Mat(3, 3, CvType.CV_64F)
        mat.put(0, 0, *values)
        return mat
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Transform2d && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        val IDENTITY = Transform2d(
            doubleArrayOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            ),
        )

        fun of(values: List<Double>): Transform2d = Transform2d(values.toDoubleArray())

        fun translation(dx: Double, dy: Double) = Transform2d(
            doubleArrayOf(
                1.0, 0.0, dx,
                0.0, 1.0, dy,
                0.0, 0.0, 1.0,
            ),
        )

        fun scale(sx: Double, sy: Double) = Transform2d(
            doubleArrayOf(
                sx, 0.0, 0.0,
                0.0, sy, 0.0,
                0.0, 0.0, 1.0,
            ),
        )

        /** Rotation by [degrees] anticlockwise in image coordinates. */
        fun rotation(degrees: Double): Transform2d {
            val radians = Math.toRadians(degrees)
            val cos = kotlin.math.cos(radians)
            val sin = kotlin.math.sin(radians)
            return Transform2d(
                doubleArrayOf(
                    cos, -sin, 0.0,
                    sin, cos, 0.0,
                    0.0, 0.0, 1.0,
                ),
            )
        }

        fun fromMat(mat: Mat): Transform2d {
            require(mat.rows() == 3 && mat.cols() == 3) { "expected a 3x3 matrix" }
            val values = DoubleArray(9)
            val converted = Mat()
            mat.convertTo(converted, CvType.CV_64F)
            converted.get(0, 0, values)
            converted.release()
            return Transform2d(values)
        }
    }
}
