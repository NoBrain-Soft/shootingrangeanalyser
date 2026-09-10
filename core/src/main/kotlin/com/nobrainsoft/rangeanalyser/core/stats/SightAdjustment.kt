package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.ClickValue
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns "your group is 40 mm high and 15 mm left" into "come down 6 clicks, right 2".
 *
 * The correction is always expressed as **how the point of impact should move**, never as which way
 * to turn a knob. Rear and front sights move opposite ways, scopes are marked yet another way, and
 * the one thing every shooter can verify is where the holes ended up.
 */
object SightAdjustment {

    /**
     * Whether the group's offset from the point of aim is distinguishable from zero.
     *
     * The centroid of n shots has a standard error of sigma/sqrt(n), so a small group shot three
     * times tells you very little about where it is centred. Below this bar the honest advice is
     * "shoot more before you touch the sights", not a click count.
     */
    fun offsetIsSignificant(stats: GroupStats, level: Double = 0.95): Boolean {
        if (stats.shotCount < 2) return false

        val covariance = Covariance2x2(
            xx = stats.sigmaXMm * stats.sigmaXMm / stats.shotCount,
            yy = stats.sigmaYMm * stats.sigmaYMm / stats.shotCount,
            // Reconstructed from the principal axes is overkill here; the axis-aligned standard
            // errors are what the shooter is adjusting along.
            xy = 0.0,
        )
        val inverse = covariance.inverse() ?: return stats.centroidOffset.radius > 0.0

        val delta = stats.centroidOffset
        val distanceSquared = delta.x * delta.x * inverse.xx + delta.y * delta.y * inverse.yy
        return distanceSquared > Statistics.chiSquare2dfCritical(level)
    }

    fun compute(stats: GroupStats, click: ClickValue): SightCorrection =
        compute(stats.centroidOffset, stats.distanceM, click)

    fun compute(
        centroidOffset: PointMm,
        distanceM: Double,
        click: ClickValue,
    ): SightCorrection {
        val clickMm = click.mmAt(distanceM)
        require(clickMm > 0.0) { "click value must move the impact somewhere, got $clickMm mm" }

        // To centre the group the impact has to move back by the offset.
        val requiredHorizontal = -centroidOffset.x
        val requiredVertical = -centroidOffset.y

        val horizontalClicks = (requiredHorizontal / clickMm).roundToInt()
        val verticalClicks = (requiredVertical / clickMm).roundToInt()

        val achieved = PointMm(horizontalClicks * clickMm, verticalClicks * clickMm)

        return SightCorrection(
            clickValueMm = clickMm,
            horizontalClicks = horizontalClicks,
            verticalClicks = verticalClicks,
            requiredMm = PointMm(requiredHorizontal, requiredVertical),
            achievedMm = achieved,
            // Whatever the clicks cannot resolve, because adjustments are discrete.
            residualMm = centroidOffset + achieved,
        )
    }
}

@Serializable
data class SightCorrection(
    /** How far one click moves the impact at this distance. */
    val clickValueMm: Double,
    /** Positive moves the impact right. */
    val horizontalClicks: Int,
    /** Positive moves the impact up. */
    val verticalClicks: Int,
    val requiredMm: PointMm,
    val achievedMm: PointMm,
    val residualMm: PointMm,
) {
    val verticalDirection: VerticalDirection
        get() = when {
            verticalClicks > 0 -> VerticalDirection.UP
            verticalClicks < 0 -> VerticalDirection.DOWN
            else -> VerticalDirection.NONE
        }

    val horizontalDirection: HorizontalDirection
        get() = when {
            horizontalClicks > 0 -> HorizontalDirection.RIGHT
            horizontalClicks < 0 -> HorizontalDirection.LEFT
            else -> HorizontalDirection.NONE
        }

    val verticalClickCount: Int get() = abs(verticalClicks)

    val horizontalClickCount: Int get() = abs(horizontalClicks)

    val isNoOp: Boolean get() = verticalClicks == 0 && horizontalClicks == 0
}

enum class VerticalDirection { UP, DOWN, NONE }

enum class HorizontalDirection { LEFT, RIGHT, NONE }
