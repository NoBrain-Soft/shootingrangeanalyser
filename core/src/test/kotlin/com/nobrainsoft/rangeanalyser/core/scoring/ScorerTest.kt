package com.nobrainsoft.rangeanalyser.core.scoring

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.model.ShotSource
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import org.junit.Test
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScorerTest {

    private val airRifle = TargetLibrary.ISSF_AIR_RIFLE_10M
    private val pellet = Calibers.AIR_177 // 4.5 mm, so 2.25 mm of bullet radius

    // --- Decimal scoring -------------------------------------------------------------------

    @Test
    fun `a centred pellet scores the maximum decimal`() {
        val scorer = Scorer(airRifle, pellet)
        val score = scorer.score(PointMm.ORIGIN)

        assertEquals(10.9, score.decimalScore!!, 1e-9)
        assertEquals(10, score.ringValue)
    }

    @Test
    fun `decimal score hits exactly N-point-zero on ring N`() {
        val scorer = Scorer(airRifle, pellet)
        val rings = assertNotNull(airRifle.rings)

        for (ring in rings.rings) {
            // Place the hole so its inner edge sits exactly on the ring line.
            val centreDistance = ring.radiusMm + pellet.bulletRadiusMm
            val score = scorer.score(PointMm(centreDistance, 0.0))

            assertEquals(
                ring.value.toDouble(),
                score.decimalScore!!,
                1e-9,
                "a shot gauging exactly onto ring ${ring.value} should score ${ring.value}.0",
            )
        }
    }

    @Test
    fun `the published air rifle decimal anchors hold`() {
        val scorer = Scorer(airRifle, pellet)

        // 10-ring radius is 0.25 mm and the ring step is 2.5 mm, so with a 4.5 mm pellet:
        assertEquals(10.0, scorer.score(PointMm(2.5, 0.0)).decimalScore!!, 1e-9)
        assertEquals(9.0, scorer.score(PointMm(5.0, 0.0)).decimalScore!!, 1e-9)
        assertEquals(9.5, scorer.score(PointMm(3.75, 0.0)).decimalScore!!, 1e-9)
    }

    @Test
    fun `decimal and integer scores never disagree`() {
        // The two are computed by independent paths - a ring lookup and a continuous function -
        // so this pins them together across the whole face.
        val scorer = Scorer(airRifle, pellet)
        val outer = airRifle.rings!!.outermostRadiusMm

        var radius = 0.0
        while (radius < outer + 5.0) {
            val score = scorer.score(PointMm(radius, 0.0))
            val decimal = score.decimalScore!!

            if (score.isMiss) {
                assertEquals(0.0, decimal, 1e-9, "a miss should score 0.0 (r=$radius)")
            } else {
                val points = score.points
                assertTrue(
                    decimal >= points - 1e-9 && decimal < points + 1.0 + 1e-9,
                    "decimal $decimal is not inside ring $points (r=$radius)",
                )
            }
            radius += 0.017 // deliberately not a divisor of the ring spacing
        }
    }

    @Test
    fun `unevenly spaced faces report no decimal score`() {
        val scorer = Scorer(TargetLibrary.NRA_B8, Calibers.P_45)
        assertNull(scorer.score(PointMm.ORIGIN).decimalScore)
    }

    // --- Edge gauging ----------------------------------------------------------------------

    @Test
    fun `a shot whose edge touches the line takes the higher ring`() {
        val scorer = Scorer(airRifle, pellet)
        val tenRingRadius = airRifle.rings!!.radiusFor(10)!!

        // Centre exactly one bullet radius outside the 10 ring: the edge is on the line, so it is
        // a 10.
        val touching = scorer.score(PointMm(tenRingRadius + pellet.bulletRadiusMm, 0.0))
        assertEquals(10, touching.ringValue)

        // A hair further out and the edge clears the line.
        val clear = scorer.score(PointMm(tenRingRadius + pellet.bulletRadiusMm + 0.01, 0.0))
        assertEquals(9, clear.ringValue)
    }

    @Test
    fun `a bigger bullet earns more from the same hole position`() {
        val position = PointMm(46.0, 0.0) // just outside the B-8 10 ring for a small calibre
        val b8 = TargetLibrary.NRA_B8

        val rimfire = Scorer(b8, Calibers.RF_22_LR).score(position)
        val fortyFive = Scorer(b8, Calibers.P_45).score(position)

        assertTrue(
            fortyFive.ringValue!! >= rimfire.ringValue!!,
            "a .45 covers more of the target than a .22 and can only score the same or better",
        )
        assertTrue(fortyFive.gaugedRadiusMm < rimfire.gaugedRadiusMm)
    }

    @Test
    fun `every ring boundary scores correctly on both sides`() {
        val scorer = Scorer(airRifle, pellet)
        val rings = assertNotNull(airRifle.rings)
        val nudge = 0.01

        for ((index, ring) in rings.byValueDescending.withIndex()) {
            val onTheLine = ring.radiusMm + pellet.bulletRadiusMm

            assertEquals(
                ring.value,
                scorer.score(PointMm(onTheLine - nudge, 0.0)).ringValue,
                "just inside ring ${ring.value}",
            )

            val expectedOutside = rings.byValueDescending.getOrNull(index + 1)?.value
            assertEquals(
                expectedOutside,
                scorer.score(PointMm(onTheLine + nudge, 0.0)).ringValue,
                "just outside ring ${ring.value}",
            )
        }
    }

    @Test
    fun `without a calibre no gauging is applied and the caller can tell`() {
        val scorer = Scorer(airRifle, caliber = null)
        val score = scorer.score(PointMm(3.0, 0.0))

        assertFalse(scorer.edgeGauged)
        assertFalse(score.edgeGauged)
        assertEquals(3.0, score.gaugedRadiusMm, 1e-9, "gauged radius should equal the raw radius")
    }

    // --- Misses and inner rings -------------------------------------------------------------

    @Test
    fun `a shot beyond the outermost ring is a miss`() {
        val scorer = Scorer(airRifle, pellet)
        val score = scorer.score(PointMm(100.0, 0.0))

        assertTrue(score.isMiss)
        assertNull(score.ringValue)
        assertEquals(0, score.points)
    }

    @Test
    fun `the B-8 X ring is gauged like any other ring`() {
        val b8 = TargetLibrary.NRA_B8
        val scorer = Scorer(b8, Calibers.P_45)
        val xRadius = b8.rings!!.innerRingRadiusMm!!
        val bulletRadius = Calibers.P_45.bulletRadiusMm

        assertTrue(scorer.score(PointMm(xRadius + bulletRadius - 0.1, 0.0)).isInnerRing)
        assertFalse(scorer.score(PointMm(xRadius + bulletRadius + 0.1, 0.0)).isInnerRing)
    }

    // --- Zone targets ----------------------------------------------------------------------

    @Test
    fun `zone targets score by the zone the hole reaches`() {
        val scorer = Scorer(TargetLibrary.IPSC_CLASSIC, Calibers.P_9MM)

        assertEquals("A", scorer.score(PointMm.ORIGIN).zoneLabel)
        // Outside the 150 x 280 mm A zone but inside the body.
        assertEquals("C", scorer.score(PointMm(150.0, 0.0)).zoneLabel)
        assertTrue(scorer.score(PointMm(1000.0, 0.0)).isMiss)
    }

    @Test
    fun `a hole breaking the zone line takes the better zone`() {
        val scorer = Scorer(TargetLibrary.IPSC_CLASSIC, Calibers.P_45)
        val aZoneHalfWidth = 75.0
        val bulletRadius = Calibers.P_45.bulletRadiusMm

        // Centre just outside the A zone, but close enough that the hole breaks the line.
        val breaking = scorer.score(PointMm(aZoneHalfWidth + bulletRadius - 0.5, 0.0))
        assertEquals("A", breaking.zoneLabel)

        val clear = scorer.score(PointMm(aZoneHalfWidth + bulletRadius + 0.5, 0.0))
        assertEquals("C", clear.zoneLabel)
    }

    // --- Unscored faces ---------------------------------------------------------------------

    @Test
    fun `a blank face reports geometry but no score`() {
        val scorer = Scorer(TargetLibrary.BLANK_A4, Calibers.R_308)
        val score = scorer.score(PointMm(10.0, 10.0))

        assertNull(score.ringValue)
        assertNull(score.zoneLabel)
        assertNull(score.decimalScore)
        assertFalse(score.isMiss)
        assertEquals(PointMm(10.0, 10.0).radius, score.radiusMm, 1e-9)
    }

    // --- Summaries -------------------------------------------------------------------------

    @Test
    fun `a string summary totals rings, decimals and misses`() {
        val scorer = Scorer(airRifle, pellet)
        val shots = listOf(
            shotAt(0, PointMm.ORIGIN), // 10.9
            shotAt(1, PointMm(2.5, 0.0)), // 10.0
            shotAt(2, PointMm(5.0, 0.0)), // 9.0
            shotAt(3, PointMm(500.0, 0.0)), // miss
        )

        val summary = scorer.summarise(shots)

        assertEquals(4, summary.shotCount)
        assertEquals(1, summary.missCount)
        assertEquals(29, summary.totalRingScore) // 10 + 10 + 9 + 0
        assertEquals(29.9, summary.totalDecimalScore!!, 1e-9)
        assertEquals(40, summary.maxPossibleRingScore)
        assertEquals(2, summary.ringCounts[10])
    }

    @Test
    fun `excluded shots are left out of the summary`() {
        val scorer = Scorer(airRifle, pellet)
        val shots = listOf(
            shotAt(0, PointMm.ORIGIN),
            shotAt(1, PointMm.ORIGIN).copy(excluded = true),
        )

        assertEquals(1, scorer.summarise(shots).shotCount)
    }

    @Test
    fun `decimalAsInteger tracks the ring value`() {
        val scorer = Scorer(airRifle, pellet)
        val score = scorer.score(PointMm(3.75, 0.0))

        assertEquals(floor(score.decimalScore!!).toInt(), score.decimalAsInteger)
        assertEquals(score.ringValue, score.decimalAsInteger)
    }

    private fun shotAt(index: Int, position: PointMm) = Shot(
        id = "shot-$index",
        position = position,
        orderIndex = index,
        source = ShotSource.MANUAL,
    )
}
