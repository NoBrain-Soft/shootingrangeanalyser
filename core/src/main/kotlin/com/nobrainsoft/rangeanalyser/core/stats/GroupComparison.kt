package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.geometry.centroid
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.random.Random

/**
 * Compares two groups and says whether the difference is real.
 *
 * This is the feature that makes the rest trustworthy. Shooters compare a new load against an old
 * one, or this week against last week, and the difference between two five-shot groups is almost
 * always noise. An app that reports "12% tighter!" every time somebody shoots a slightly better
 * group teaches them nothing and costs them money in ammunition.
 *
 * The test is a **permutation test on shot positions**, which assumes very little. Both groups are
 * centred on their own point of impact, the residuals are pooled, then repeatedly dealt back out
 * into two groups of the original sizes. If the observed difference in mean radius sits comfortably
 * inside that shuffled distribution, the two groups are not distinguishable.
 */
object GroupComparison {

    fun compare(
        a: AnalysedSession,
        b: AnalysedSession,
        settings: ComparisonSettings = ComparisonSettings(),
    ): ComparisonResult = compare(
        aPositions = a.positions,
        bPositions = b.positions,
        aLabel = a.session.name,
        bLabel = b.session.name,
        distanceM = a.session.distanceM,
        settings = settings,
    )

    fun compare(
        aPositions: List<PointMm>,
        bPositions: List<PointMm>,
        aLabel: String = "A",
        bLabel: String = "B",
        distanceM: Double = 100.0,
        settings: ComparisonSettings = ComparisonSettings(),
    ): ComparisonResult {
        val aMeanRadius = GroupStats.meanRadius(aPositions)
        val bMeanRadius = GroupStats.meanRadius(bPositions)
        val observedDifference = bMeanRadius - aMeanRadius

        val aSpread = GroupStats.extremeSpread(aPositions).first
        val bSpread = GroupStats.extremeSpread(bPositions).first

        val centroidShift = if (aPositions.isNotEmpty() && bPositions.isNotEmpty()) {
            bPositions.centroid().distanceTo(aPositions.centroid())
        } else {
            0.0
        }

        if (aPositions.size < MIN_SHOTS || bPositions.size < MIN_SHOTS) {
            return ComparisonResult(
                aLabel = aLabel,
                bLabel = bLabel,
                distanceM = distanceM,
                aShotCount = aPositions.size,
                bShotCount = bPositions.size,
                aMeanRadiusMm = aMeanRadius,
                bMeanRadiusMm = bMeanRadius,
                aExtremeSpreadMm = aSpread,
                bExtremeSpreadMm = bSpread,
                centroidShiftMm = centroidShift,
                pValue = null,
                verdict = ComparisonVerdict.INSUFFICIENT_DATA,
                shotsPerGroupToDecide = null,
                advice = ResolutionAdvice.SHOOT_MORE,
            )
        }

        val pValue = permutationPValue(aPositions, bPositions, observedDifference, settings)
        val significant = pValue <= settings.alpha

        val verdict = when {
            !significant -> ComparisonVerdict.INDISTINGUISHABLE
            observedDifference < 0 -> ComparisonVerdict.B_TIGHTER
            else -> ComparisonVerdict.A_TIGHTER
        }

        // Only worth asking "how many more shots?" when the question is still open.
        val needed = if (significant) null else shotsNeeded(aPositions, bPositions, settings)

        return ComparisonResult(
            aLabel = aLabel,
            bLabel = bLabel,
            distanceM = distanceM,
            aShotCount = aPositions.size,
            bShotCount = bPositions.size,
            aMeanRadiusMm = aMeanRadius,
            bMeanRadiusMm = bMeanRadius,
            aExtremeSpreadMm = aSpread,
            bExtremeSpreadMm = bSpread,
            centroidShiftMm = centroidShift,
            pValue = pValue,
            verdict = verdict,
            shotsPerGroupToDecide = (needed as? ShotsNeeded.Count)?.shotsPerGroup,
            advice = when {
                significant -> ResolutionAdvice.ALREADY_SETTLED
                needed is ShotsNeeded.Count -> ResolutionAdvice.SHOOT_MORE
                needed is ShotsNeeded.BeyondReach -> ResolutionAdvice.DIFFERENCE_TOO_SMALL_TO_MATTER
                else -> ResolutionAdvice.CANNOT_ESTIMATE
            },
        )
    }

    /**
     * Fraction of reshuffles that produce a difference at least as large as the observed one.
     */
    private fun permutationPValue(
        aPositions: List<PointMm>,
        bPositions: List<PointMm>,
        observedDifference: Double,
        settings: ComparisonSettings,
    ): Double {
        // Centring first is what makes this a test of *dispersion* rather than of position: two
        // groups can be the same size and sit in different places, and that is a zeroing question,
        // not a precision one.
        val pooled = centreOn(aPositions) + centreOn(bPositions)
        val random = Random(settings.seed)
        val target = abs(observedDifference)

        var atLeastAsExtreme = 0
        repeat(settings.iterations) {
            val shuffled = pooled.shuffled(random)
            val left = shuffled.take(aPositions.size)
            val right = shuffled.drop(aPositions.size)
            val difference = GroupStats.meanRadius(right) - GroupStats.meanRadius(left)
            if (abs(difference) >= target) atLeastAsExtreme++
        }

        // The observed arrangement is itself one of the possibilities, hence the +1 on both sides;
        // this keeps the p-value from ever being exactly zero, which it never truly is.
        return (atLeastAsExtreme + 1.0) / (settings.iterations + 1.0)
    }

    /**
     * How many shots per group it would take to resolve a difference this size.
     *
     * Simulates from each group's fitted covariance at a range of sample sizes and finds the
     * smallest that would detect the observed effect reliably. Turns "inconclusive" into something
     * the shooter can act on.
     */
    private fun shotsNeeded(
        aPositions: List<PointMm>,
        bPositions: List<PointMm>,
        settings: ComparisonSettings,
    ): ShotsNeeded {
        val covarianceA = Statistics.covariance(aPositions).choleskyOrNull()
            ?: return ShotsNeeded.Unknown
        val covarianceB = Statistics.covariance(bPositions).choleskyOrNull()
            ?: return ShotsNeeded.Unknown
        val pooled = Statistics.covariance(centreOn(aPositions) + centreOn(bPositions))
            .choleskyOrNull() ?: return ShotsNeeded.Unknown

        for (candidate in settings.sampleSizeLadder) {
            val random = Random(settings.seed + candidate)

            // Null distribution: both groups drawn from the pooled spread.
            val nullDifferences = DoubleArray(settings.powerIterations) {
                val left = simulate(pooled, candidate, random)
                val right = simulate(pooled, candidate, random)
                abs(GroupStats.meanRadius(right) - GroupStats.meanRadius(left))
            }
            val critical = Statistics.percentile(nullDifferences.toList(), 1.0 - settings.alpha)

            // Alternative: each group drawn from its own fitted spread.
            var detected = 0
            repeat(settings.powerIterations) {
                val left = simulate(covarianceA, candidate, random)
                val right = simulate(covarianceB, candidate, random)
                if (abs(GroupStats.meanRadius(right) - GroupStats.meanRadius(left)) > critical) {
                    detected++
                }
            }

            if (detected.toDouble() / settings.powerIterations >= settings.targetPower) {
                return ShotsNeeded.Count(candidate)
            }
        }

        // The two fitted spreads are so close that even the largest sample size on the ladder would
        // not separate them. That is not a failure to answer - it *is* the answer, and a more
        // useful one than a bigger number: these loads shoot the same.
        return if (settings.sampleSizeLadder.isEmpty()) ShotsNeeded.Unknown else ShotsNeeded.BeyondReach
    }

    private sealed interface ShotsNeeded {
        data class Count(val shotsPerGroup: Int) : ShotsNeeded

        /** No sample size on the ladder reaches the target power. */
        data object BeyondReach : ShotsNeeded

        /** The group is degenerate, so there is nothing to simulate from. */
        data object Unknown : ShotsNeeded
    }

    private fun simulate(cholesky: Cholesky2x2, count: Int, random: Random): List<PointMm> =
        List(count) {
            cholesky.transform(Statistics.nextGaussian(random), Statistics.nextGaussian(random))
        }

    private fun centreOn(positions: List<PointMm>): List<PointMm> {
        if (positions.isEmpty()) return emptyList()
        val centre = positions.centroid()
        return positions.map { it - centre }
    }

    private const val MIN_SHOTS = 3
}

@Serializable
data class ComparisonSettings(
    val alpha: Double = 0.05,
    val iterations: Int = 3000,
    val seed: Long = 20260202L,
    val powerIterations: Int = 300,
    val targetPower: Double = 0.8,
    val sampleSizeLadder: List<Int> = listOf(5, 10, 15, 20, 30, 40, 50, 75, 100),
)

@Serializable
data class ComparisonResult(
    val aLabel: String,
    val bLabel: String,
    val distanceM: Double,
    val aShotCount: Int,
    val bShotCount: Int,
    val aMeanRadiusMm: Double,
    val bMeanRadiusMm: Double,
    val aExtremeSpreadMm: Double,
    val bExtremeSpreadMm: Double,
    /** How far the point of impact moved. A zeroing question, separate from precision. */
    val centroidShiftMm: Double,
    /** Null when the groups are too small to test at all. */
    val pValue: Double?,
    val verdict: ComparisonVerdict,
    /**
     * Shots per group that would settle the question. Present only when [advice] is
     * [ResolutionAdvice.SHOOT_MORE].
     */
    val shotsPerGroupToDecide: Int?,
    /** What the shooter should actually do about this comparison. */
    val advice: ResolutionAdvice,
) {
    /** Negative means B is tighter. */
    val meanRadiusDeltaMm: Double get() = bMeanRadiusMm - aMeanRadiusMm

    val meanRadiusPercentChange: Double
        get() = if (aMeanRadiusMm <= 0.0) 0.0 else 100.0 * meanRadiusDeltaMm / aMeanRadiusMm

    val extremeSpreadDeltaMm: Double get() = bExtremeSpreadMm - aExtremeSpreadMm

    val isConclusive: Boolean
        get() = verdict == ComparisonVerdict.A_TIGHTER || verdict == ComparisonVerdict.B_TIGHTER
}

enum class ResolutionAdvice {
    /** The comparison already has an answer. */
    ALREADY_SETTLED,

    /** More shots would resolve it; see [ComparisonResult.shotsPerGroupToDecide]. */
    SHOOT_MORE,

    /**
     * The two loads are close enough that no practical amount of shooting would separate them.
     * Worth saying out loud: it stops the shooter burning ammunition chasing a difference that is
     * not there.
     */
    DIFFERENCE_TOO_SMALL_TO_MATTER,

    /** The groups are degenerate, so power cannot be estimated. */
    CANNOT_ESTIMATE,
}

enum class ComparisonVerdict {
    A_TIGHTER,
    B_TIGHTER,

    /** A difference exists on paper but is well within what chance produces. */
    INDISTINGUISHABLE,

    /** Fewer shots than any test could work with. */
    INSUFFICIENT_DATA,
}
