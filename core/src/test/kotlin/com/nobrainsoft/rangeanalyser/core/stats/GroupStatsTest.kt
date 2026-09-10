package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.model.Shot
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupStatsTest {

    // A 20 mm square of shots: every number below is hand-computable from it.
    private val square = listOf(
        PointMm(-10.0, -10.0),
        PointMm(10.0, -10.0),
        PointMm(-10.0, 10.0),
        PointMm(10.0, 10.0),
    )

    @Test
    fun `basic measures match hand calculation on a square group`() {
        val stats = assertNotNull(statsFor(square))

        assertEquals(4, stats.shotCount)
        assertEquals(PointMm.ORIGIN, stats.centroid)
        // Diagonal of a 20 mm square.
        assertEquals(sqrt(800.0), stats.extremeSpreadMm, 1e-9)
        // Every corner is the same distance from the centre.
        assertEquals(sqrt(200.0), stats.meanRadiusMm, 1e-9)
        assertEquals(sqrt(200.0), stats.medianRadiusMm, 1e-9)
        // Sample standard deviation of (-10, 10, -10, 10).
        assertEquals(sqrt(400.0 / 3.0), stats.sigmaXMm, 1e-9)
        assertEquals(sqrt(400.0 / 3.0), stats.sigmaYMm, 1e-9)
    }

    @Test
    fun `outside-to-outside spread adds one bullet diameter`() {
        val stats = assertNotNull(statsFor(square, caliber = Calibers.R_308))
        assertEquals(
            stats.extremeSpreadMm + Calibers.R_308.bulletDiameterMm,
            stats.extremeSpreadOutsideMm!!,
            1e-9,
        )
    }

    @Test
    fun `extreme spread names the two shots responsible`() {
        val positions = listOf(
            PointMm(0.0, 0.0),
            PointMm(1.0, 0.0),
            PointMm(50.0, 0.0), // this one and the first are the widest pair
            PointMm(2.0, 0.0),
        )
        val stats = assertNotNull(statsFor(positions))

        assertEquals(50.0, stats.extremeSpreadMm, 1e-9)
        assertEquals(0 to 2, stats.extremeSpreadPair)
    }

    @Test
    fun `CEP of a round group is the exact Rayleigh result`() {
        val stats = assertNotNull(statsFor(square))
        val sigma = sqrt(400.0 / 3.0)

        // 1.177 sigma, which the elliptical approximation reproduces exactly when the axes match.
        assertEquals(1.177 * sigma, stats.cep50Mm, 0.001 * sigma)
        assertEquals(sigma * sqrt(5.9915), stats.r95Mm, 0.001 * sigma)
    }

    // --- Shape ------------------------------------------------------------------------------

    @Test
    fun `a vertically strung group reports a vertical major axis`() {
        val vertical = (-2..2).map { PointMm(0.0, it * 10.0) }
        val stats = assertNotNull(statsFor(vertical))

        assertEquals(0.0, stats.sigmaXMm, 1e-9)
        assertEquals(0.0, stats.axes.majorAxisBearingDeg, 1e-6, "0 degrees is straight up")
        assertTrue(stats.verticalToHorizontalRatio > 100.0)
    }

    @Test
    fun `a horizontally strung group reports a horizontal major axis`() {
        val horizontal = (-2..2).map { PointMm(it * 10.0, 0.0) }
        val stats = assertNotNull(statsFor(horizontal))

        assertEquals(90.0, stats.axes.majorAxisBearingDeg, 1e-6)
        assertEquals(0.0, stats.verticalToHorizontalRatio, 1e-9)
    }

    @Test
    fun `a diagonal group is detected as diagonal, not as round`() {
        // This is the case plain x/y spreads miss entirely: sigmaX and sigmaY are equal, so the
        // group looks circular until you rotate to its own axes.
        val diagonal = (-2..2).map { PointMm(it * 10.0, it * 10.0) }
        val stats = assertNotNull(statsFor(diagonal))

        assertEquals(stats.sigmaXMm, stats.sigmaYMm, 1e-9)
        assertEquals(45.0, stats.axes.majorAxisBearingDeg, 1e-6)
        assertTrue(
            stats.axes.elongation > 100.0,
            "the principal axes should show this is a line, not a circle",
        )
    }

    // --- Flyers -----------------------------------------------------------------------------

    @Test
    fun `a single wild shot is flagged without masking itself`() {
        val tight = listOf(
            PointMm(0.0, 0.0), PointMm(2.0, 1.0), PointMm(-1.0, 2.0), PointMm(1.0, -2.0),
            PointMm(-2.0, -1.0), PointMm(0.5, 1.5), PointMm(-1.5, 0.5),
            PointMm(80.0, 60.0), // the flyer
        )
        val stats = assertNotNull(statsFor(tight))

        assertEquals(1, stats.flyers.size, "expected exactly one flyer")
        assertEquals(7, stats.flyers.single().shotIndex)
    }

    @Test
    fun `an ordinary group has no flyers`() {
        val random = Random(7)
        val ordinary = List(12) {
            PointMm(Statistics.nextGaussian(random) * 10.0, Statistics.nextGaussian(random) * 10.0)
        }
        val stats = assertNotNull(statsFor(ordinary))

        assertTrue(
            stats.flyers.isEmpty(),
            "a normally distributed group should not produce flyers, got ${stats.flyers}",
        )
    }

    @Test
    fun `flyer detection is skipped when the sample is too small to support it`() {
        val stats = assertNotNull(statsFor(square))
        assertTrue(stats.flyers.isEmpty(), "four shots cannot support an outlier test")
    }

    @Test
    fun `the false positive rate is actually controlled`() {
        // The whole value of flyer detection rests on it staying quiet when nothing is wrong.
        // Simulate a few hundred perfectly ordinary ten-shot strings and count how often any shot
        // gets flagged; the family-wise level is 1%, so a handful is expected and a flood is a bug.
        val random = Random(4242)
        val trials = 300
        var stringsWithAFlyer = 0

        repeat(trials) {
            val group = List(10) {
                PointMm(Statistics.nextGaussian(random) * 15.0, Statistics.nextGaussian(random) * 15.0)
            }
            if (assertNotNull(statsFor(group)).flyers.isNotEmpty()) stringsWithAFlyer++
        }

        val rate = stringsWithAFlyer.toDouble() / trials
        assertTrue(
            rate <= 0.05,
            "flagged a flyer in ${(rate * 100).toInt()}% of clean groups; the nominal rate is 1%",
        )
    }

    @Test
    fun `the same outlier is called on a long string but not on a short one`() {
        // The honest consequence of correcting for an estimated covariance: with four reference
        // shots, the spread is barely pinned down, so a shot has to be wildly out before the data
        // can say so. Add more shots and the same offset becomes unambiguous. This is a real
        // property of the statistics, not a tuning knob.
        val cluster = listOf(
            PointMm(0.0, 0.0), PointMm(1.5, 0.5), PointMm(-1.0, 1.5), PointMm(0.5, -1.5),
            PointMm(-1.5, -0.5), PointMm(1.0, 1.0), PointMm(-0.5, -1.0), PointMm(1.5, -1.0),
            PointMm(-1.5, 1.0), PointMm(0.0, 1.5), PointMm(0.5, 0.5),
        )
        val outlier = PointMm(10.0, 10.0)

        val shortString = assertNotNull(statsFor(cluster.take(4) + outlier))
        val longString = assertNotNull(statsFor(cluster + outlier))

        assertTrue(
            shortString.flyers.isEmpty(),
            "five shots cannot establish that the last one was an outlier",
        )
        assertEquals(
            1,
            longString.flyers.size,
            "twelve shots pin the spread well enough to call the same offset",
        )
        assertEquals(cluster.size, longString.flyers.single().shotIndex)
    }

    @Test
    fun `the flyer bar drops as the string gets longer`() {
        val shortString = GroupStats.flyerThreshold(referenceSize = 6, level = 0.99)
        val longString = GroupStats.flyerThreshold(referenceSize = 30, level = 0.99)

        assertTrue(
            longString < shortString,
            "more reference shots means a better covariance estimate and a less forgiving test",
        )
    }

    // --- Confidence intervals ----------------------------------------------------------------

    @Test
    fun `confidence intervals bracket the observed values`() {
        val random = Random(11)
        val group = List(10) {
            PointMm(Statistics.nextGaussian(random) * 12.0, Statistics.nextGaussian(random) * 12.0)
        }
        val stats = assertNotNull(statsFor(group))

        val meanRadiusCi = assertNotNull(stats.meanRadiusCi)
        assertTrue(
            meanRadiusCi.contains(stats.meanRadiusMm),
            "mean radius ${stats.meanRadiusMm} outside its own interval $meanRadiusCi",
        )
        assertTrue(assertNotNull(stats.extremeSpreadCi).contains(stats.extremeSpreadMm))
    }

    @Test
    fun `confidence intervals are reproducible`() {
        // A band that moved between runs would be worse than none at all.
        val first = assertNotNull(statsFor(square))
        val second = assertNotNull(statsFor(square))
        assertEquals(first.meanRadiusCi, second.meanRadiusCi)
        assertEquals(first.extremeSpreadCi, second.extremeSpreadCi)
    }

    @Test
    fun `more shots produce a tighter interval`() {
        // The point the app makes to the user: five shots barely constrain anything.
        val random = Random(3)
        fun sample(n: Int) = List(n) {
            PointMm(Statistics.nextGaussian(random) * 10.0, Statistics.nextGaussian(random) * 10.0)
        }

        val small = assertNotNull(statsFor(sample(5)))
        val large = assertNotNull(statsFor(sample(40)))

        val smallRelativeWidth = small.meanRadiusCi!!.width / small.meanRadiusMm
        val largeRelativeWidth = large.meanRadiusCi!!.width / large.meanRadiusMm

        assertTrue(
            largeRelativeWidth < smallRelativeWidth,
            "40 shots ($largeRelativeWidth) should constrain mean radius better than 5 ($smallRelativeWidth)",
        )
    }

    @Test
    fun `a degenerate group does not crash the bootstrap`() {
        // All shots in a vertical line: the covariance is singular, so there is nothing to
        // simulate from and the interval is simply absent.
        val stats = assertNotNull(statsFor((-2..2).map { PointMm(0.0, it * 10.0) }))
        assertNull(stats.meanRadiusCi)
    }

    @Test
    fun `an empty group returns nothing rather than dividing by zero`() {
        assertNull(GroupStats.of(emptyList(), distanceM = 100.0))
    }

    @Test
    fun `a single shot has no spread but still reports a position`() {
        val stats = assertNotNull(statsFor(listOf(PointMm(5.0, 5.0))))

        assertEquals(1, stats.shotCount)
        assertEquals(0.0, stats.extremeSpreadMm, 1e-9)
        assertEquals(0.0, stats.meanRadiusMm, 1e-9)
        assertEquals(PointMm(5.0, 5.0), stats.centroid)
    }

    // --- Order-dependent --------------------------------------------------------------------

    @Test
    fun `a cold bore shot away from the rest is measured`() {
        val positions = listOf(
            PointMm(0.0, 40.0), // cold bore, high
            PointMm(0.0, 0.0), PointMm(2.0, 1.0), PointMm(-2.0, -1.0), PointMm(1.0, -1.0),
        )
        val order = assertNotNull(assertNotNull(statsFor(positions)).order)

        assertEquals(40.0, order.coldBoreOffsetMm!!, 1.0)
        assertTrue(order.coldBoreOffsetRatio!! > 5.0, "should stand out against a tight group")
    }

    @Test
    fun `a group walking across the string is detected`() {
        // Point of impact creeping up and right, as a barrel heating up does.
        val walking = (0..9).map { PointMm(it * 2.0, it * 3.0) }
        val order = assertNotNull(assertNotNull(statsFor(walking)).order)

        assertEquals(2.0, order.driftMmPerShot.x, 1e-9)
        assertEquals(3.0, order.driftMmPerShot.y, 1e-9)
        assertEquals(1.0, order.driftStrength, 1e-9, "a perfect walk explains all the variance")
    }

    @Test
    fun `a stationary group shows no drift`() {
        val random = Random(5)
        val steady = List(10) {
            PointMm(Statistics.nextGaussian(random) * 8.0, Statistics.nextGaussian(random) * 8.0)
        }
        val order = assertNotNull(assertNotNull(statsFor(steady)).order)

        assertTrue(order.driftStrength < 0.5, "random scatter should not look like a trend")
    }

    @Test
    fun `order statistics need enough shots to mean anything`() {
        assertNull(assertNotNull(statsFor(square.take(3))).order)
    }

    private fun statsFor(
        positions: List<PointMm>,
        distanceM: Double = 100.0,
        caliber: com.nobrainsoft.rangeanalyser.core.model.Caliber? = null,
    ): GroupStats? = GroupStats.of(
        shots = positions.mapIndexed { index, point ->
            Shot(id = "s$index", position = point, orderIndex = index)
        },
        distanceM = distanceM,
        caliber = caliber,
    )
}
