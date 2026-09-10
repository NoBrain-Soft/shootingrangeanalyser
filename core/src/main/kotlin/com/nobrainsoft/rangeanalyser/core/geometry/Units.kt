package com.nobrainsoft.rangeanalyser.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.tan

/**
 * Unit conversions used throughout the analysis engine.
 *
 * Everything internal to the app is in millimetres and metres; imperial and angular units exist
 * only at the presentation edge. Angular conversions need a distance because MOA and mil describe
 * an *angle* - "a 20 mm group" means something very different at 10 m and at 300 m.
 */
object Units {
    const val MM_PER_INCH = 25.4
    const val MM_PER_METRE = 1000.0
    const val METRES_PER_YARD = 0.9144
    const val GRAMS_PER_GRAIN = 0.06479891
    const val MPS_PER_FPS = 0.3048

    /** One minute of angle, in radians. */
    private val MOA_RADIANS = Math.toRadians(1.0 / 60.0)

    /**
     * Millimetres subtended by one MOA at [distanceM].
     *
     * Uses the true trigonometric definition (~29.089 mm at 100 m), not the "1 inch at 100 yards"
     * shorthand (~29.1 mm), because sight-click advice compounds the error over many clicks.
     */
    fun mmPerMoaAt(distanceM: Double): Double {
        requirePositiveDistance(distanceM)
        return 2.0 * distanceM * MM_PER_METRE * tan(MOA_RADIANS / 2.0)
    }

    /**
     * Millimetres subtended by one milliradian at [distanceM].
     *
     * A mil is 1/1000 rad, so at d metres it subtends d millimetres exactly - 100 mm at 100 m.
     */
    fun mmPerMilAt(distanceM: Double): Double {
        requirePositiveDistance(distanceM)
        return distanceM
    }

    fun mmToMoa(mm: Double, distanceM: Double): Double = mm / mmPerMoaAt(distanceM)

    fun moaToMm(moa: Double, distanceM: Double): Double = moa * mmPerMoaAt(distanceM)

    fun mmToMil(mm: Double, distanceM: Double): Double = mm / mmPerMilAt(distanceM)

    fun milToMm(mil: Double, distanceM: Double): Double = mil * mmPerMilAt(distanceM)

    fun mmToInch(mm: Double): Double = mm / MM_PER_INCH

    fun inchToMm(inch: Double): Double = inch * MM_PER_INCH

    fun yardsToMetres(yards: Double): Double = yards * METRES_PER_YARD

    fun metresToYards(metres: Double): Double = metres / METRES_PER_YARD

    fun grainsToGrams(grains: Double): Double = grains * GRAMS_PER_GRAIN

    fun gramsToGrains(grams: Double): Double = grams / GRAMS_PER_GRAIN

    fun fpsToMps(fps: Double): Double = fps * MPS_PER_FPS

    fun mpsToFps(mps: Double): Double = mps / MPS_PER_FPS

    private fun requirePositiveDistance(distanceM: Double) {
        require(distanceM > 0.0) { "distance must be positive to convert angular units, was $distanceM" }
    }
}

/** How the user wants lengths shown. Analysis is always metric internally. */
@Serializable
enum class LengthUnit { MILLIMETRES, CENTIMETRES, INCHES }

/** How the user wants dispersion shown. */
@Serializable
enum class AngularUnit { MOA, MIL }

/** Presentation preference carried on a profile. */
@Serializable
data class UnitPreference(
    val length: LengthUnit = LengthUnit.MILLIMETRES,
    val angular: AngularUnit = AngularUnit.MOA,
    val useYards: Boolean = false,
)
