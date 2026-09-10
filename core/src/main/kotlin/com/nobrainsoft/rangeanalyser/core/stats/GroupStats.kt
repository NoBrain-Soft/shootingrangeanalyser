package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.geometry.Units
import com.nobrainsoft.rangeanalyser.core.geometry.centroid
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.model.scoring
import kotlinx.serialization.Serializable
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Everything measurable about a group.
 *
 * Two design choices here are deliberate and worth knowing about.
 *
 * **Mean radius is treated as the headline number, not extreme spread.** Extreme spread uses only
 * the two worst shots and throws the rest away, so it is a noisy estimator: two identical rifles
 * shooting five rounds each will routinely differ by 40% on extreme spread alone. Mean radius uses
 * every shot and settles down far faster.
 *
 * **Confidence intervals are computed, not decorative.** They come from a parametric bootstrap -
 * fit the group's covariance, simulate thousands of fresh groups of the same size from it, and
 * read off the percentiles. That answers the question a shooter actually has ("if I shot this
 * again, what would I see?") and is what stops the app from calling a 3 mm improvement on five
 * shots an improvement.
 */
@Serializable
data class GroupStats(
    val shotCount: Int,
    val distanceM: Double,
    val bulletDiameterMm: Double?,
    val centroid: PointMm,
    val pointOfAim: PointMm,
    /** Where the group sits relative to where the shooter was aiming. Drives zeroing advice. */
    val centroidOffset: PointMm,
    /** Widest centre-to-centre separation. */
    val extremeSpreadMm: Double,
    /** The same measured edge to edge, which is how a caliper on paper reads it. */
    val extremeSpreadOutsideMm: Double?,
    /** Indices, into the scoring shot list, of the two shots that define the extreme spread. */
    val extremeSpreadPair: Pair<Int, Int>?,
    val meanRadiusMm: Double,
    val medianRadiusMm: Double,
    val sigmaXMm: Double,
    val sigmaYMm: Double,
    val rayleighSigmaMm: Double,
    val cep50Mm: Double,
    val r95Mm: Double,
    val axes: PrincipalAxes,
    val meanRadiusCi: ConfidenceInterval?,
    val extremeSpreadCi: ConfidenceInterval?,
    val flyers: List<Flyer>,
    val order: OrderStats?,
) {
    /**
     * Vertical spread divided by horizontal spread. Above ~1.8 usually points at the ammunition or
     * the shooter's breathing; below ~0.55 at wind, trigger control or an unstable position.
     */
    val verticalToHorizontalRatio: Double
        get() = if (sigmaXMm <= 0.0) Double.POSITIVE_INFINITY else sigmaYMm / sigmaXMm

    fun extremeSpreadMoa(): Double = Units.mmToMoa(extremeSpreadMm, distanceM)

    fun extremeSpreadMil(): Double = Units.mmToMil(extremeSpreadMm, distanceM)

    fun meanRadiusMoa(): Double = Units.mmToMoa(meanRadiusMm, distanceM)

    fun meanRadiusMil(): Double = Units.mmToMil(meanRadiusMm, distanceM)

    /** True when the group is small enough that any conclusion drawn from it is shaky. */
    val isSmallSample: Boolean get() = shotCount < 5

    companion object {
        /**
         * Family-wise false-positive rate for flyer detection: at most a 1-in-100 chance of calling
         * *any* shot in an ordinary group a flyer, not a 1-in-100 chance per shot.
         */
        const val FLYER_FAMILY_ALPHA = 0.01

        /** Shot positions are two-dimensional. Named because it appears in the F-test degrees of freedom. */
        private const val DIMENSIONS = 2

        fun of(
            shots: List<Shot>,
            distanceM: Double,
            caliber: Caliber? = null,
            pointOfAim: PointMm = PointMm.ORIGIN,
            bootstrap: BootstrapSettings = BootstrapSettings(),
        ): GroupStats? {
            val ordered = shots.scoring()
            if (ordered.isEmpty()) return null

            val positions = ordered.map { it.position }
            val centre = positions.centroid()
            val radii = positions.map { it.distanceTo(centre) }

            val (spread, spreadPair) = extremeSpread(positions)
            val covariance = Statistics.covariance(positions)
            val axes = covariance.principalAxes()

            val sigmaX = sqrt(covariance.xx.coerceAtLeast(0.0))
            val sigmaY = sqrt(covariance.yy.coerceAtLeast(0.0))
            val rayleighSigma = sqrt((covariance.xx + covariance.yy).coerceAtLeast(0.0) / 2.0)

            val simulation = if (positions.size >= 3 && bootstrap.enabled) {
                simulate(covariance, positions.size, bootstrap)
            } else {
                null
            }

            return GroupStats(
                shotCount = ordered.size,
                distanceM = distanceM,
                bulletDiameterMm = caliber?.bulletDiameterMm,
                centroid = centre,
                pointOfAim = pointOfAim,
                centroidOffset = centre - pointOfAim,
                extremeSpreadMm = spread,
                extremeSpreadOutsideMm = caliber?.let { spread + it.bulletDiameterMm },
                extremeSpreadPair = spreadPair,
                meanRadiusMm = Statistics.mean(radii),
                medianRadiusMm = Statistics.median(radii),
                sigmaXMm = sigmaX,
                sigmaYMm = sigmaY,
                rayleighSigmaMm = rayleighSigma,
                cep50Mm = circularErrorProbable(axes),
                r95Mm = radiusContaining95Percent(rayleighSigma),
                axes = axes,
                meanRadiusCi = simulation?.meanRadiusCi,
                extremeSpreadCi = simulation?.extremeSpreadCi,
                flyers = findFlyers(ordered),
                order = OrderStats.of(ordered),
            )
        }

        /**
         * Widest centre-to-centre distance, and which two shots produced it.
         *
         * Quadratic, which is irrelevant at the group sizes anyone shoots.
         */
        fun extremeSpread(positions: List<PointMm>): Pair<Double, Pair<Int, Int>?> {
            if (positions.size < 2) return 0.0 to null

            var widest = 0.0
            var pair: Pair<Int, Int>? = null
            for (i in positions.indices) {
                for (j in i + 1 until positions.size) {
                    val distance = positions[i].distanceTo(positions[j])
                    if (distance > widest) {
                        widest = distance
                        pair = i to j
                    }
                }
            }
            return widest to pair
        }

        fun meanRadius(positions: List<PointMm>): Double {
            if (positions.isEmpty()) return 0.0
            val centre = positions.centroid()
            return Statistics.mean(positions.map { it.distanceTo(centre) })
        }

        /**
         * Radius of the circle containing half the shots.
         *
         * The standard elliptical approximation; for a round group it reduces to 1.177 sigma, which
         * is the exact Rayleigh result. Very elongated groups fall back to the univariate form.
         */
        private fun circularErrorProbable(axes: PrincipalAxes): Double {
            val major = axes.majorSigmaMm
            val minor = axes.minorSigmaMm
            if (major <= 0.0) return 0.0
            return if (minor / major >= 0.3) {
                0.615 * major + 0.562 * minor
            } else {
                0.675 * major
            }
        }

        /**
         * Radius containing 95% of shots, from the Rayleigh distribution.
         *
         * Exact for a round group and an approximation otherwise; the app reports it alongside the
         * elongation so a very oval group is visibly not the case this assumes.
         */
        private fun radiusContaining95Percent(rayleighSigma: Double): Double =
            rayleighSigma * sqrt(Statistics.chiSquare2dfCritical(0.95))

        /**
         * Flags shots that do not belong to the same distribution as the rest.
         *
         * Three things have to be right here, and getting any of them wrong produces an outlier
         * test that invents flyers in perfectly ordinary groups:
         *
         * 1. The mean and covariance are recomputed *excluding* the candidate. Including it would
         *    let a single wild shot inflate the spread enough to make itself look normal - the
         *    masking effect that defeats naive outlier tests on small samples.
         * 2. Because that covariance is estimated rather than known, the squared distance is not
         *    chi-square. It follows Hotelling's prediction T-squared, which maps onto F(2, m-2).
         *    Using the chi-square threshold instead is far too permissive.
         * 3. Every shot in the string is tested, so the per-shot level is Bonferroni-corrected by
         *    the shot count. Without it, a ten-shot group produces a false flyer roughly one time
         *    in ten - often enough for the user to stop believing the feature.
         *
         * The combined effect is a test that stays quiet on small strings, which is the honest
         * outcome: five shots simply cannot establish that one of them was an outlier.
         */
        private fun findFlyers(shots: List<Shot>): List<Flyer> {
            val referenceSize = shots.size - 1
            // Need more reference shots than dimensions for the covariance to be usable at all.
            if (referenceSize - DIMENSIONS < 1) return emptyList()

            val perShotLevel = 1.0 - FLYER_FAMILY_ALPHA / shots.size
            val threshold = flyerThreshold(referenceSize, perShotLevel)

            return shots.mapIndexedNotNull { index, shot ->
                val others = shots.filterIndexed { i, _ -> i != index }.map { it.position }
                val othersCentre = others.centroid()
                val inverse = Statistics.covariance(others).inverse() ?: return@mapIndexedNotNull null

                val delta = shot.position - othersCentre
                val distanceSquared = delta.x * delta.x * inverse.xx +
                    2.0 * delta.x * delta.y * inverse.xy +
                    delta.y * delta.y * inverse.yy

                if (distanceSquared > threshold) {
                    Flyer(
                        shotIndex = index,
                        shotId = shot.id,
                        orderIndex = shot.orderIndex,
                        mahalanobisDistanceSquared = distanceSquared,
                        thresholdSquared = threshold,
                    )
                } else {
                    null
                }
            }
        }

        /**
         * Squared Mahalanobis distance a shot must exceed to count as a flyer.
         *
         * Derived from Hotelling's prediction T-squared for a point held out of the sample that
         * estimated the mean and covariance: with m reference shots in p dimensions,
         * `((m-p)/(p(m-1))) * (m/(m+1)) * D^2` is distributed as `F(p, m-p)`.
         */
        internal fun flyerThreshold(referenceSize: Int, level: Double): Double {
            val m = referenceSize.toDouble()
            val p = DIMENSIONS.toDouble()
            val fCritical = Statistics.fQuantile2Numerator(level, referenceSize - DIMENSIONS)
            return fCritical * (p * (m - 1.0) / (m - p)) * ((m + 1.0) / m)
        }

        private fun simulate(
            covariance: Covariance2x2,
            shotCount: Int,
            settings: BootstrapSettings,
        ): SimulationResult? {
            val cholesky = covariance.choleskyOrNull() ?: return null
            val random = Random(settings.seed)
            val iterations = settings.iterationsFor(shotCount)

            val spreads = DoubleArray(iterations)
            val radii = DoubleArray(iterations)

            repeat(iterations) { iteration ->
                val simulated = List(shotCount) {
                    cholesky.transform(
                        Statistics.nextGaussian(random),
                        Statistics.nextGaussian(random),
                    )
                }
                spreads[iteration] = extremeSpread(simulated).first
                radii[iteration] = meanRadius(simulated)
            }

            return SimulationResult(
                extremeSpreadCi = percentileInterval(spreads.toList(), settings.level),
                meanRadiusCi = percentileInterval(radii.toList(), settings.level),
            )
        }

        private fun percentileInterval(values: List<Double>, level: Double): ConfidenceInterval {
            val tail = (1.0 - level) / 2.0
            return ConfidenceInterval(
                lower = Statistics.percentile(values, tail),
                upper = Statistics.percentile(values, 1.0 - tail),
                level = level,
            )
        }
    }
}

private data class SimulationResult(
    val extremeSpreadCi: ConfidenceInterval,
    val meanRadiusCi: ConfidenceInterval,
)

/**
 * Controls the parametric bootstrap. The seed is fixed so the same group always produces the same
 * interval - a confidence band that flickers between runs would rightly destroy trust in it.
 */
@Serializable
data class BootstrapSettings(
    val enabled: Boolean = true,
    val level: Double = 0.95,
    val seed: Long = 20260101L,
    val iterations: Int = 2000,
) {
    /** Extreme spread is quadratic in shot count, so big strings get fewer iterations. */
    fun iterationsFor(shotCount: Int): Int = if (shotCount > 30) iterations / 4 else iterations
}

@Serializable
data class Flyer(
    val shotIndex: Int,
    val shotId: String,
    val orderIndex: Int,
    val mahalanobisDistanceSquared: Double,
    val thresholdSquared: Double,
)

/**
 * Statistics that only mean something when the firing order is known - live mode, or a photo the
 * user has numbered.
 */
@Serializable
data class OrderStats(
    /** How far the first shot sits from the centre of the rest. */
    val coldBoreOffsetMm: Double?,
    /** That offset as a multiple of the remaining group's mean radius. */
    val coldBoreOffsetRatio: Double?,
    /** Per-shot movement of the point of impact across the string. */
    val driftMmPerShot: PointMm,
    val driftRSquaredX: Double,
    val driftRSquaredY: Double,
    val firstHalfMeanRadiusMm: Double,
    val secondHalfMeanRadiusMm: Double,
) {
    /** Strength of the walk across the string, 0..1. */
    val driftStrength: Double get() = maxOf(driftRSquaredX, driftRSquaredY)

    val driftPerShotMm: Double get() = driftMmPerShot.radius

    companion object {
        fun of(shots: List<Shot>): OrderStats? {
            if (shots.size < 4) return null

            val indices = shots.map { it.orderIndex.toDouble() }
            val fitX = Statistics.linearRegression(indices, shots.map { it.position.x })
            val fitY = Statistics.linearRegression(indices, shots.map { it.position.y })

            val rest = shots.drop(1).map { it.position }
            val restCentre = rest.centroid()
            val coldBoreOffset = shots.first().position.distanceTo(restCentre)
            val restMeanRadius = GroupStats.meanRadius(rest)

            val midpoint = shots.size / 2
            val firstHalf = shots.take(midpoint).map { it.position }
            val secondHalf = shots.drop(midpoint).map { it.position }

            return OrderStats(
                coldBoreOffsetMm = coldBoreOffset,
                coldBoreOffsetRatio = if (restMeanRadius > 0.0) coldBoreOffset / restMeanRadius else null,
                driftMmPerShot = PointMm(fitX.slope, fitY.slope),
                driftRSquaredX = fitX.rSquared,
                driftRSquaredY = fitY.rSquared,
                firstHalfMeanRadiusMm = GroupStats.meanRadius(firstHalf),
                secondHalfMeanRadiusMm = GroupStats.meanRadius(secondHalf),
            )
        }
    }
}
