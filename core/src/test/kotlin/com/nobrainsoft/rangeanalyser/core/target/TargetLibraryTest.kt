package com.nobrainsoft.rangeanalyser.core.target

import com.nobrainsoft.rangeanalyser.core.geometry.Units
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the ring tables. These numbers are transcribed from rulebooks and a single wrong digit
 * silently corrupts every score the app ever produces, so they get checked structurally (spacing,
 * ordering, black area) as well as by spot value.
 */
class TargetLibraryTest {

    @Test
    fun `every ring target orders rings smallest-highest to largest-lowest`() {
        for (spec in TargetLibrary.all) {
            val rings = spec.rings ?: continue
            rings.byValueDescending.zipWithNext { inner, outer ->
                assertTrue(
                    inner.diameterMm < outer.diameterMm,
                    "${spec.name}: ring ${inner.value} is not smaller than ring ${outer.value}",
                )
                assertTrue(
                    inner.value > outer.value,
                    "${spec.name}: ring values are not descending",
                )
            }
        }
    }

    @Test
    fun `ISSF faces are evenly spaced, which is what makes decimal scoring valid`() {
        val issf = TargetLibrary.all.filter { it.id.startsWith("issf_") }
        assertEquals(6, issf.size, "expected six ISSF faces")

        for (spec in issf) {
            val rings = assertNotNull(spec.rings, "${spec.name} should have rings")
            assertTrue(rings.hasUniformStep, "${spec.name}: ring spacing is not uniform")
            assertTrue(rings.supportsDecimal, "${spec.name}: ISSF faces support decimal scoring")
        }
    }

    @Test
    fun `B-8 is unevenly spaced and does not claim decimal support`() {
        val rings = assertNotNull(TargetLibrary.NRA_B8.rings)
        assertFalse(
            rings.hasUniformStep,
            "the B-8's rings really are unevenly spaced - if this passes, the table is wrong",
        )
        assertFalse(rings.supportsDecimal)
        assertEquals("X", rings.innerRingLabel)
    }

    @Test
    fun `10m air rifle table matches the ISSF face`() {
        val rings = assertNotNull(TargetLibrary.ISSF_AIR_RIFLE_10M.rings)

        assertEquals(0.5, rings.radiusFor(10)!! * 2, 1e-9)
        assertEquals(45.5, rings.radiusFor(1)!! * 2, 1e-9)
        // 5 mm between ring diameters, i.e. 2.5 mm of radius.
        assertEquals(2.5, rings.radiusStepAt(10)!!, 1e-9)
        // The black covers rings 4 to 10.
        assertEquals(TargetLibrary.ISSF_AIR_RIFLE_10M.blackDiameterMm!!, rings.radiusFor(4)!! * 2, 1e-9)
    }

    @Test
    fun `10m air pistol table matches the ISSF face`() {
        val rings = assertNotNull(TargetLibrary.ISSF_AIR_PISTOL_10M.rings)

        assertEquals(11.5, rings.radiusFor(10)!! * 2, 1e-9)
        assertEquals(155.5, rings.radiusFor(1)!! * 2, 1e-9)
        assertEquals(8.0, rings.radiusStepAt(10)!!, 1e-9)
        // The black covers rings 7 to 10.
        assertEquals(TargetLibrary.ISSF_AIR_PISTOL_10M.blackDiameterMm!!, rings.radiusFor(7)!! * 2, 1e-9)
    }

    @Test
    fun `50m rifle table matches the ISSF face`() {
        val rings = assertNotNull(TargetLibrary.ISSF_RIFLE_50M.rings)

        assertEquals(10.4, rings.radiusFor(10)!! * 2, 1e-9)
        assertEquals(8.0, rings.radiusStepAt(9)!!, 1e-9)
        assertEquals(154.4, rings.radiusFor(1)!! * 2, 1e-9)
    }

    @Test
    fun `B-8 ring diameters convert from the published inch values`() {
        val rings = assertNotNull(TargetLibrary.NRA_B8.rings)

        assertEquals(Units.inchToMm(3.36), rings.radiusFor(10)!! * 2, 1e-9)
        assertEquals(Units.inchToMm(5.54), rings.radiusFor(9)!! * 2, 1e-9)
        assertEquals(Units.inchToMm(19.68), rings.radiusFor(5)!! * 2, 1e-9)
        // The black is the 9 ring.
        assertEquals(TargetLibrary.NRA_B8.blackDiameterMm!!, rings.radiusFor(9)!! * 2, 1e-9)
    }

    @Test
    fun `black aiming area lies within the scoring rings`() {
        // The black is used as a calibration reference, so a transcription slip here would put the
        // whole scale out. It must at least fall inside the printed ring span.
        for (spec in TargetLibrary.all) {
            val black = spec.blackDiameterMm ?: continue
            val rings = spec.rings ?: continue
            assertTrue(
                black >= rings.byValueDescending.first().diameterMm &&
                    black <= rings.byValueDescending.last().diameterMm,
                "${spec.name}: black area $black mm falls outside the ring span",
            )
        }
    }

    @Test
    fun `black aiming area coincides with a ring, except on the 50m rifle face`() {
        // On most faces the rulebook defines the black as covering whole rings, which gives the
        // calibrator an exact reference. The ISSF 50 m rifle face is the odd one out: its aiming
        // mark is 112.4 mm, between the 4 ring (106.4) and the 3 ring (122.4). That is the spec,
        // not a typo - but it is pinned here so nobody "fixes" it later, and so the calibrator is
        // never written assuming black always equals a ring.
        val exceptions = setOf(TargetLibrary.ISSF_RIFLE_50M.id)

        for (spec in TargetLibrary.all) {
            val black = spec.blackDiameterMm ?: continue
            val rings = spec.rings ?: continue
            val matchesARing = rings.rings.any { abs(it.diameterMm - black) < 0.01 }

            if (spec.id in exceptions) {
                assertFalse(
                    matchesARing,
                    "${spec.name} is listed as an exception but its black now matches a ring - " +
                        "remove it from the exception set",
                )
            } else {
                assertTrue(
                    matchesARing,
                    "${spec.name}: black area $black mm matches no ring - one of the two is wrong",
                )
            }
        }
    }

    @Test
    fun `ids are unique and lookup works`() {
        val ids = TargetLibrary.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate target ids")
        for (spec in TargetLibrary.all) {
            assertEquals(spec, TargetLibrary.find(spec.id))
        }
    }

    @Test
    fun `practical targets are flagged as approximate rather than implying false precision`() {
        for (spec in TargetLibrary.of(Discipline.PRACTICAL)) {
            assertFalse(
                spec.dimensionsVerified,
                "${spec.name}: silhouette outline is approximated, so it must be flagged",
            )
            assertTrue(spec.source.isNotBlank(), "${spec.name}: needs a provenance note")
        }
    }
}
