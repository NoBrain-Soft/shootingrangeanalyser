package com.nobrainsoft.rangeanalyser.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MuzzleVelocityTest {

    @Test
    fun `9mm 124 grain lands where factory ammunition actually does`() {
        // Magtech 9 mm 124 gr FMJ is published at 1109 fps, which is 338 m/s. The old default of
        // 800 m/s was rifle territory - out by a factor of well over two.
        val estimate = assertNotNull(
            MuzzleVelocity.estimate(Calibers.P_9MM, bulletWeightGrains = 124.0, barrelLengthMm = 102.0),
        )
        assertEquals(338.0, estimate.metresPerSecond, 15.0)
        assertTrue(338.0 in estimate.plausibleLowMps..estimate.plausibleHighMps)
    }

    @Test
    fun `within a cartridge, heavier bullets come out slower`() {
        val light = assertNotNull(MuzzleVelocity.estimate(Calibers.P_9MM, 115.0))
        val middle = assertNotNull(MuzzleVelocity.estimate(Calibers.P_9MM, 124.0))
        val heavy = assertNotNull(MuzzleVelocity.estimate(Calibers.P_9MM, 147.0))

        assertTrue(
            light.metresPerSecond > middle.metresPerSecond,
            "115 gr should beat 124 gr, got ${light.metresPerSecond} vs ${middle.metresPerSecond}",
        )
        assertTrue(middle.metresPerSecond > heavy.metresPerSecond)

        // Published figures: 115 gr about 1180 fps (360 m/s), 147 gr about 990 fps (302 m/s).
        assertEquals(360.0, light.metresPerSecond, 20.0)
        assertEquals(302.0, heavy.metresPerSecond, 20.0)
    }

    @Test
    fun `308 match ammunition is around 800 metres per second`() {
        val estimate = assertNotNull(
            MuzzleVelocity.estimate(Calibers.R_308, bulletWeightGrains = 168.0, barrelLengthMm = 610.0),
        )
        assertEquals(808.0, estimate.metresPerSecond, 20.0)
    }

    @Test
    fun `a longer barrel gives more velocity, but not without limit`() {
        val short = assertNotNull(MuzzleVelocity.estimate(Calibers.P_9MM, 124.0, barrelLengthMm = 76.0))
        val standard = assertNotNull(MuzzleVelocity.estimate(Calibers.P_9MM, 124.0, barrelLengthMm = 102.0))
        val long = assertNotNull(MuzzleVelocity.estimate(Calibers.P_9MM, 124.0, barrelLengthMm = 152.0))

        assertTrue(short.metresPerSecond < standard.metresPerSecond)
        assertTrue(standard.metresPerSecond < long.metresPerSecond)

        // A carbine-length 9 mm barrel does gain, but the linear rule is clamped well before it
        // starts claiming rifle velocities from pistol powder.
        val absurd = assertNotNull(MuzzleVelocity.estimate(Calibers.P_9MM, 124.0, barrelLengthMm = 900.0))
        assertTrue(
            absurd.metresPerSecond < standard.metresPerSecond * 1.25,
            "barrel scaling ran away: ${absurd.metresPerSecond}",
        )
    }

    @Test
    fun `air rifles are reported as depending on the gun, not the pellet`() {
        val estimate = assertNotNull(MuzzleVelocity.estimate(Calibers.AIR_177, bulletWeightGrains = 7.9))
        assertEquals(VelocityConfidence.GUN_DEPENDENT, estimate.confidence)
        // The band has to be wide enough to hold both a UK-legal rifle and a magnum springer.
        assertTrue(estimate.plausibleLowMps < 150.0, "band too narrow: ${estimate.plausibleLowMps}")
        assertTrue(estimate.plausibleHighMps > 250.0, "band too narrow: ${estimate.plausibleHighMps}")
    }

    @Test
    fun `a calibre spanning very different loadings gets a wider band`() {
        val magnum = assertNotNull(MuzzleVelocity.estimate(Calibers.P_38, 158.0))
        val settled = assertNotNull(MuzzleVelocity.estimate(Calibers.P_45, 230.0))

        val magnumWidth = magnum.plausibleHighMps - magnum.plausibleLowMps
        val settledWidth = settled.plausibleHighMps - settled.plausibleLowMps
        assertTrue(
            magnumWidth / magnum.metresPerSecond > settledWidth / settled.metresPerSecond,
            ".38/.357 covers both a mild and a magnum load and should say so",
        )
        assertEquals(VelocityConfidence.BROAD, magnum.confidence)
    }

    @Test
    fun `an unusual bullet weight widens the band rather than pretending`() {
        val normal = assertNotNull(MuzzleVelocity.estimate(Calibers.R_308, 168.0))
        val odd = assertNotNull(MuzzleVelocity.estimate(Calibers.R_308, 110.0))

        assertTrue(
            (odd.plausibleHighMps - odd.plausibleLowMps) / odd.metresPerSecond >
                (normal.plausibleHighMps - normal.plausibleLowMps) / normal.metresPerSecond,
        )
        assertEquals(VelocityConfidence.BROAD, odd.confidence)
    }

    @Test
    fun `the estimate always sits inside its own band`() {
        for (caliber in Calibers.all) {
            val estimate = MuzzleVelocity.estimate(caliber) ?: continue
            assertTrue(
                estimate.metresPerSecond in estimate.plausibleLowMps..estimate.plausibleHighMps,
                "${caliber.displayName} estimate outside its band",
            )
            assertTrue(estimate.plausibleLowMps > 0.0, "${caliber.displayName} band reaches zero")
        }
    }

    @Test
    fun `every built-in calibre has a reference`() {
        // A calibre in the picker with no estimate would put the shooter right back where they
        // started, staring at a field they cannot fill.
        val missing = Calibers.all.filter { MuzzleVelocity.estimate(it) == null }
        assertTrue(missing.isEmpty(), "no muzzle velocity reference for ${missing.map { it.id }}")
    }

    @Test
    fun `nothing is claimed for an unknown calibre`() {
        assertNull(MuzzleVelocity.estimate(null))
        assertNull(MuzzleVelocity.estimate(Caliber("made_up", "Nonesuch", 7.0, CaliberKind.RIFLE)))
    }

    @Test
    fun `a measured velocity always beats the estimate`() {
        val ammo = Ammo(
            id = "a",
            brand = "Chronographed",
            caliberId = Calibers.P_9MM.id,
            bulletWeightGrains = 124.0,
            muzzleVelocityMps = 351.0,
        )
        assertEquals(351.0, MuzzleVelocity.resolve(ammo, null))
    }

    @Test
    fun `an unmeasured load still yields a usable number`() {
        val ammo = Ammo(
            id = "a",
            brand = "Magtech",
            caliberId = Calibers.P_9MM.id,
            bulletWeightGrains = 124.0,
        )
        val firearm = Firearm(
            id = "f",
            name = "Carry",
            type = FirearmType.PISTOL,
            caliberId = Calibers.P_9MM.id,
            barrelLengthMm = 102.0,
        )
        val resolved = assertNotNull(MuzzleVelocity.resolve(ammo, firearm))
        assertEquals(338.0, resolved, 15.0)
    }

    @Test
    fun `the basis names the reference it came from`() {
        val estimate = assertNotNull(MuzzleVelocity.estimate(Calibers.P_9MM, 147.0, 102.0))
        assertTrue(estimate.basis.contains("9 mm"), "unhelpful basis: ${estimate.basis}")
        assertTrue(estimate.basis.contains("147"), "should say what it scaled to: ${estimate.basis}")
    }
}
