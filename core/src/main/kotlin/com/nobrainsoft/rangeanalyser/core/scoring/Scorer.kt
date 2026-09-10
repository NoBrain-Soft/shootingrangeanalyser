package com.nobrainsoft.rangeanalyser.core.scoring

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.model.scoring
import com.nobrainsoft.rangeanalyser.core.target.ScoringModel
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.min

/**
 * Turns a hole position into a score.
 *
 * Two rules do the real work here.
 *
 * **Edge gauging.** A shot counts for the higher ring if the *edge* of the hole touches the ring
 * line, not its centre. So the radius that matters is the centre distance less the bullet radius,
 * and the same hole position scores differently for a .22 and a .45. Getting this wrong costs
 * points on every boundary shot, which is most of the ones anybody argues about.
 *
 * **Decimal scoring.** ISSF electronic targets report tenths. The score falls linearly with gauged
 * radius, hitting exactly N.0 on ring N's line, and is capped at 10.9 in the middle. Because it is
 * defined from the ring lines outwards it works on unevenly spaced targets too, though only evenly
 * spaced faces declare [ScoringModel.Rings.supportsDecimal].
 */
class Scorer(
    private val spec: TargetSpec,
    private val caliber: Caliber?,
) {
    private val bulletRadiusMm: Double = caliber?.bulletRadiusMm ?: 0.0

    /** False when no calibre is known, so the UI can say scores are not edge-gauged. */
    val edgeGauged: Boolean = caliber != null

    fun score(position: PointMm): ShotScore {
        val radiusMm = position.radius
        val gauged = (radiusMm - bulletRadiusMm).coerceAtLeast(NEGATIVE_RADIUS_FLOOR)

        return when (val model = spec.scoring) {
            is ScoringModel.Rings -> scoreRings(model, radiusMm, gauged)
            is ScoringModel.Zones -> scoreZones(model, position, radiusMm, gauged)
            ScoringModel.NoScoring -> ShotScore(
                radiusMm = radiusMm,
                gaugedRadiusMm = gauged,
                edgeGauged = edgeGauged,
            )
        }
    }

    fun scoreAll(shots: List<Shot>): List<Pair<Shot, ShotScore>> =
        shots.scoring().map { it to score(it.position) }

    fun summarise(shots: List<Shot>): ScoreSummary {
        val scored = scoreAll(shots)
        val rings = spec.rings

        val decimalTotal = scored.mapNotNull { it.second.decimalScore }
            .takeIf { it.size == scored.size && it.isNotEmpty() }
            ?.sum()

        val zonePoints = scored.mapNotNull { it.second.zonePoints }
            .takeIf { spec.scoring is ScoringModel.Zones }
            ?.sum()

        return ScoreSummary(
            shotCount = scored.size,
            totalRingScore = scored.sumOf { it.second.ringValue ?: 0 },
            totalDecimalScore = decimalTotal,
            innerRingCount = scored.count { it.second.isInnerRing },
            missCount = scored.count { it.second.isMiss },
            ringCounts = scored.mapNotNull { it.second.ringValue }
                .groupingBy { it }
                .eachCount(),
            zoneCounts = scored.mapNotNull { it.second.zoneLabel }
                .groupingBy { it }
                .eachCount(),
            totalZonePoints = zonePoints,
            maxPossibleRingScore = rings?.let { it.highestValue * scored.size },
        )
    }

    private fun scoreRings(
        model: ScoringModel.Rings,
        radiusMm: Double,
        gauged: Double,
    ): ShotScore {
        val ringValue = model.byValueDescending.firstOrNull { gauged <= it.radiusMm }?.value
        val innerRadius = model.innerRingRadiusMm

        return ShotScore(
            ringValue = ringValue,
            decimalScore = decimalScore(model, gauged),
            isInnerRing = innerRadius != null && gauged <= innerRadius,
            isMiss = ringValue == null,
            radiusMm = radiusMm,
            gaugedRadiusMm = gauged,
            edgeGauged = edgeGauged,
        )
    }

    /**
     * Continuous score as a function of gauged radius.
     *
     * Anchored on the ring lines: exactly N.0 on ring N, interpolating linearly between them, and
     * extrapolating inward from the highest ring using its own step so a centre hit reaches the
     * maximum. Returns null on faces that do not use decimal scoring.
     */
    private fun decimalScore(model: ScoringModel.Rings, gauged: Double): Double? {
        if (!model.supportsDecimal) return null

        val ordered = model.byValueDescending
        val innermost = ordered.first()

        if (gauged <= innermost.radiusMm) {
            val step = model.radiusStepAt(innermost.value) ?: return null
            val raw = innermost.value + (innermost.radiusMm - gauged) / step
            return min(raw, model.maxDecimal)
        }

        ordered.zipWithNext { inner, outer ->
            if (gauged <= outer.radiusMm) {
                val span = outer.radiusMm - inner.radiusMm
                return inner.value - (gauged - inner.radiusMm) / span
            }
        }

        // Outside the lowest ring: a miss.
        return 0.0
    }

    private fun scoreZones(
        model: ScoringModel.Zones,
        position: PointMm,
        radiusMm: Double,
        gauged: Double,
    ): ShotScore {
        // Practical rules also give the shooter the better zone when the hole breaks the line, so
        // the test allows the bullet radius as a margin.
        val zone = model.byPriority.firstOrNull { it.shape.touches(position, bulletRadiusMm) }

        return ShotScore(
            zoneLabel = zone?.label,
            zonePoints = zone?.points,
            isMiss = zone == null,
            radiusMm = radiusMm,
            gaugedRadiusMm = gauged,
            edgeGauged = edgeGauged,
        )
    }

    private companion object {
        /**
         * A hole centred closer than its own radius has a "gauged radius" that is mathematically
         * negative. That is meaningful for decimal scoring (it is how 10.9 is reached), so it is
         * kept rather than clamped to zero - only absurd values are floored.
         */
        const val NEGATIVE_RADIUS_FLOOR = -1000.0
    }
}

@Serializable
data class ShotScore(
    val ringValue: Int? = null,
    val decimalScore: Double? = null,
    val zoneLabel: String? = null,
    val zonePoints: Int? = null,
    val isInnerRing: Boolean = false,
    val isMiss: Boolean = false,
    /** Distance from the target centre to the hole centre. */
    val radiusMm: Double = 0.0,
    /** Distance to the nearest edge of the hole - the radius that actually decides the ring. */
    val gaugedRadiusMm: Double = 0.0,
    val edgeGauged: Boolean = false,
) {
    /** Integer ring score, treating a miss as zero. */
    val points: Int get() = ringValue ?: 0

    /**
     * The decimal score truncated to a whole ring. Always agrees with [ringValue] - the two are
     * derived independently, and a test pins that they never disagree.
     */
    val decimalAsInteger: Int? get() = decimalScore?.let { floor(it).toInt() }
}

@Serializable
data class ScoreSummary(
    val shotCount: Int,
    val totalRingScore: Int,
    val totalDecimalScore: Double?,
    val innerRingCount: Int,
    val missCount: Int,
    val ringCounts: Map<Int, Int>,
    val zoneCounts: Map<String, Int>,
    val totalZonePoints: Int?,
    val maxPossibleRingScore: Int?,
)
