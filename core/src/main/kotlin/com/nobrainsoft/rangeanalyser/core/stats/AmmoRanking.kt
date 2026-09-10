package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.geometry.Units
import com.nobrainsoft.rangeanalyser.core.geometry.centroid
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Ranks loads by how well they shoot, for load development.
 *
 * Measurements are pooled in **MOA rather than millimetres** so groups shot at different distances
 * can be compared at all; the millimetre figure is carried alongside but is only meaningful when
 * every session used the same distance, which [LoadPerformance.mixedDistances] reports.
 *
 * The ranking always states whether the top two loads are actually separable. Very often they are
 * not, and "these two are the same as far as this data can tell" is the result that saves the
 * shooter from rebuying a load that only won by luck.
 */
object AmmoRanking {

    fun rank(
        sessions: List<AnalysedSession>,
        settings: RankingSettings = RankingSettings(),
    ): RankingResult {
        val byAmmo = sessions
            .filter { it.shotCount > 0 }
            .groupBy { it.session.ammoId }

        val entries = byAmmo.mapNotNull { (ammoId, group) ->
            performanceOf(ammoId, group, settings)
        }.sortedBy { it.meanRadiusMoa }

        val topTwoSeparable = when {
            entries.size < 2 -> false
            else -> {
                val best = entries[0].meanRadiusMoaCi
                val runnerUp = entries[1].meanRadiusMoaCi
                best != null && runnerUp != null && best.upper < runnerUp.lower
            }
        }

        return RankingResult(
            entries = entries,
            topTwoSeparable = topTwoSeparable,
        )
    }

    private fun performanceOf(
        ammoId: String?,
        sessions: List<AnalysedSession>,
        settings: RankingSettings,
    ): LoadPerformance? {
        // Radii are measured from each session's own centre of impact, so a load that shoots tight
        // but to a different zero is not penalised for it.
        val radiiMoaPerSession = sessions.map { session ->
            val positions = session.positions
            if (positions.isEmpty()) return@map emptyList()
            val centre = positions.centroid()
            positions.map { Units.mmToMoa(it.distanceTo(centre), session.session.distanceM) }
        }

        val pooledMoa = radiiMoaPerSession.flatten()
        if (pooledMoa.isEmpty()) return null

        val distances = sessions.map { it.session.distanceM }.distinct()
        val pooledMm = sessions.flatMap { session ->
            val positions = session.positions
            if (positions.isEmpty()) {
                emptyList()
            } else {
                val centre = positions.centroid()
                positions.map { it.distanceTo(centre) }
            }
        }

        return LoadPerformance(
            ammoId = ammoId,
            ammoName = sessions.firstNotNullOfOrNull { it.ammo?.displayName } ?: UNKNOWN_LOAD,
            sessionCount = sessions.size,
            totalShots = pooledMoa.size,
            meanRadiusMoa = Statistics.mean(pooledMoa),
            meanRadiusMoaCi = bootstrapMeanRadius(radiiMoaPerSession, settings),
            meanRadiusMm = Statistics.mean(pooledMm),
            bestSessionMeanRadiusMoa = radiiMoaPerSession
                .filter { it.isNotEmpty() }
                .minOfOrNull { Statistics.mean(it) },
            mixedDistances = distances.size > 1,
        )
    }

    /**
     * Confidence interval for a load's mean radius.
     *
     * With several sessions the resampling unit is the **session**, because range trips vary for
     * reasons that have nothing to do with the ammunition - light, wind, the shooter's day. Falling
     * back to resampling shots for a single session gives a narrower interval that only describes
     * that one string, which is why [LoadPerformance.sessionCount] is reported next to it.
     */
    private fun bootstrapMeanRadius(
        radiiPerSession: List<List<Double>>,
        settings: RankingSettings,
    ): ConfidenceInterval? {
        val populated = radiiPerSession.filter { it.isNotEmpty() }
        if (populated.isEmpty()) return null

        val random = Random(settings.seed)
        val resampleSessions = populated.size >= settings.minimumSessionsForSessionBootstrap
        val flat = populated.flatten()
        if (flat.size < 3) return null

        val means = ArrayList<Double>(settings.iterations)
        repeat(settings.iterations) {
            val sample = if (resampleSessions) {
                (1..populated.size).flatMap { populated[random.nextInt(populated.size)] }
            } else {
                List(flat.size) { flat[random.nextInt(flat.size)] }
            }
            means += Statistics.mean(sample)
        }

        val tail = (1.0 - settings.level) / 2.0
        return ConfidenceInterval(
            lower = Statistics.percentile(means, tail),
            upper = Statistics.percentile(means, 1.0 - tail),
            level = settings.level,
        )
    }

    private const val UNKNOWN_LOAD = "Unrecorded load"
}

@Serializable
data class RankingSettings(
    val level: Double = 0.95,
    val iterations: Int = 2000,
    val seed: Long = 20260404L,
    val minimumSessionsForSessionBootstrap: Int = 3,
)

@Serializable
data class RankingResult(
    /** Tightest first. */
    val entries: List<LoadPerformance>,
    /** True only when the best load's interval clears the runner-up's entirely. */
    val topTwoSeparable: Boolean,
) {
    val best: LoadPerformance? get() = entries.firstOrNull()
}

@Serializable
data class LoadPerformance(
    val ammoId: String?,
    val ammoName: String,
    val sessionCount: Int,
    val totalShots: Int,
    val meanRadiusMoa: Double,
    val meanRadiusMoaCi: ConfidenceInterval?,
    /** Only comparable across loads when [mixedDistances] is false. */
    val meanRadiusMm: Double,
    val bestSessionMeanRadiusMoa: Double?,
    val mixedDistances: Boolean,
) {
    /** A single session cannot separate the load from the day it was shot. */
    val isSingleSession: Boolean get() = sessionCount < 2
}
