package com.nobrainsoft.rangeanalyser.core.ballistics

import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.DragModel
import com.nobrainsoft.rangeanalyser.core.model.Weather
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A deliberately small point-mass trajectory model.
 *
 * It exists for one purpose: to answer *how much of the vertical spread on this target is the
 * ammunition's fault*. A load with a 15 m/s velocity standard deviation will string shots
 * vertically at 300 m no matter how well it is shot, and telling somebody to work on their
 * breathing when the answer is their ammunition wastes their range time.
 *
 * It is not a ballistic calculator and must not be used to dial a scope: no spin drift, no
 * Coriolis, no transonic correction, and a drag model that assumes the published BC is honest.
 */
object Ballistics {

    private const val GRAVITY = 9.80665
    private const val SPECIFIC_GAS_CONSTANT = 287.05
    private const val KELVIN_OFFSET = 273.15

    /** Ballistic coefficients are published in lb/in²; convert to kg/m² to work in SI. */
    private const val BC_LB_PER_SQ_INCH_TO_SI = 703.0696

    private const val TIME_STEP_SECONDS = 0.0002
    private const val MAX_FLIGHT_SECONDS = 10.0

    /**
     * Solves the trajectory for a given zero, returning drop and velocity along the flight path.
     *
     * Returns null when the projectile cannot reach the zero distance at all - a pellet asked about
     * a 300 m zero, say.
     */
    fun solve(input: BallisticInput): BallisticSolution? {
        val launchAngle = solveLaunchAngle(input) ?: return null
        val path = integrate(input, launchAngle, input.maxRangeM)
        if (path.isEmpty()) return null
        return BallisticSolution(launchAngleRad = launchAngle, path = path)
    }

    /**
     * Vertical dispersion at [distanceM] attributable to the load's muzzle-velocity spread.
     *
     * The launch angle is held fixed while the muzzle velocity is varied, because that is what
     * physically happens: the sight setting does not know that this particular round came out
     * faster. Returns the standard deviation of vertical impact position in millimetres.
     */
    fun verticalDispersionFromVelocitySd(
        ammo: Ammo,
        distanceM: Double,
        zeroDistanceM: Double,
        sightHeightMm: Double = DEFAULT_SIGHT_HEIGHT_MM,
        atmosphere: Atmosphere = Atmosphere.STANDARD,
    ): VelocityDispersion? {
        val muzzleVelocity = ammo.muzzleVelocityMps ?: return null
        val velocitySd = ammo.velocitySdMps ?: return null
        val ballisticCoefficient = ammo.ballisticCoefficient ?: return null
        if (velocitySd <= 0.0 || distanceM <= 0.0) return null

        val base = BallisticInput(
            muzzleVelocityMps = muzzleVelocity,
            ballisticCoefficient = ballisticCoefficient,
            dragModel = ammo.dragModel,
            zeroDistanceM = zeroDistanceM,
            sightHeightMm = sightHeightMm,
            atmosphere = atmosphere,
            maxRangeM = distanceM,
        )

        // One launch angle, three muzzle velocities: the sight does not move between shots.
        val launchAngle = solveLaunchAngle(base) ?: return null

        val fast = integrate(base.copy(muzzleVelocityMps = muzzleVelocity + velocitySd), launchAngle, distanceM)
        val slow = integrate(base.copy(muzzleVelocityMps = muzzleVelocity - velocitySd), launchAngle, distanceM)
        val nominal = integrate(base, launchAngle, distanceM)

        val fastDrop = heightAt(fast, distanceM) ?: return null
        val slowDrop = heightAt(slow, distanceM) ?: return null

        return VelocityDispersion(
            distanceM = distanceM,
            velocitySdMps = velocitySd,
            // Half the spread between plus and minus one sigma is the standard deviation of impact.
            verticalSigmaMm = abs(fastDrop - slowDrop) / 2.0 * 1000.0,
            timeOfFlightSeconds = timeAt(nominal, distanceM) ?: 0.0,
        )
    }

    /**
     * Bisects for the launch angle that puts the trajectory on the line of sight at the zero
     * distance. Monotonic in angle over any sane range, so bisection is both safe and quick.
     */
    private fun solveLaunchAngle(input: BallisticInput): Double? {
        var low = -0.02
        var high = 0.15

        fun heightAtZero(angle: Double): Double? =
            heightAt(integrate(input, angle, input.zeroDistanceM), input.zeroDistanceM)

        // If even the steepest angle falls short, the projectile cannot reach the zero distance.
        val highest = heightAtZero(high) ?: return null
        if (highest < 0.0) return null

        repeat(60) {
            val middle = (low + high) / 2.0
            val height = heightAtZero(middle) ?: return null
            if (height > 0.0) high = middle else low = middle
        }
        return (low + high) / 2.0
    }

    private fun integrate(
        input: BallisticInput,
        launchAngleRad: Double,
        toDistanceM: Double,
    ): List<TrajectoryPoint> {
        val bcSi = input.ballisticCoefficient * BC_LB_PER_SQ_INCH_TO_SI
        if (bcSi <= 0.0) return emptyList()

        val table = when (input.dragModel) {
            DragModel.G1 -> DragTables.G1
            DragModel.G7 -> DragTables.G7
        }
        val airDensity = input.atmosphere.airDensityKgPerM3
        val speedOfSound = input.atmosphere.speedOfSoundMps

        var x = 0.0
        // The bore sits below the sight line by the optic height, which is why a rifle shoots low
        // at very close range even when perfectly zeroed further out.
        var y = -input.sightHeightMm / 1000.0
        var vx = input.muzzleVelocityMps * cos(launchAngleRad)
        var vy = input.muzzleVelocityMps * sin(launchAngleRad)
        var time = 0.0

        val path = ArrayList<TrajectoryPoint>(2048)
        path += TrajectoryPoint(x, y, hypot(vx, vy), time)

        val limit = toDistanceM + 1.0
        while (x < limit && time < MAX_FLIGHT_SECONDS) {
            val speed = hypot(vx, vy)
            if (speed <= 1.0) break

            val dragDeceleration =
                0.5 * airDensity * speed * speed *
                    table.dragCoefficientAt(speed / speedOfSound) *
                    (Math.PI / 4.0) / bcSi

            val ax = -dragDeceleration * vx / speed
            val ay = -dragDeceleration * vy / speed - GRAVITY

            vx += ax * TIME_STEP_SECONDS
            vy += ay * TIME_STEP_SECONDS
            x += vx * TIME_STEP_SECONDS
            y += vy * TIME_STEP_SECONDS
            time += TIME_STEP_SECONDS

            path += TrajectoryPoint(x, y, hypot(vx, vy), time)
        }
        return path
    }

    internal fun heightAt(path: List<TrajectoryPoint>, distanceM: Double): Double? =
        interpolate(path, distanceM) { it.heightM }

    internal fun timeAt(path: List<TrajectoryPoint>, distanceM: Double): Double? =
        interpolate(path, distanceM) { it.timeS }

    internal fun velocityAt(path: List<TrajectoryPoint>, distanceM: Double): Double? =
        interpolate(path, distanceM) { it.velocityMps }

    private inline fun interpolate(
        path: List<TrajectoryPoint>,
        distanceM: Double,
        select: (TrajectoryPoint) -> Double,
    ): Double? {
        if (path.size < 2 || path.last().distanceM < distanceM) return null

        val index = path.indexOfFirst { it.distanceM >= distanceM }
        if (index <= 0) return select(path.first())

        val before = path[index - 1]
        val after = path[index]
        val span = after.distanceM - before.distanceM
        if (span <= 0.0) return select(after)

        val weight = (distanceM - before.distanceM) / span
        return select(before) * (1 - weight) + select(after) * weight
    }

    const val DEFAULT_SIGHT_HEIGHT_MM = 40.0
}

@Serializable
data class BallisticInput(
    val muzzleVelocityMps: Double,
    /** As published, in lb/in². */
    val ballisticCoefficient: Double,
    val dragModel: DragModel = DragModel.G1,
    val zeroDistanceM: Double,
    val sightHeightMm: Double = Ballistics.DEFAULT_SIGHT_HEIGHT_MM,
    val atmosphere: Atmosphere = Atmosphere.STANDARD,
    val maxRangeM: Double = 1000.0,
)

@Serializable
data class TrajectoryPoint(
    val distanceM: Double,
    /** Height relative to the line of sight; negative is below it. */
    val heightM: Double,
    val velocityMps: Double,
    val timeS: Double,
)

@Serializable
data class BallisticSolution(
    val launchAngleRad: Double,
    val path: List<TrajectoryPoint>,
) {
    /** Drop below the line of sight, in millimetres. Positive means the shot lands low. */
    fun dropMmAt(distanceM: Double): Double? =
        Ballistics.heightAt(path, distanceM)?.let { -it * 1000.0 }

    fun velocityAt(distanceM: Double): Double? = Ballistics.velocityAt(path, distanceM)

    fun timeOfFlightAt(distanceM: Double): Double? = Ballistics.timeAt(path, distanceM)

    val launchAngleMoa: Double get() = Math.toDegrees(atan(kotlin.math.tan(launchAngleRad))) * 60.0
}

@Serializable
data class VelocityDispersion(
    val distanceM: Double,
    val velocitySdMps: Double,
    /** Standard deviation of vertical impact caused by the velocity spread alone. */
    val verticalSigmaMm: Double,
    val timeOfFlightSeconds: Double,
) {
    /**
     * What fraction of an observed vertical spread this accounts for.
     *
     * Near or above 1.0 means the shooter is already at the ammunition's limit vertically and no
     * amount of technique work will tighten it further.
     */
    fun fractionOf(observedVerticalSigmaMm: Double): Double? =
        if (observedVerticalSigmaMm <= 0.0) null else verticalSigmaMm / observedVerticalSigmaMm
}

@Serializable
data class Atmosphere(
    val temperatureC: Double = 15.0,
    val pressureHpa: Double = 1013.25,
) {
    val airDensityKgPerM3: Double
        get() = (pressureHpa * 100.0) / (287.05 * (temperatureC + 273.15))

    /** Sound travels faster in warm air, which shifts where transonic drag effects begin. */
    val speedOfSoundMps: Double
        get() = sqrt(1.4 * 287.05 * (temperatureC + 273.15))

    companion object {
        val STANDARD = Atmosphere()

        fun from(weather: Weather?): Atmosphere = Atmosphere(
            temperatureC = weather?.temperatureC ?: STANDARD.temperatureC,
            pressureHpa = weather?.pressureHpa ?: STANDARD.pressureHpa,
        )
    }
}
