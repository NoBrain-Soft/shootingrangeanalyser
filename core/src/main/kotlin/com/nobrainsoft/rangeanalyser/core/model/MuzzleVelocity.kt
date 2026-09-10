package com.nobrainsoft.rangeanalyser.core.model

import kotlin.math.pow

/**
 * How sure we are of an estimated muzzle velocity.
 *
 * This is shown to the shooter rather than kept internal, because the answer to "should I trust
 * this?" is different for a .308 match load and an air rifle, and only the app knows which.
 */
enum class VelocityConfidence {
    /** Factory loads in this calibre cluster tightly. The estimate will be close. */
    TYPICAL,

    /** The calibre spans very different loadings, or the weight is far from anything typical. */
    BROAD,

    /**
     * Power varies by the individual gun rather than the ammunition, so this is barely an estimate.
     * Air rifles are the case: the same pellet leaves a sub-12 ft-lb rifle and a magnum springer at
     * velocities that differ by a factor of two.
     */
    GUN_DEPENDENT,
}

/**
 * An estimated muzzle velocity, with the range it could plausibly be and why.
 *
 * The band is the point. A single number invites the shooter to treat a guess as a measurement, and
 * every downstream calculation - drop, dispersion attribution, the "is it you or the ammunition?"
 * verdict - inherits that false precision.
 */
data class VelocityEstimate(
    val metresPerSecond: Double,
    val plausibleLowMps: Double,
    val plausibleHighMps: Double,
    val confidence: VelocityConfidence,
    /** Plain-language account of what the number came from. */
    val basis: String,
)

/**
 * Estimates muzzle velocity from the calibre, the bullet weight and the barrel.
 *
 * Nobody without a chronograph can answer "what does your ammunition actually do", and demanding
 * the number as a required field is how you get a made-up one - which is worse than none, because
 * the app cannot tell it was made up. So the app estimates, says it is estimating, and offers to
 * take a real figure if there is one.
 *
 * The method is deliberately simple: a published factory reference load per calibre, scaled for
 * bullet weight and barrel length. It is an ordering-of-magnitude tool for dispersion attribution,
 * not a ballistic solver, and it does not pretend otherwise.
 */
object MuzzleVelocity {

    /**
     * A representative factory load, from published manufacturer data for that calibre.
     *
     * These are typical figures, not any particular product's, and the plausible band around each
     * is wide enough to cover the ordinary spread between brands.
     */
    private data class Reference(
        val bulletWeightGrains: Double,
        val velocityMps: Double,
        val barrelLengthMm: Double,
        val confidence: VelocityConfidence = VelocityConfidence.TYPICAL,
        /** Fractional half-width of the plausible band. */
        val spread: Double = 0.10,
    )

    private val references: Map<String, Reference> = mapOf(
        // Air: the pellet says almost nothing; the powerplant says everything.
        Calibers.AIR_177.id to Reference(7.9, 200.0, 450.0, VelocityConfidence.GUN_DEPENDENT, 0.35),
        Calibers.AIR_20.id to Reference(11.4, 190.0, 450.0, VelocityConfidence.GUN_DEPENDENT, 0.35),
        Calibers.AIR_22.id to Reference(14.3, 175.0, 450.0, VelocityConfidence.GUN_DEPENDENT, 0.35),
        Calibers.AIR_25.id to Reference(25.4, 160.0, 450.0, VelocityConfidence.GUN_DEPENDENT, 0.35),

        // Rimfire: .22 LR is sold as standard velocity and as high velocity, hence the wide band.
        Calibers.RF_17_HMR.id to Reference(17.0, 775.0, 550.0),
        Calibers.RF_22_LR.id to Reference(40.0, 340.0, 500.0, VelocityConfidence.BROAD, 0.16),
        Calibers.RF_22_WMR.id to Reference(40.0, 570.0, 550.0),

        Calibers.R_223.id to Reference(55.0, 990.0, 508.0),
        Calibers.R_243.id to Reference(100.0, 900.0, 610.0),
        Calibers.R_65.id to Reference(140.0, 820.0, 610.0),
        Calibers.R_270.id to Reference(130.0, 930.0, 610.0),
        Calibers.R_7MM.id to Reference(150.0, 890.0, 610.0, VelocityConfidence.BROAD, 0.14),
        Calibers.R_308.id to Reference(168.0, 808.0, 610.0),
        Calibers.R_3006.id to Reference(168.0, 850.0, 610.0),
        Calibers.R_8MM.id to Reference(196.0, 800.0, 610.0, VelocityConfidence.BROAD, 0.14),
        Calibers.R_338.id to Reference(250.0, 820.0, 660.0, VelocityConfidence.BROAD, 0.14),
        Calibers.R_50BMG.id to Reference(660.0, 880.0, 737.0),

        Calibers.P_9MM.id to Reference(124.0, 340.0, 102.0),
        // One entry covering both .38 Special and .357 Magnum, which differ by around 50 per cent.
        Calibers.P_38.id to Reference(158.0, 300.0, 102.0, VelocityConfidence.BROAD, 0.28),
        Calibers.P_40.id to Reference(180.0, 300.0, 102.0),
        Calibers.P_10MM.id to Reference(180.0, 350.0, 127.0, VelocityConfidence.BROAD, 0.14),
        Calibers.P_44.id to Reference(240.0, 380.0, 152.0, VelocityConfidence.BROAD, 0.20),
        Calibers.P_45.id to Reference(230.0, 253.0, 127.0),

        Calibers.SG_12_SLUG.id to Reference(437.0, 500.0, 710.0, VelocityConfidence.BROAD, 0.15),
    )

    /**
     * Best estimate for this combination, or null when the calibre is not one we have a reference
     * for - in which case saying nothing is the honest answer.
     *
     * [barrelLengthMm] is optional; without it the reference barrel is assumed, which is what a
     * manufacturer's quoted figure is measured from anyway.
     */
    fun estimate(
        caliber: Caliber?,
        bulletWeightGrains: Double? = null,
        barrelLengthMm: Double? = null,
    ): VelocityEstimate? {
        val reference = references[caliber?.id ?: return null] ?: return null

        val weight = bulletWeightGrains?.takeIf { it > 0.0 } ?: reference.bulletWeightGrains
        val weightRatio = weight / reference.bulletWeightGrains
        val fromWeight = reference.velocityMps * weightRatio.pow(WEIGHT_EXPONENT)

        val barrelFactor = barrelFactor(caliber, reference, barrelLengthMm)
        val velocity = fromWeight * barrelFactor

        // Extrapolating a long way from the reference weight is guesswork, and the band says so.
        val stretch = if (weightRatio in 0.7..1.4) 0.0 else EXTRAPOLATION_PENALTY
        val spread = reference.spread + stretch

        return VelocityEstimate(
            metresPerSecond = velocity,
            plausibleLowMps = velocity * (1.0 - spread),
            plausibleHighMps = velocity * (1.0 + spread),
            confidence = if (stretch > 0.0 && reference.confidence == VelocityConfidence.TYPICAL) {
                VelocityConfidence.BROAD
            } else {
                reference.confidence
            },
            basis = describe(caliber, reference, weight, barrelLengthMm, barrelFactor),
        )
    }

    /**
     * The velocity to actually calculate with: what the shooter measured, or failing that an
     * estimate.
     *
     * Returns null when neither is available, so callers can decline rather than invent.
     */
    fun resolve(ammo: Ammo?, firearm: Firearm?): Double? =
        ammo?.muzzleVelocityMps
            ?: estimate(
                caliber = ammo?.caliber() ?: firearm?.caliber(),
                bulletWeightGrains = ammo?.bulletWeightGrains,
                barrelLengthMm = firearm?.barrelLengthMm,
            )?.metresPerSecond

    private fun barrelFactor(
        caliber: Caliber,
        reference: Reference,
        barrelLengthMm: Double?,
    ): Double {
        val barrel = barrelLengthMm?.takeIf { it > 0.0 } ?: return 1.0
        val rate = when (caliber.kind) {
            CaliberKind.PISTOL -> 0.0009
            CaliberKind.RIMFIRE -> 0.0004
            CaliberKind.RIFLE -> 0.0004
            CaliberKind.AIRGUN -> 0.0003
            CaliberKind.SHOTGUN -> 0.0002
        }
        val factor = 1.0 + rate * (barrel - reference.barrelLengthMm)
        // Beyond this the linear rule stops meaning anything - a barrel twice the reference does
        // not add half again the velocity, and past a point powder burn is complete and it falls.
        return factor.coerceIn(MIN_BARREL_FACTOR, MAX_BARREL_FACTOR)
    }

    private fun describe(
        caliber: Caliber,
        reference: Reference,
        weightGrains: Double,
        barrelLengthMm: Double?,
        barrelFactor: Double,
    ): String = buildString {
        append("Typical factory ${caliber.displayName} runs about ")
        append(reference.velocityMps.toInt())
        append(" m/s with a ")
        append(trimGrains(reference.bulletWeightGrains))
        append(" gr bullet")
        if (weightGrains != reference.bulletWeightGrains) {
            append(", scaled here to ")
            append(trimGrains(weightGrains))
            append(" gr")
        }
        if (barrelLengthMm != null && barrelFactor != 1.0) {
            append(barrelFactor.let { if (it > 1.0) ", and up" else ", and down" })
            append(" for a ")
            append(barrelLengthMm.toInt())
            append(" mm barrel against a ")
            append(reference.barrelLengthMm.toInt())
            append(" mm reference")
        }
        append(".")
    }

    private fun trimGrains(grains: Double): String =
        if (grains % 1.0 == 0.0) grains.toInt().toString() else String.format("%.1f", grains)

    /**
     * How velocity falls as bullet weight rises within one cartridge.
     *
     * Constant muzzle energy would give -0.5 and constant momentum -1.0; real factory ladders sit
     * between. Fitted against published 9 mm and .308 weight ladders, which both land near -0.75.
     */
    private const val WEIGHT_EXPONENT = -0.75

    private const val EXTRAPOLATION_PENALTY = 0.08
    private const val MIN_BARREL_FACTOR = 0.80
    private const val MAX_BARREL_FACTOR = 1.20
}
