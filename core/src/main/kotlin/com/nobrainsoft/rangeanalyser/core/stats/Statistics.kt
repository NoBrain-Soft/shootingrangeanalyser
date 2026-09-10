package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Small statistical helpers shared by the group, comparison and coaching layers. */
object Statistics {

    fun mean(values: List<Double>): Double {
        require(values.isNotEmpty()) { "mean of an empty sample" }
        return values.sum() / values.size
    }

    /** Sample standard deviation, with the n-1 denominator. Zero for a single value. */
    fun sampleStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        val sumSquares = values.sumOf { (it - m) * (it - m) }
        return sqrt(sumSquares / (values.size - 1))
    }

    fun median(values: List<Double>): Double = percentile(values, 0.5)

    /**
     * Linearly interpolated percentile, [fraction] in 0..1.
     */
    fun percentile(values: List<Double>, fraction: Double): Double {
        require(values.isNotEmpty()) { "percentile of an empty sample" }
        require(fraction in 0.0..1.0) { "fraction must be in 0..1, was $fraction" }

        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]

        val position = fraction * (sorted.size - 1)
        val lowerIndex = position.toInt()
        val upperIndex = minOf(lowerIndex + 1, sorted.lastIndex)
        val weight = position - lowerIndex
        return sorted[lowerIndex] * (1 - weight) + sorted[upperIndex] * weight
    }

    /** Least-squares slope and intercept of y on x, plus how much of the variance it explains. */
    fun linearRegression(x: List<Double>, y: List<Double>): LinearFit {
        require(x.size == y.size) { "x and y must be the same length" }
        require(x.size >= 2) { "need at least two points to fit a line" }

        val meanX = mean(x)
        val meanY = mean(y)
        var covariance = 0.0
        var varianceX = 0.0
        for (i in x.indices) {
            covariance += (x[i] - meanX) * (y[i] - meanY)
            varianceX += (x[i] - meanX) * (x[i] - meanX)
        }
        if (varianceX == 0.0) return LinearFit(0.0, meanY, 0.0)

        val slope = covariance / varianceX
        val intercept = meanY - slope * meanX

        val totalSumSquares = y.sumOf { (it - meanY) * (it - meanY) }
        val residualSumSquares = x.indices.sumOf {
            val predicted = slope * x[it] + intercept
            (y[it] - predicted) * (y[it] - predicted)
        }
        val rSquared = if (totalSumSquares == 0.0) 0.0 else 1.0 - residualSumSquares / totalSumSquares

        return LinearFit(slope, intercept, rSquared)
    }

    /**
     * 2x2 covariance of a set of points, with the n-1 denominator.
     */
    fun covariance(points: List<PointMm>): Covariance2x2 {
        if (points.size < 2) return Covariance2x2(0.0, 0.0, 0.0)

        val meanX = mean(points.map { it.x })
        val meanY = mean(points.map { it.y })
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (point in points) {
            val dx = point.x - meanX
            val dy = point.y - meanY
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        val denominator = (points.size - 1).toDouble()
        return Covariance2x2(xx / denominator, yy / denominator, xy / denominator)
    }

    /**
     * Chi-square critical value with two degrees of freedom.
     *
     * Closed form for k=2: the CDF is 1 - exp(-x/2), so the inverse is -2 ln(1-p). No table needed,
     * and it is exact.
     */
    fun chiSquare2dfCritical(probability: Double): Double {
        require(probability in 0.0..1.0)
        return -2.0 * ln(1.0 - probability)
    }

    /**
     * Quantile of the F distribution with **two** numerator degrees of freedom.
     *
     * F(2, d) has the closed form CDF `1 - (1 + 2f/d)^(-d/2)`, so its inverse needs no table and no
     * iteration. Two numerator degrees of freedom is exactly the case a 2D shot group produces,
     * which is a convenient accident.
     */
    fun fQuantile2Numerator(probability: Double, denominatorDf: Int): Double {
        require(probability in 0.0..1.0) { "probability must be in 0..1, was $probability" }
        require(denominatorDf > 0) { "need at least one denominator degree of freedom" }

        val d = denominatorDf.toDouble()
        return (d / 2.0) * (Math.pow(1.0 - probability, -2.0 / d) - 1.0)
    }

    /** Standard normal sample via Box-Muller. */
    fun nextGaussian(random: Random): Double {
        var u1 = random.nextDouble()
        while (u1 <= 0.0) u1 = random.nextDouble()
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}

data class LinearFit(val slope: Double, val intercept: Double, val rSquared: Double)

/** Covariance of a 2D point set. */
@Serializable
data class Covariance2x2(val xx: Double, val yy: Double, val xy: Double) {
    /**
     * Eigen-decomposition, giving the group's true spread axes.
     *
     * This is what separates "stringing vertically" from "stringing diagonally": a group can look
     * round in x and y while actually being an ellipse tilted at 45 degrees.
     */
    fun principalAxes(): PrincipalAxes {
        val difference = xx - yy
        val discriminant = sqrt(difference * difference + 4.0 * xy * xy)
        val majorVariance = (xx + yy + discriminant) / 2.0
        val minorVariance = (xx + yy - discriminant) / 2.0

        // Angle of the major axis, measured clockwise from vertical to match PointMm bearings.
        val axisAngleFromXAxis = 0.5 * atan2(2.0 * xy, difference)
        val bearing = normaliseBearing(90.0 - Math.toDegrees(axisAngleFromXAxis))

        return PrincipalAxes(
            majorSigmaMm = sqrt(majorVariance.coerceAtLeast(0.0)),
            minorSigmaMm = sqrt(minorVariance.coerceAtLeast(0.0)),
            majorAxisBearingDeg = bearing,
        )
    }

    /** Inverse, used for Mahalanobis distance. Null when the matrix is singular. */
    fun inverse(): Covariance2x2? {
        val determinant = xx * yy - xy * xy
        if (determinant <= 0.0 || !determinant.isFinite()) return null
        return Covariance2x2(xx = yy / determinant, yy = xx / determinant, xy = -xy / determinant)
    }

    /** Cholesky factor, used to simulate groups with this covariance. */
    fun choleskyOrNull(): Cholesky2x2? {
        if (xx <= 0.0) return null
        val l11 = sqrt(xx)
        val l21 = xy / l11
        val remainder = yy - l21 * l21
        if (remainder < 0.0) return null
        return Cholesky2x2(l11, l21, sqrt(remainder))
    }

    private fun normaliseBearing(degrees: Double): Double {
        // An axis has no head or tail, so only its direction modulo 180 is meaningful.
        var bearing = degrees % 180.0
        if (bearing < 0) bearing += 180.0
        return bearing
    }
}

@Serializable
data class PrincipalAxes(
    val majorSigmaMm: Double,
    val minorSigmaMm: Double,
    /** Bearing of the long axis in degrees, clockwise from vertical, in 0..180. */
    val majorAxisBearingDeg: Double,
) {
    /** 1.0 is perfectly round; larger means more elongated. */
    val elongation: Double
        get() = if (minorSigmaMm <= 0.0) Double.POSITIVE_INFINITY else majorSigmaMm / minorSigmaMm
}

data class Cholesky2x2(val l11: Double, val l21: Double, val l22: Double) {
    fun transform(z1: Double, z2: Double): PointMm = PointMm(l11 * z1, l21 * z1 + l22 * z2)
}

/** A range of plausible values for a statistic. */
@Serializable
data class ConfidenceInterval(
    val lower: Double,
    val upper: Double,
    val level: Double = 0.95,
) {
    val width: Double get() = upper - lower

    fun contains(value: Double): Boolean = value in lower..upper
}

/** Rotates a unit vector by [bearingDeg] clockwise from vertical. */
internal fun bearingToVector(bearingDeg: Double): PointMm {
    val radians = Math.toRadians(bearingDeg)
    return PointMm(sin(radians), cos(radians))
}
