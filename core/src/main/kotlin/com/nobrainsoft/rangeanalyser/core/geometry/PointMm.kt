package com.nobrainsoft.rangeanalyser.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A position on the target face in millimetres.
 *
 * Origin is the target centre, +x right, +y **up**. This is the single coordinate convention that
 * crosses module boundaries: `:vision` converts out of pixels before returning anything, and `:app`
 * flips y when it draws. Keeping y up means the maths reads like the range does - a shot at
 * y = +20 is 20 mm high.
 */
@Serializable
data class PointMm(val x: Double, val y: Double) {
    val radius: Double get() = hypot(x, y)

    operator fun plus(other: PointMm) = PointMm(x + other.x, y + other.y)

    operator fun minus(other: PointMm) = PointMm(x - other.x, y - other.y)

    operator fun times(scale: Double) = PointMm(x * scale, y * scale)

    operator fun div(divisor: Double) = PointMm(x / divisor, y / divisor)

    fun distanceTo(other: PointMm): Double = hypot(x - other.x, y - other.y)

    /**
     * Bearing in degrees clockwise from straight up, in [0, 360).
     *
     * Straight up is 0, right is 90 - the same sense as a clock face, which is how shooters
     * describe shot placement.
     */
    fun bearingDegrees(): Double {
        val degrees = Math.toDegrees(atan2(x, y))
        return if (degrees < 0) degrees + 360.0 else degrees
    }

    /**
     * Position as a clock hour in 1..12, the way a spotter calls it ("your flyer is at 2 o'clock").
     *
     * Dead centre has no meaningful direction, so it returns null.
     */
    fun clockPosition(deadZoneMm: Double = 0.5): Int? {
        if (radius <= deadZoneMm) return null
        val hour = (bearingDegrees() / 30.0).roundToInt()
        return if (hour == 0) 12 else hour
    }

    companion object {
        val ORIGIN = PointMm(0.0, 0.0)
    }
}

/** Centroid of a set of points; the group's "centre of impact". */
fun List<PointMm>.centroid(): PointMm {
    require(isNotEmpty()) { "cannot take the centroid of an empty group" }
    var sumX = 0.0
    var sumY = 0.0
    for (point in this) {
        sumX += point.x
        sumY += point.y
    }
    return PointMm(sumX / size, sumY / size)
}
