package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.model.Firearm
import com.nobrainsoft.rangeanalyser.core.model.FirearmType
import com.nobrainsoft.rangeanalyser.core.model.Session
import com.nobrainsoft.rangeanalyser.core.model.SessionMode
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComparisonAndTrendTest {

    // --- Comparison ---------------------------------------------------------------------------

    @Test
    fun `a clearly tighter group is called tighter`() {
        val loose = gaussianGroup(40, sigma = 25.0, seed = 1)
        val tight = gaussianGroup(40, sigma = 8.0, seed = 2)

        val result = GroupComparison.compare(loose, tight, aLabel = "Bulk", bLabel = "Match")

        assertEquals(ComparisonVerdict.B_TIGHTER, result.verdict)
        assertTrue(result.isConclusive)
        assertTrue(result.meanRadiusDeltaMm < 0, "B should have the smaller mean radius")
        assertNull(result.shotsPerGroupToDecide, "no need to shoot more, the answer is already clear")
    }

    @Test
    fun `two groups from the same load are not declared different`() {
        val a = gaussianGroup(20, sigma = 12.0, seed = 10)
        val b = gaussianGroup(20, sigma = 12.0, seed = 11)

        val result = GroupComparison.compare(a, b)

        assertEquals(ComparisonVerdict.INDISTINGUISHABLE, result.verdict)
    }

    @Test
    fun `the false discovery rate is actually controlled`() {
        // The headline promise of this feature is that it will not invent improvements. Run many
        // comparisons between groups drawn from an identical distribution and count how often it
        // claims a winner; the test level is 5%, so a few are expected and a flood is a bug.
        val cheap = ComparisonSettings(iterations = 600, sampleSizeLadder = emptyList())
        var conclusive = 0
        val trials = 40

        repeat(trials) { trial ->
            val a = gaussianGroup(15, sigma = 14.0, seed = 1000 + trial * 2)
            val b = gaussianGroup(15, sigma = 14.0, seed = 1001 + trial * 2)
            if (GroupComparison.compare(a, b, settings = cheap).isConclusive) conclusive++
        }

        val rate = conclusive.toDouble() / trials
        assertTrue(rate <= 0.15, "claimed a winner in ${(rate * 100).toInt()}% of identical pairs")
    }

    @Test
    fun `an inconclusive comparison says how many shots would settle it`() {
        // Five-shot groups from loads that differ modestly. Sometimes the draw is clear enough to
        // call and sometimes it is not - both are legitimate - but whenever the answer is "cannot
        // tell", the app owes the shooter a number rather than a shrug.
        var inconclusiveSeen = 0

        repeat(10) { trial ->
            val a = gaussianGroup(5, sigma = 10.0, seed = 21 + trial * 2)
            val b = gaussianGroup(5, sigma = 13.0, seed = 22 + trial * 2)
            val result = GroupComparison.compare(a, b)

            if (result.verdict == ComparisonVerdict.INDISTINGUISHABLE) {
                inconclusiveSeen++
                when (result.advice) {
                    // Either say how many more shots it would take...
                    ResolutionAdvice.SHOOT_MORE -> {
                        val needed = assertNotNull(result.shotsPerGroupToDecide)
                        assertTrue(needed > 5, "needs more than the five already shot, got $needed")
                    }
                    // ...or say that no amount of shooting would separate them, which is just as
                    // actionable and saves the ammunition.
                    ResolutionAdvice.DIFFERENCE_TOO_SMALL_TO_MATTER ->
                        assertNull(result.shotsPerGroupToDecide)

                    else -> throw AssertionError(
                        "an inconclusive comparison must advise something, got ${result.advice}",
                    )
                }
            } else {
                assertEquals(ResolutionAdvice.ALREADY_SETTLED, result.advice)
                assertNull(
                    result.shotsPerGroupToDecide,
                    "a settled question does not need more shots",
                )
            }
        }

        assertTrue(inconclusiveSeen > 0, "expected at least one inconclusive pair out of ten")
    }

    @Test
    fun `a difference beyond any practical sample size is said out loud`() {
        // When no sample size worth shooting would resolve the difference, the app must say that
        // rather than return a bare "don't know". Telling the shooter to go burn another 500 rounds
        // chasing a difference that is not there is the failure mode this exists to prevent.
        // The ladder is capped here so the branch is exercised deterministically rather than
        // depending on a lucky draw.
        val a = gaussianGroup(20, sigma = 12.0, seed = 10)
        val b = gaussianGroup(20, sigma = 12.0, seed = 11)

        val result = GroupComparison.compare(
            a,
            b,
            settings = ComparisonSettings(sampleSizeLadder = listOf(5, 10)),
        )

        assertEquals(ComparisonVerdict.INDISTINGUISHABLE, result.verdict)
        assertEquals(ResolutionAdvice.DIFFERENCE_TOO_SMALL_TO_MATTER, result.advice)
        assertNull(result.shotsPerGroupToDecide)
    }

    @Test
    fun `groups too small to test are reported as such rather than guessed at`() {
        val result = GroupComparison.compare(
            gaussianGroup(2, sigma = 10.0, seed = 31),
            gaussianGroup(2, sigma = 30.0, seed = 32),
        )

        assertEquals(ComparisonVerdict.INSUFFICIENT_DATA, result.verdict)
        assertNull(result.pValue)
    }

    @Test
    fun `a shifted zero is not mistaken for a precision change`() {
        // Same dispersion, 100 mm apart. That is a sight adjustment, not a better load, and the
        // comparison must keep the two ideas separate.
        val a = gaussianGroup(25, sigma = 12.0, seed = 41)
        val b = gaussianGroup(25, sigma = 12.0, seed = 42).map { it + PointMm(100.0, 0.0) }

        val result = GroupComparison.compare(a, b)

        assertEquals(ComparisonVerdict.INDISTINGUISHABLE, result.verdict)
        assertTrue(result.centroidShiftMm > 90.0, "the shift itself should still be reported")
    }

    @Test
    fun `comparison results are reproducible`() {
        val a = gaussianGroup(15, sigma = 10.0, seed = 51)
        val b = gaussianGroup(15, sigma = 13.0, seed = 52)

        assertEquals(GroupComparison.compare(a, b), GroupComparison.compare(a, b))
    }

    // --- Trends -------------------------------------------------------------------------------

    @Test
    fun `a steadily improving history is recognised`() {
        val sessions = (0 until 8).map { index ->
            analysedSession(
                id = "s$index",
                positions = gaussianGroup(10, sigma = 20.0 - index * 1.8, seed = 100 + index),
                epochMs = index * DAY_MS,
            )
        }

        val trend = assertNotNull(TrendAnalysis.over(sessions, TrendMetric.MEAN_RADIUS_MM))

        assertEquals(TrendDirection.IMPROVING, trend.direction)
        assertTrue(trend.slopePerSession < 0, "mean radius should be falling")
        assertNotNull(trend.slopePerDay)
    }

    @Test
    fun `a flat noisy history is not spun into progress`() {
        val sessions = (0 until 8).map { index ->
            analysedSession(
                id = "s$index",
                positions = gaussianGroup(10, sigma = 15.0, seed = 200 + index),
                epochMs = index * DAY_MS,
            )
        }

        val trend = assertNotNull(TrendAnalysis.over(sessions, TrendMetric.MEAN_RADIUS_MM))
        assertEquals(TrendDirection.NO_CHANGE_DETECTED, trend.direction)
    }

    @Test
    fun `rising scores count as improvement even though the number goes up`() {
        // Groups tightening onto the middle of a scored face means the score climbs; the direction
        // of "better" is per metric, not universal.
        val sessions = (0 until 8).map { index ->
            analysedSession(
                id = "s$index",
                positions = gaussianGroup(10, sigma = 30.0 - index * 3.0, seed = 300 + index),
                epochMs = index * DAY_MS,
                target = TargetLibrary.ISSF_PISTOL_25M,
            )
        }

        val radiusTrend = assertNotNull(TrendAnalysis.over(sessions, TrendMetric.MEAN_RADIUS_MM))
        val scoreTrend = assertNotNull(
            TrendAnalysis.over(sessions, TrendMetric.AVERAGE_SCORE_PER_SHOT),
        )

        assertEquals(TrendDirection.IMPROVING, radiusTrend.direction)
        assertTrue(scoreTrend.slopePerSession > 0, "score should be climbing")
        assertEquals(TrendDirection.IMPROVING, scoreTrend.direction)
    }

    @Test
    fun `two sessions are not enough to fit a trend`() {
        val sessions = (0 until 2).map { index ->
            analysedSession("s$index", gaussianGroup(10, 15.0, 400 + index), index * DAY_MS)
        }
        assertNull(TrendAnalysis.over(sessions, TrendMetric.MEAN_RADIUS_MM))
    }

    @Test
    fun `the fitted line can be evaluated for drawing`() {
        val sessions = (0 until 6).map { index ->
            analysedSession(
                "s$index",
                gaussianGroup(10, sigma = 20.0 - index * 2.0, seed = 500 + index),
                index * DAY_MS,
            )
        }
        val trend = assertNotNull(TrendAnalysis.over(sessions, TrendMetric.MEAN_RADIUS_MM))

        assertEquals(trend.intercept, trend.fittedValueAt(0), 1e-9)
        assertTrue(trend.fittedValueAt(5) < trend.fittedValueAt(0))
    }

    // --- Ammo ranking -------------------------------------------------------------------------

    @Test
    fun `loads are ranked tightest first`() {
        val sessions = buildList {
            repeat(4) { add(loadSession("match", it, sigma = 7.0, seed = 600 + it)) }
            repeat(4) { add(loadSession("bulk", it, sigma = 22.0, seed = 700 + it)) }
        }

        val ranking = AmmoRanking.rank(sessions)

        assertEquals(2, ranking.entries.size)
        assertEquals("match", ranking.best?.ammoId)
        assertTrue(ranking.topTwoSeparable, "a 3x difference in spread should be separable")
    }

    @Test
    fun `two indistinguishable loads are reported as inseparable`() {
        val sessions = buildList {
            repeat(3) { add(loadSession("brandA", it, sigma = 14.0, seed = 800 + it)) }
            repeat(3) { add(loadSession("brandB", it, sigma = 14.5, seed = 900 + it)) }
        }

        val ranking = AmmoRanking.rank(sessions)

        assertFalse(
            ranking.topTwoSeparable,
            "these two loads shoot the same; saying otherwise would cost the shooter money",
        )
    }

    @Test
    fun `sessions shot at different distances are flagged`() {
        val sessions = listOf(
            loadSession("match", 0, sigma = 10.0, seed = 1000, distanceM = 100.0),
            loadSession("match", 1, sigma = 10.0, seed = 1001, distanceM = 200.0),
        )

        val entry = AmmoRanking.rank(sessions).entries.single()

        assertTrue(entry.mixedDistances, "millimetre figures are not comparable across distances")
        assertTrue(entry.meanRadiusMoa > 0.0, "MOA still is")
    }

    // --- Fixtures -----------------------------------------------------------------------------

    private fun gaussianGroup(count: Int, sigma: Double, seed: Int): List<PointMm> {
        val random = Random(seed)
        return List(count) {
            PointMm(
                Statistics.nextGaussian(random) * sigma,
                Statistics.nextGaussian(random) * sigma,
            )
        }
    }

    private fun loadSession(
        ammoId: String,
        index: Int,
        sigma: Double,
        seed: Int,
        distanceM: Double = 100.0,
    ) = analysedSession(
        id = "$ammoId-$index",
        positions = gaussianGroup(10, sigma, seed),
        epochMs = index * DAY_MS,
        ammoId = ammoId,
        distanceM = distanceM,
    )

    private fun analysedSession(
        id: String,
        positions: List<PointMm>,
        epochMs: Long,
        ammoId: String? = null,
        distanceM: Double = 100.0,
        target: com.nobrainsoft.rangeanalyser.core.target.TargetSpec = TargetLibrary.BLANK_A4,
    ): AnalysedSession {
        val session = Session(
            id = id,
            name = id,
            firearmId = testRifle.id,
            ammoId = ammoId,
            targetSpecId = target.id,
            distanceM = distanceM,
            mode = SessionMode.PHOTO,
            startedAtEpochMs = epochMs,
            shots = positions.mapIndexed { index, point ->
                Shot(id = "$id-$index", position = point, orderIndex = index)
            },
        )
        return assertNotNull(
            AnalysedSession.analyse(
                session = session,
                firearm = testRifle,
                ammo = ammoId?.let { Ammo(id = it, brand = it, caliberId = Calibers.R_308.id) },
                target = target,
            ),
        )
    }

    private val testRifle = Firearm(
        id = "rifle",
        name = "Test rifle",
        type = FirearmType.CENTREFIRE_RIFLE,
        caliberId = Calibers.R_308.id,
    )

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
