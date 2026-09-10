package com.nobrainsoft.rangeanalyser.core.target

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import kotlinx.serialization.Serializable

/**
 * The printed geometry of a target face.
 *
 * This is what turns pixels into meaning. It gives the scorer its ring table, and it gives the
 * calibrator something of known size to measure - which is why [blackDiameterMm] matters even on
 * targets with no rings at all: an orange aiming dot of known diameter is a perfectly good ruler.
 */
@Serializable
data class TargetSpec(
    val id: String,
    val name: String,
    val discipline: Discipline,
    val scoring: ScoringModel,
    val sheetWidthMm: Double? = null,
    val sheetHeightMm: Double? = null,
    /** Diameter of the printed black (or otherwise high-contrast) aiming area. */
    val blackDiameterMm: Double? = null,
    val defaultDistanceM: Double? = null,
    /** Spacing of a printed grid, if any. Another usable calibration reference. */
    val gridSpacingMm: Double? = null,
    val isBuiltIn: Boolean = true,
    /**
     * False when some dimensions are approximate. The UI says so rather than implying a precision
     * the data does not have, and the target editor lets the user correct them.
     */
    val dimensionsVerified: Boolean = true,
    /** Where the numbers came from, so a future reader can check them. */
    val source: String = "",
) {
    val rings: ScoringModel.Rings? get() = scoring as? ScoringModel.Rings

    /** Largest scoring radius, used to size the drawn target and to sanity-check detections. */
    val outerRadiusMm: Double?
        get() = when (scoring) {
            is ScoringModel.Rings -> scoring.outermostRadiusMm
            is ScoringModel.Zones -> scoring.boundingRadiusMm
            ScoringModel.NoScoring -> sheetWidthMm?.let { maxOf(it, sheetHeightMm ?: it) / 2.0 }
        }
}

enum class Discipline {
    PRECISION_AIR,
    PRECISION_SMALLBORE,
    PRECISION_FULLBORE,
    BULLSEYE_PISTOL,
    PRACTICAL,
    LOAD_DEVELOPMENT,
    GENERAL,
}

@Serializable
sealed interface ScoringModel {
    /**
     * Concentric scoring rings.
     *
     * Ring diameters are stored, not radii, because that is how every rulebook publishes them and
     * transcription errors are the main risk here.
     */
    @Serializable
    data class Rings(
        val rings: List<Ring>,
        /** A smaller tie-break circle inside the highest ring, e.g. the NRA X ring. */
        val innerRingDiameterMm: Double? = null,
        val innerRingLabel: String? = null,
        /** ISSF-style tenth-of-a-ring scoring. Only meaningful for evenly spaced rings. */
        val supportsDecimal: Boolean = false,
        val maxDecimal: Double = 10.9,
    ) : ScoringModel {
        /** Highest value (smallest ring) first. */
        val byValueDescending: List<Ring> = rings.sortedByDescending { it.value }

        val highestValue: Int get() = byValueDescending.first().value

        val lowestValue: Int get() = byValueDescending.last().value

        val outermostRadiusMm: Double get() = byValueDescending.last().radiusMm

        val innerRingRadiusMm: Double? get() = innerRingDiameterMm?.let { it / 2.0 }

        init {
            require(rings.isNotEmpty()) { "a ring target needs at least one ring" }
            require(rings.map { it.value }.toSet().size == rings.size) {
                "duplicate ring values in $rings"
            }
            // A higher-valued ring must be physically smaller. Catches transcription slips.
            byValueDescending.zipWithNext { inner, outer ->
                require(inner.diameterMm < outer.diameterMm) {
                    "ring ${inner.value} (${inner.diameterMm} mm) must be smaller than " +
                        "ring ${outer.value} (${outer.diameterMm} mm)"
                }
            }
        }

        fun radiusFor(value: Int): Double? = byValueDescending.firstOrNull { it.value == value }?.radiusMm

        /**
         * Radial width of the annulus below [value] - the distance from this ring's line out to the
         * next one. Decimal scoring divides this into ten.
         *
         * Not every target is evenly spaced (the NRA B-8 emphatically is not), so this is looked up
         * per ring rather than assumed constant.
         */
        fun radiusStepAt(value: Int): Double? {
            val index = byValueDescending.indexOfFirst { it.value == value }
            if (index < 0) return null
            return when {
                index + 1 < byValueDescending.size ->
                    byValueDescending[index + 1].radiusMm - byValueDescending[index].radiusMm
                // Outermost ring: reuse the step just inside it.
                index > 0 -> byValueDescending[index].radiusMm - byValueDescending[index - 1].radiusMm
                else -> null
            }
        }

        val hasUniformStep: Boolean
            get() {
                val steps = byValueDescending.zipWithNext { a, b -> b.radiusMm - a.radiusMm }
                if (steps.isEmpty()) return true
                val first = steps.first()
                return steps.all { kotlin.math.abs(it - first) < 0.05 }
            }
    }

    /** Hit zones, as used by practical and defensive disciplines. */
    @Serializable
    data class Zones(val zones: List<Zone>) : ScoringModel {
        init {
            require(zones.isNotEmpty()) { "a zone target needs at least one zone" }
        }

        /** Zones are tested in order, so the most valuable must come first. */
        val byPriority: List<Zone> = zones.sortedByDescending { it.points }

        val boundingRadiusMm: Double get() = zones.maxOf { it.shape.boundingRadiusMm() }
    }

    /** No printed scoring at all - group geometry is the only thing that matters. */
    @Serializable
    data object NoScoring : ScoringModel
}

@Serializable
data class Ring(val value: Int, val diameterMm: Double) {
    val radiusMm: Double get() = diameterMm / 2.0

    init {
        require(diameterMm > 0.0) { "ring $value has non-positive diameter $diameterMm" }
    }
}

@Serializable
data class Zone(val label: String, val points: Int, val shape: ZoneShape)

@Serializable
sealed interface ZoneShape {
    fun contains(point: PointMm): Boolean

    fun boundingRadiusMm(): Double

    @Serializable
    data class Circle(val centre: PointMm, val diameterMm: Double) : ZoneShape {
        override fun contains(point: PointMm): Boolean =
            point.distanceTo(centre) <= diameterMm / 2.0

        override fun boundingRadiusMm(): Double = centre.radius + diameterMm / 2.0
    }

    @Serializable
    data class Rect(val centre: PointMm, val widthMm: Double, val heightMm: Double) : ZoneShape {
        override fun contains(point: PointMm): Boolean =
            kotlin.math.abs(point.x - centre.x) <= widthMm / 2.0 &&
                kotlin.math.abs(point.y - centre.y) <= heightMm / 2.0

        override fun boundingRadiusMm(): Double =
            centre.radius + kotlin.math.hypot(widthMm / 2.0, heightMm / 2.0)
    }

    @Serializable
    data class Polygon(val vertices: List<PointMm>) : ZoneShape {
        init {
            require(vertices.size >= 3) { "a polygon zone needs at least 3 vertices" }
        }

        /** Standard ray-casting point-in-polygon test. */
        override fun contains(point: PointMm): Boolean {
            var inside = false
            var j = vertices.lastIndex
            for (i in vertices.indices) {
                val a = vertices[i]
                val b = vertices[j]
                if ((a.y > point.y) != (b.y > point.y)) {
                    val crossingX = (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
                    if (point.x < crossingX) inside = !inside
                }
                j = i
            }
            return inside
        }

        override fun boundingRadiusMm(): Double = vertices.maxOf { it.radius }
    }
}
