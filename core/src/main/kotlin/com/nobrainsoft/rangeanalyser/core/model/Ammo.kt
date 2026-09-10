package com.nobrainsoft.rangeanalyser.core.model

import com.nobrainsoft.rangeanalyser.core.geometry.Units
import kotlinx.serialization.Serializable

/**
 * A load. Kept separate from [Firearm] so the same rifle can be compared across ammunition, which
 * is the whole point of load development.
 *
 * [velocitySdMps] is the field that unlocks the most useful coaching: it lets the engine work out
 * how much of an observed vertical spread the ammunition alone explains, rather than blaming the
 * shooter for it.
 */
@Serializable
data class Ammo(
    val id: String,
    val brand: String,
    val line: String = "",
    val caliberId: String,
    val bulletWeightGrains: Double? = null,
    val bulletType: BulletType = BulletType.UNKNOWN,
    val muzzleVelocityMps: Double? = null,
    val velocitySdMps: Double? = null,
    val ballisticCoefficient: Double? = null,
    val dragModel: DragModel = DragModel.G1,
    val lot: String = "",
    val notes: String = "",
) {
    val displayName: String
        get() = buildString {
            append(brand)
            if (line.isNotBlank()) append(' ').append(line)
            bulletWeightGrains?.let { append(' ').append(formatGrains(it)).append(" gr") }
        }

    val bulletMassKg: Double?
        get() = bulletWeightGrains?.let { Units.grainsToGrams(it) / 1000.0 }

    fun caliber(): Caliber? = Calibers.find(caliberId)

    /** True when there is enough data to run a trajectory, which several coaching rules need. */
    fun hasBallisticData(): Boolean =
        muzzleVelocityMps != null && ballisticCoefficient != null && bulletWeightGrains != null

    private fun formatGrains(grains: Double): String =
        if (grains % 1.0 == 0.0) grains.toInt().toString() else grains.toString()
}

enum class BulletType {
    UNKNOWN,
    FMJ,
    HOLLOW_POINT,
    SOFT_POINT,
    MATCH_HPBT,
    WADCUTTER,
    SEMI_WADCUTTER,
    LEAD_ROUND_NOSE,
    PELLET_DOMED,
    PELLET_WADCUTTER,
    PELLET_POINTED,
    PELLET_HOLLOW_POINT,
    SLUG,
    BUCKSHOT,
    BIRDSHOT,
}

enum class DragModel {
    /** Flat-base, blunt reference projectile. Most factory ammunition is advertised this way. */
    G1,

    /** Boat-tail, long-ogive reference projectile. Better for modern match bullets. */
    G7,
}
