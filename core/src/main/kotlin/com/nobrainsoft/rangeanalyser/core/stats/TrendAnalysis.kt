package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Whether the shooter is getting better, and by how much.
 *
 * A fitted line through a handful of range trips is easy to over-read - three sessions and a bit of
 * luck will "show" almost any trend you like. So the slope carries a bootstrap confidence band, and
 * a direction is only reported when that band stays clear of zero.
 */
object TrendAnalysis {

    fun over(
        sessions: List<AnalysedSession>,
        metric: TrendMetric,
        settings: TrendSettings = TrendSettings(),
    ): Trend? {
        val points = sessions
            .mapNotNull { session -> metric.extract(session)?.let { session to it } }
            .sortedBy { it.first.session.startedAtEpochMs }
            .mapIndexed { index, (session, value) ->
                TrendPoint(
                    sessionId = session.session.id,
                    label = session.session.name,
                    epochMs = session.session.startedAtEpochMs,
                    sessionIndex = index,
                    value = value,
                    shotCount = session.shotCount,
                )
            }

        if (points.size < settings.minimumSessions) return null

        val indices = points.map { it.sessionIndex.toDouble() }
        val values = points.map { it.value }
        val fit = Statistics.linearRegression(indices, values)

        val firstEpoch = points.first().epochMs
        val days = points.map { (it.epochMs - firstEpoch) / MILLIS_PER_DAY }
        val perDayFit = if (days.distinct().size >= 2) {
            Statistics.linearRegression(days, values)
        } else {
            null
        }

        val slopeCi = bootstrapSlope(indices, values, settings)
        val slopeIsReal = slopeCi != null && !slopeCi.contains(0.0)

        val direction = when {
            !slopeIsReal -> TrendDirection.NO_CHANGE_DETECTED
            // A falling group size is progress; a falling score is not.
            (fit.slope < 0) == metric.lowerIsBetter -> TrendDirection.IMPROVING
            else -> TrendDirection.WORSENING
        }

        return Trend(
            metric = metric,
            points = points,
            slopePerSession = fit.slope,
            slopePerDay = perDayFit?.slope,
            intercept = fit.intercept,
            rSquared = fit.rSquared,
            slopeCi = slopeCi,
            direction = direction,
        )
    }

    /**
     * Confidence band for the slope, by resampling whole sessions with replacement.
     *
     * Resampling sessions rather than individual shots is deliberate: the thing that varies between
     * range trips is the trip, not the shot.
     */
    private fun bootstrapSlope(
        x: List<Double>,
        y: List<Double>,
        settings: TrendSettings,
    ): ConfidenceInterval? {
        if (x.size < 3) return null
        val random = Random(settings.seed)
        val slopes = ArrayList<Double>(settings.iterations)

        repeat(settings.iterations) {
            val sampleX = ArrayList<Double>(x.size)
            val sampleY = ArrayList<Double>(y.size)
            repeat(x.size) {
                val pick = random.nextInt(x.size)
                sampleX += x[pick]
                sampleY += y[pick]
            }
            // A resample that happened to draw the same session every time has no slope to fit.
            if (sampleX.distinct().size >= 2) {
                slopes += Statistics.linearRegression(sampleX, sampleY).slope
            }
        }

        if (slopes.size < settings.iterations / 2) return null

        val tail = (1.0 - settings.level) / 2.0
        return ConfidenceInterval(
            lower = Statistics.percentile(slopes, tail),
            upper = Statistics.percentile(slopes, 1.0 - tail),
            level = settings.level,
        )
    }

    private const val MILLIS_PER_DAY = 86_400_000.0
}

@Serializable
data class TrendSettings(
    val minimumSessions: Int = 3,
    val level: Double = 0.95,
    val iterations: Int = 2000,
    val seed: Long = 20260303L,
)

@Serializable
data class Trend(
    val metric: TrendMetric,
    val points: List<TrendPoint>,
    val slopePerSession: Double,
    /** Null when every session shares a timestamp, so a per-day rate is meaningless. */
    val slopePerDay: Double?,
    val intercept: Double,
    val rSquared: Double,
    val slopeCi: ConfidenceInterval?,
    val direction: TrendDirection,
) {
    val sessionCount: Int get() = points.size

    val firstValue: Double get() = points.first().value

    val lastValue: Double get() = points.last().value

    /** Total change the fitted line accounts for across the whole history. */
    val fittedChange: Double get() = slopePerSession * (points.size - 1)

    /** Value predicted by the fit at a given session index; used to draw the trend line. */
    fun fittedValueAt(sessionIndex: Int): Double = slopePerSession * sessionIndex + intercept
}

@Serializable
data class TrendPoint(
    val sessionId: String,
    val label: String,
    val epochMs: Long,
    val sessionIndex: Int,
    val value: Double,
    val shotCount: Int,
)

enum class TrendDirection {
    IMPROVING,
    WORSENING,

    /** The fitted slope is not distinguishable from flat. Usually the honest answer. */
    NO_CHANGE_DETECTED,
}

enum class TrendMetric(val lowerIsBetter: Boolean) {
    MEAN_RADIUS_MM(lowerIsBetter = true),
    MEAN_RADIUS_MOA(lowerIsBetter = true),
    EXTREME_SPREAD_MM(lowerIsBetter = true),
    EXTREME_SPREAD_MOA(lowerIsBetter = true),

    /** How far the group sits from the aim point - a zeroing measure, not a precision one. */
    CENTROID_OFFSET_MM(lowerIsBetter = true),
    TOTAL_SCORE(lowerIsBetter = false),
    AVERAGE_SCORE_PER_SHOT(lowerIsBetter = false),
    ;

    fun extract(session: AnalysedSession): Double? = when (this) {
        MEAN_RADIUS_MM -> session.stats.meanRadiusMm
        MEAN_RADIUS_MOA -> session.stats.meanRadiusMoa()
        EXTREME_SPREAD_MM -> session.stats.extremeSpreadMm
        EXTREME_SPREAD_MOA -> session.stats.extremeSpreadMoa()
        CENTROID_OFFSET_MM -> session.stats.centroidOffset.radius
        TOTAL_SCORE -> session.score
            ?.let { it.totalDecimalScore ?: it.totalRingScore.toDouble() }
        AVERAGE_SCORE_PER_SHOT -> session.score
            ?.takeIf { it.shotCount > 0 }
            ?.let { (it.totalDecimalScore ?: it.totalRingScore.toDouble()) / it.shotCount }
    }
}
