package com.nobrainsoft.rangeanalyser.core.stats

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.geometry.Units
import com.nobrainsoft.rangeanalyser.core.model.ClickUnit
import com.nobrainsoft.rangeanalyser.core.model.ClickValue
import com.nobrainsoft.rangeanalyser.core.model.Shot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SightAdjustmentTest {

    private val tenMmClick = ClickValue(10.0, ClickUnit.MM_AT_100M)

    @Test
    fun `a high group calls for the impact to come down`() {
        val correction = SightAdjustment.compute(
            centroidOffset = PointMm(0.0, 40.0),
            distanceM = 100.0,
            click = tenMmClick,
        )

        assertEquals(VerticalDirection.DOWN, correction.verticalDirection)
        assertEquals(4, correction.verticalClickCount)
        assertEquals(HorizontalDirection.NONE, correction.horizontalDirection)
    }

    @Test
    fun `a low-left group calls for up and right`() {
        val correction = SightAdjustment.compute(
            centroidOffset = PointMm(-20.0, -30.0),
            distanceM = 100.0,
            click = tenMmClick,
        )

        assertEquals(VerticalDirection.UP, correction.verticalDirection)
        assertEquals(3, correction.verticalClickCount)
        assertEquals(HorizontalDirection.RIGHT, correction.horizontalDirection)
        assertEquals(2, correction.horizontalClickCount)
    }

    @Test
    fun `applying the correction lands the group on the aim point`() {
        val offset = PointMm(-20.0, 40.0)
        val correction = SightAdjustment.compute(offset, 100.0, tenMmClick)

        // The residual is what is left after the discrete clicks are applied.
        assertEquals(0.0, correction.residualMm.x, 1e-9)
        assertEquals(0.0, correction.residualMm.y, 1e-9)
    }

    @Test
    fun `a correction that clicks cannot resolve exactly leaves a stated residual`() {
        val correction = SightAdjustment.compute(PointMm(0.0, 33.0), 100.0, tenMmClick)

        assertEquals(3, correction.verticalClickCount)
        assertEquals(VerticalDirection.DOWN, correction.verticalDirection)
        // 33 mm needed, 30 mm delivered.
        assertEquals(3.0, correction.residualMm.y, 1e-9)
    }

    @Test
    fun `a centred group needs no adjustment`() {
        val correction = SightAdjustment.compute(PointMm(1.0, 1.0), 100.0, tenMmClick)
        assertTrue(correction.isNoOp)
    }

    @Test
    fun `click value scales with distance`() {
        val quarterMoa = ClickValue(0.25, ClickUnit.MOA)

        val atHundred = SightAdjustment.compute(PointMm(0.0, 29.089), 100.0, quarterMoa)
        val atTwoHundred = SightAdjustment.compute(PointMm(0.0, 58.178), 200.0, quarterMoa)

        // One MOA of offset is four quarter-MOA clicks, whatever the distance.
        assertEquals(4, atHundred.verticalClickCount)
        assertEquals(4, atTwoHundred.verticalClickCount)
        assertEquals(
            Units.mmPerMoaAt(200.0) / 4.0,
            atTwoHundred.clickValueMm,
            1e-9,
        )
    }

    @Test
    fun `a tight group well off centre is a real zero error`() {
        // Ten shots in a 10 mm cluster, 80 mm off the aim point: unambiguous.
        val shots = (0 until 10).map {
            Shot(
                id = "s$it",
                position = PointMm(80.0 + (it % 3) - 1, 80.0 + (it % 2) - 0.5),
                orderIndex = it,
            )
        }
        val stats = assertNotNull(GroupStats.of(shots, distanceM = 100.0))

        assertTrue(SightAdjustment.offsetIsSignificant(stats))
    }

    @Test
    fun `a scattered group barely off centre is not worth chasing`() {
        // Three shots with a 60 mm spread whose centre is 5 mm off: the offset is well inside the
        // noise, and telling the user to adjust here would be actively harmful advice.
        val shots = listOf(
            PointMm(-30.0, 25.0), PointMm(35.0, -20.0), PointMm(10.0, 10.0),
        ).mapIndexed { index, point -> Shot(id = "s$index", position = point, orderIndex = index) }
        val stats = assertNotNull(GroupStats.of(shots, distanceM = 100.0))

        assertFalse(SightAdjustment.offsetIsSignificant(stats))
    }

    @Test
    fun `a single shot never justifies a sight adjustment`() {
        val stats = assertNotNull(
            GroupStats.of(
                listOf(Shot(id = "s0", position = PointMm(50.0, 50.0), orderIndex = 0)),
                distanceM = 100.0,
            ),
        )
        assertFalse(SightAdjustment.offsetIsSignificant(stats))
    }
}
