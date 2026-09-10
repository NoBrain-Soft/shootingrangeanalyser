package com.nobrainsoft.rangeanalyser.core.geometry

import com.nobrainsoft.rangeanalyser.core.model.ClickUnit
import com.nobrainsoft.rangeanalyser.core.model.ClickValue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UnitsTest {

    @Test
    fun `one MOA subtends about 29 millimetres at 100 metres`() {
        assertEquals(29.089, Units.mmPerMoaAt(100.0), 0.001)
    }

    @Test
    fun `one mil subtends the distance in millimetres`() {
        assertEquals(100.0, Units.mmPerMilAt(100.0), 1e-9)
        assertEquals(300.0, Units.mmPerMilAt(300.0), 1e-9)
    }

    @Test
    fun `angular conversions round-trip`() {
        val mm = 47.3
        assertEquals(mm, Units.moaToMm(Units.mmToMoa(mm, 137.0), 137.0), 1e-9)
        assertEquals(mm, Units.milToMm(Units.mmToMil(mm, 137.0), 137.0), 1e-9)
    }

    @Test
    fun `angular conversion at zero distance is rejected rather than returning infinity`() {
        assertFailsWith<IllegalArgumentException> { Units.mmPerMoaAt(0.0) }
    }

    @Test
    fun `click values agree across units at the distance they are defined for`() {
        val quarterMoa = ClickValue(0.25, ClickUnit.MOA)
        assertEquals(0.25 * 29.089, quarterMoa.mmAt(100.0), 0.01)

        // A tenth-mil click moves 10 mm at 100 m by definition.
        assertEquals(10.0, ClickValue(0.1, ClickUnit.MIL).mmAt(100.0), 1e-9)

        // "1 inch at 100 yd" is 25.4 mm at 91.44 m, and scales linearly from there.
        val inchAt100Yards = ClickValue(1.0, ClickUnit.INCH_AT_100YD)
        assertEquals(25.4, inchAt100Yards.mmAt(Units.yardsToMetres(100.0)), 1e-9)
        assertEquals(50.8, inchAt100Yards.mmAt(Units.yardsToMetres(200.0)), 1e-9)

        assertEquals(10.0, ClickValue(10.0, ClickUnit.MM_AT_100M).mmAt(100.0), 1e-9)
        assertEquals(5.0, ClickValue(10.0, ClickUnit.MM_AT_100M).mmAt(50.0), 1e-9)
    }

    @Test
    fun `MOA and the inch-at-100-yards shorthand are close but not equal`() {
        // Worth pinning: conflating the two is a common source of sight-adjustment error, and it
        // compounds over many clicks.
        val trueMoa = Units.mmPerMoaAt(Units.yardsToMetres(100.0))
        assertEquals(26.6, trueMoa, 0.1)
        assertEquals(25.4, Units.inchToMm(1.0), 1e-9)
    }

    @Test
    fun `bearing is measured clockwise from straight up`() {
        assertEquals(0.0, PointMm(0.0, 10.0).bearingDegrees(), 1e-9)
        assertEquals(90.0, PointMm(10.0, 0.0).bearingDegrees(), 1e-9)
        assertEquals(180.0, PointMm(0.0, -10.0).bearingDegrees(), 1e-9)
        assertEquals(270.0, PointMm(-10.0, 0.0).bearingDegrees(), 1e-9)
    }

    @Test
    fun `clock position reads like a spotter's call`() {
        assertEquals(12, PointMm(0.0, 10.0).clockPosition())
        assertEquals(3, PointMm(10.0, 0.0).clockPosition())
        assertEquals(6, PointMm(0.0, -10.0).clockPosition())
        assertEquals(9, PointMm(-10.0, 0.0).clockPosition())
        // Up and to the right is 1 to 2 o'clock.
        assertEquals(2, PointMm(10.0, 8.0).clockPosition())
        // A centre hit has no direction to call.
        assertNull(PointMm(0.1, 0.1).clockPosition())
    }

    @Test
    fun `centroid averages the group`() {
        val group = listOf(PointMm(0.0, 0.0), PointMm(10.0, 0.0), PointMm(0.0, 10.0), PointMm(10.0, 10.0))
        assertEquals(PointMm(5.0, 5.0), group.centroid())
    }
}
