package com.nobrainsoft.rangeanalyser.core.model

import com.nobrainsoft.rangeanalyser.core.geometry.Units
import kotlinx.serialization.Serializable

@Serializable
data class Firearm(
    val id: String,
    val name: String,
    val type: FirearmType,
    val caliberId: String,
    val action: ActionType = ActionType.UNKNOWN,
    val barrelLengthMm: Double? = null,
    /** Rifling twist as one turn in this many millimetres. */
    val twistRateMm: Double? = null,
    val sight: SightSetup = SightSetup(),
    val zeroDistanceM: Double? = null,
    val photoPath: String? = null,
    val notes: String = "",
) {
    val isPistol: Boolean
        get() = type in setOf(
            FirearmType.AIR_PISTOL,
            FirearmType.RIMFIRE_PISTOL,
            FirearmType.PISTOL,
            FirearmType.REVOLVER,
        )

    val isRifle: Boolean
        get() = type in setOf(
            FirearmType.AIR_RIFLE,
            FirearmType.RIMFIRE_RIFLE,
            FirearmType.CENTREFIRE_RIFLE,
        )

    val isAirgun: Boolean
        get() = type == FirearmType.AIR_RIFLE || type == FirearmType.AIR_PISTOL

    fun caliber(): Caliber? = Calibers.find(caliberId)
}

enum class FirearmType {
    AIR_RIFLE,
    AIR_PISTOL,
    RIMFIRE_RIFLE,
    RIMFIRE_PISTOL,
    CENTREFIRE_RIFLE,
    PISTOL,
    REVOLVER,
    SHOTGUN,
}

enum class ActionType {
    UNKNOWN,
    BOLT,
    SEMI_AUTO,
    LEVER,
    PUMP,
    BREAK_BARREL,
    SINGLE_SHOT,
    REVOLVER,
    PRE_CHARGED_PNEUMATIC,
    SPRING_PISTON,
}

@Serializable
data class SightSetup(
    val type: SightType = SightType.UNKNOWN,
    val clickValue: ClickValue? = null,
    /** Distance between front and rear sight, for iron sights. */
    val sightRadiusMm: Double? = null,
    /** Optic centre height above the bore axis. */
    val opticHeightMm: Double? = null,
)

enum class SightType {
    UNKNOWN,
    OPEN_IRON,
    APERTURE,
    DIOPTER,
    RED_DOT,
    SCOPE,
    NONE,
}

/**
 * One click of the sight's adjustment.
 *
 * Manufacturers express this in at least four different ways and shooters routinely mix them up,
 * so the unit travels with the number and every conversion goes through [mmAt].
 */
@Serializable
data class ClickValue(val amount: Double, val unit: ClickUnit) {
    /** Point-of-impact shift, in millimetres, produced by one click at [distanceM]. */
    fun mmAt(distanceM: Double): Double = when (unit) {
        ClickUnit.MOA -> Units.moaToMm(amount, distanceM)
        ClickUnit.MIL -> Units.milToMm(amount, distanceM)
        // "N mm at 100 m" scales linearly with distance.
        ClickUnit.MM_AT_100M -> amount * (distanceM / 100.0)
        // "N inch at 100 yd" - convert to mm, then scale from 100 yd to the actual distance.
        ClickUnit.INCH_AT_100YD ->
            Units.inchToMm(amount) * (distanceM / Units.yardsToMetres(100.0))
    }

    companion object {
        val QUARTER_MOA = ClickValue(0.25, ClickUnit.MOA)
        val HALF_MOA = ClickValue(0.5, ClickUnit.MOA)
        val TENTH_MIL = ClickValue(0.1, ClickUnit.MIL)
    }
}

enum class ClickUnit {
    MOA,
    MIL,
    MM_AT_100M,
    INCH_AT_100YD,
}
