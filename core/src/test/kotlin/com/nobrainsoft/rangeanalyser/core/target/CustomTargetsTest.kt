package com.nobrainsoft.rangeanalyser.core.target

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomTargetsTest {

    private fun draft(
        name: String = "Club A3",
        rings: List<Double> = listOf(50.0, 100.0, 150.0, 200.0, 250.0),
        sheetWidthMm: Double? = CustomTargets.Sheet.A3.widthMm,
        sheetHeightMm: Double? = CustomTargets.Sheet.A3.heightMm,
        innerRingDiameterMm: Double? = null,
        blackDiameterMm: Double? = null,
    ) = CustomTargets.Draft(
        name = name,
        ringDiametersMm = rings,
        sheetWidthMm = sheetWidthMm,
        sheetHeightMm = sheetHeightMm,
        innerRingDiameterMm = innerRingDiameterMm,
        blackDiameterMm = blackDiameterMm,
    )

    @Test
    fun `a measured face becomes a usable target`() {
        // The reported case: a range whose own target is printed on A3 and matches nothing in the
        // built-in library.
        val spec = CustomTargets.build("custom-1", draft()).getOrThrow()

        assertEquals("Club A3", spec.name)
        assertFalse(spec.isBuiltIn)
        assertEquals(297.0, spec.sheetWidthMm)

        val rings = assertNotNull(spec.rings)
        assertEquals(5, rings.rings.size)
        // The smallest ring is the highest score, counting down outwards.
        assertEquals(10, rings.highestValue)
        assertEquals(50.0, rings.byValueDescending.first().diameterMm)
        assertEquals(250.0, rings.outermostRadiusMm * 2.0)
        assertEquals(6, rings.lowestValue)
    }

    @Test
    fun `ring order does not matter to the shooter`() {
        val outwards = CustomTargets.build("a", draft(rings = listOf(50.0, 100.0, 150.0))).getOrThrow()
        val inwards = CustomTargets.build("b", draft(rings = listOf(150.0, 100.0, 50.0))).getOrThrow()

        assertEquals(
            outwards.rings?.byValueDescending?.map { it.diameterMm },
            inwards.rings?.byValueDescending?.map { it.diameterMm },
        )
    }

    @Test
    fun `evenly spaced rings get decimal scoring, uneven ones do not`() {
        val even = CustomTargets.build("a", draft(rings = listOf(50.0, 100.0, 150.0))).getOrThrow()
        val uneven = CustomTargets.build("b", draft(rings = listOf(50.0, 90.0, 200.0))).getOrThrow()

        assertTrue(even.rings!!.supportsDecimal, "evenly spaced rings can be scored in tenths")
        assertFalse(
            uneven.rings!!.supportsDecimal,
            "tenths of an uneven ring would be invented precision",
        )
    }

    // --- Refusing what would score wrongly --------------------------------------------------------

    @Test
    fun `a nameless target is refused`() {
        assertIs<CustomTargets.Problem.NoName>(CustomTargets.problemWith(draft(name = " ")))
    }

    @Test
    fun `a target with no rings is refused`() {
        assertIs<CustomTargets.Problem.NoRings>(CustomTargets.problemWith(draft(rings = emptyList())))
    }

    @Test
    fun `two rings measured to the same size are refused`() {
        // Tapping the same ring twice is the easy mistake, and it would otherwise build a table
        // that scores a whole ring's worth of paper as something it is not.
        val problem = CustomTargets.problemWith(draft(rings = listOf(50.0, 100.0, 100.02)))
        assertIs<CustomTargets.Problem.Duplicate>(problem)
        assertTrue(problem.message.contains("100"), problem.message)
    }

    @Test
    fun `a ring larger than the paper means the scale was wrong`() {
        val problem = CustomTargets.problemWith(draft(rings = listOf(100.0, 900.0)))
        assertIs<CustomTargets.Problem.LargerThanSheet>(problem)
        assertTrue(problem.message.contains("scale"), problem.message)
    }

    @Test
    fun `a ring printed to the edge of the paper is allowed`() {
        // Plenty of faces run right out to the sheet edge; a hard cut at exactly the width would
        // reject perfectly good measurements over a millimetre of slack.
        assertNull(CustomTargets.problemWith(draft(rings = listOf(100.0, 299.0))))
    }

    @Test
    fun `an inner ring must be smaller than the highest ring`() {
        val problem = CustomTargets.problemWith(
            draft(rings = listOf(50.0, 100.0), innerRingDiameterMm = 60.0),
        )
        assertIs<CustomTargets.Problem.InnerRingTooLarge>(problem)
    }

    @Test
    fun `a negative measurement is refused`() {
        assertIs<CustomTargets.Problem.NotPositive>(
            CustomTargets.problemWith(draft(rings = listOf(50.0, -10.0))),
        )
    }

    @Test
    fun `a target with no sheet size is still allowed`() {
        // Not everyone knows what their target is printed on, and the ring table is what matters.
        assertNull(
            CustomTargets.problemWith(draft(sheetWidthMm = null, sheetHeightMm = null)),
        )
    }

    @Test
    fun `building refuses exactly what problemWith refuses`() {
        val bad = draft(rings = listOf(50.0, 50.0))
        assertNotNull(CustomTargets.problemWith(bad))
        assertTrue(CustomTargets.build("x", bad).isFailure)
    }

    // --- Measuring helpers ------------------------------------------------------------------------

    @Test
    fun `evenly spaced rings divide the outer diameter`() {
        val rings = CustomTargets.evenlySpaced(outerDiameterMm = 200.0, ringCount = 4)
        assertEquals(listOf(50.0, 100.0, 150.0, 200.0), rings)
    }

    @Test
    fun `nonsense ring counts produce nothing rather than a broken table`() {
        assertTrue(CustomTargets.evenlySpaced(200.0, 0).isEmpty())
        assertTrue(CustomTargets.evenlySpaced(-5.0, 4).isEmpty())
    }

    @Test
    fun `scale comes from two taps a known distance apart`() {
        // 297 mm of A3 width spanning 594 pixels is exactly half a millimetre per pixel.
        val scale = assertNotNull(CustomTargets.scaleFrom(10.0, 100.0, 604.0, 100.0, 297.0))
        assertEquals(0.5, scale, 1e-9)
    }

    @Test
    fun `two taps in the same place are a slip, not a measurement`() {
        assertNull(CustomTargets.scaleFrom(10.0, 10.0, 12.0, 11.0, 297.0))
    }

    @Test
    fun `a ring diameter is twice the tapped radius`() {
        val diameter = CustomTargets.diameterFromTap(
            centreX = 100.0,
            centreY = 100.0,
            edgeX = 100.0,
            edgeY = 200.0,
            millimetresPerPixel = 0.5,
        )
        assertEquals(100.0, diameter, 1e-9)
    }

    @Test
    fun `a diagonal tap measures the same ring as a straight one`() {
        val straight = CustomTargets.diameterFromTap(0.0, 0.0, 100.0, 0.0, 1.0)
        val diagonal = CustomTargets.diameterFromTap(0.0, 0.0, 70.71068, 70.71068, 1.0)
        assertEquals(straight, diagonal, 0.01)
    }
}
