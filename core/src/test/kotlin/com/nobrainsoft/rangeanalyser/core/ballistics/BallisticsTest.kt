package com.nobrainsoft.rangeanalyser.core.ballistics

import com.nobrainsoft.rangeanalyser.core.geometry.Units
import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.model.DragModel
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the trajectory model against published figures.
 *
 * Tolerances are deliberately loose. The module claims to be a diagnostic, not a ballistic
 * calculator, and its real output - how much vertical spread a velocity SD explains - depends
 * chiefly on time of flight rather than on fine drag detail. A test that demanded three-decimal
 * agreement would be asserting a precision the model does not claim to have.
 */
class BallisticsTest {

    private val yard = Units.yardsToMetres(1.0)

    // .308 Winchester, 168 gr match, a load whose numbers are widely published.
    private val match308 = Ammo(
        id = "308-168",
        brand = "Test",
        caliberId = Calibers.R_308.id,
        bulletWeightGrains = 168.0,
        muzzleVelocityMps = Units.fpsToMps(2650.0),
        velocitySdMps = Units.fpsToMps(10.0),
        ballisticCoefficient = 0.462,
        dragModel = DragModel.G1,
    )

    private val bulk223 = Ammo(
        id = "223-55",
        brand = "Test",
        caliberId = Calibers.R_223.id,
        bulletWeightGrains = 55.0,
        muzzleVelocityMps = Units.fpsToMps(3240.0),
        velocitySdMps = Units.fpsToMps(45.0),
        ballisticCoefficient = 0.243,
        dragModel = DragModel.G1,
    )

    @Test
    fun `a 308 match load loses roughly the published velocity over 100 yards`() {
        val solution = assertNotNull(solutionFor(match308))
        val remaining = assertNotNull(solution.velocityAt(100 * yard))
        val lostFps = Units.mpsToFps(match308.muzzleVelocityMps!! - remaining)

        // Published tables put this near 180 fps.
        assertTrue(lostFps in 140.0..230.0, "lost $lostFps fps over 100 yd, expected around 180")
    }

    @Test
    fun `time of flight is about right`() {
        val solution = assertNotNull(solutionFor(match308))
        val flight = assertNotNull(solution.timeOfFlightAt(300 * yard))

        assertTrue(flight in 0.32..0.46, "time of flight $flight s, expected around 0.38")
    }

    @Test
    fun `drop is consistent with the time of flight it took to get there`() {
        // Anchored on physics rather than on a quoted table, because drop is not a free parameter:
        // once the velocity profile is right, time of flight follows, and once time of flight is
        // right the drop is determined. Free fall over the flight time, less what the launch angle
        // buys back at the zero, bounds the answer from above - drag on the descending bullet can
        // only ever reduce the fall below the vacuum figure, never increase it.
        val solution = assertNotNull(solutionFor(match308))
        val zeroDistance = 100 * yard
        val distance = 300 * yard

        val flightToZero = assertNotNull(solution.timeOfFlightAt(zeroDistance))
        val flightToTarget = assertNotNull(solution.timeOfFlightAt(distance))
        val modelDropMm = assertNotNull(solution.dropMmAt(distance))

        fun freeFallMm(seconds: Double) = 0.5 * 9.80665 * seconds * seconds * 1000.0

        val sightHeightMm = Ballistics.DEFAULT_SIGHT_HEIGHT_MM
        // The launch angle is whatever put the bullet on the sights at the zero distance.
        val risePerMetre = (sightHeightMm + freeFallMm(flightToZero)) / zeroDistance
        val vacuumDropMm = -sightHeightMm + risePerMetre * distance - freeFallMm(flightToTarget)

        assertTrue(
            modelDropMm < -vacuumDropMm,
            "drag must reduce the fall below the vacuum bound: model ${modelDropMm}mm vs ${-vacuumDropMm}mm",
        )
        assertTrue(
            modelDropMm > -vacuumDropMm * 0.75,
            "but not by much over this distance: model ${modelDropMm}mm vs ${-vacuumDropMm}mm",
        )
    }

    @Test
    fun `the trajectory crosses the line of sight at the zero distance`() {
        val solution = assertNotNull(solutionFor(match308))
        val dropAtZero = assertNotNull(solution.dropMmAt(100 * yard))

        assertTrue(kotlin.math.abs(dropAtZero) < 2.0, "should be on the sights at the zero, was $dropAtZero mm")
    }

    @Test
    fun `a rifle shoots low at the muzzle because the sight sits above the bore`() {
        val solution = assertNotNull(solutionFor(match308))
        val dropAtTenMetres = assertNotNull(solution.dropMmAt(10.0))

        assertTrue(dropAtTenMetres > 0.0, "should still be below the sight line close in")
    }

    // --- The part that actually matters -------------------------------------------------------

    @Test
    fun `velocity spread produces almost no vertical dispersion up close`() {
        val dispersion = assertNotNull(
            Ballistics.verticalDispersionFromVelocitySd(
                ammo = bulk223,
                distanceM = 100 * yard,
                zeroDistanceM = 100 * yard,
            ),
        )

        // At 100 yards even sloppy ammunition has almost no time to separate.
        assertTrue(
            dispersion.verticalSigmaMm < 6.0,
            "velocity SD should barely matter at 100 yd, got ${dispersion.verticalSigmaMm} mm",
        )
    }

    @Test
    fun `the same velocity spread matters a great deal at distance`() {
        val near = assertNotNull(
            Ballistics.verticalDispersionFromVelocitySd(bulk223, 100 * yard, 100 * yard),
        )
        val far = assertNotNull(
            Ballistics.verticalDispersionFromVelocitySd(bulk223, 500 * yard, 100 * yard),
        )

        assertTrue(
            far.verticalSigmaMm > near.verticalSigmaMm * 8,
            "vertical dispersion should grow sharply with distance: " +
                "${near.verticalSigmaMm} mm at 100 yd vs ${far.verticalSigmaMm} mm at 500 yd",
        )
    }

    @Test
    fun `consistent ammunition disperses less than inconsistent ammunition`() {
        val consistent = bulk223.copy(velocitySdMps = Units.fpsToMps(10.0))
        val sloppy = bulk223.copy(velocitySdMps = Units.fpsToMps(50.0))

        val consistentSpread = assertNotNull(
            Ballistics.verticalDispersionFromVelocitySd(consistent, 500 * yard, 100 * yard),
        ).verticalSigmaMm
        val sloppySpread = assertNotNull(
            Ballistics.verticalDispersionFromVelocitySd(sloppy, 500 * yard, 100 * yard),
        ).verticalSigmaMm

        // Five times the velocity spread, roughly five times the vertical dispersion.
        assertTrue(sloppySpread > consistentSpread * 3.5)
        assertTrue(sloppySpread < consistentSpread * 7.0)
    }

    @Test
    fun `the observed fraction tells the shooter whether the ammunition is the limit`() {
        val dispersion = assertNotNull(
            Ballistics.verticalDispersionFromVelocitySd(bulk223, 500 * yard, 100 * yard),
        )

        // A vertical spread twice what the ammunition explains leaves room to improve.
        val roomToImprove = assertNotNull(dispersion.fractionOf(dispersion.verticalSigmaMm * 2))
        assertTrue(roomToImprove < 0.6)

        // A vertical spread matching the ammunition means the shooter is already at its limit.
        val atTheLimit = assertNotNull(dispersion.fractionOf(dispersion.verticalSigmaMm))
        assertTrue(atTheLimit > 0.9)
    }

    @Test
    fun `ammunition without the data needed simply declines to answer`() {
        val unknown = Ammo(id = "x", brand = "Unknown", caliberId = Calibers.R_308.id)
        assertNull(Ballistics.verticalDispersionFromVelocitySd(unknown, 300.0, 100.0))

        val noSd = match308.copy(velocitySdMps = null)
        assertNull(Ballistics.verticalDispersionFromVelocitySd(noSd, 300.0, 100.0))
    }

    @Test
    fun `an air pellet cannot be zeroed at 300 metres and says so`() {
        val pellet = Ammo(
            id = "pellet",
            brand = "Test",
            caliberId = Calibers.AIR_177.id,
            bulletWeightGrains = 8.4,
            muzzleVelocityMps = 200.0,
            velocitySdMps = 3.0,
            ballisticCoefficient = 0.021,
        )

        assertNull(
            Ballistics.solve(
                BallisticInput(
                    muzzleVelocityMps = 200.0,
                    ballisticCoefficient = 0.021,
                    zeroDistanceM = 300.0,
                    maxRangeM = 300.0,
                ),
            ),
            "a pellet has no business reaching 300 m and the model should not pretend it does",
        )
        assertNull(Ballistics.verticalDispersionFromVelocitySd(pellet, 300.0, 300.0))
    }

    // --- Drag tables ---------------------------------------------------------------------------

    @Test
    fun `the G1 drag curve peaks in the transonic region`() {
        val subsonic = DragTables.G1.dragCoefficientAt(0.5)
        val transonic = DragTables.G1.dragCoefficientAt(1.4)
        val supersonic = DragTables.G1.dragCoefficientAt(3.0)

        assertTrue(transonic > subsonic, "drag rises sharply through the sound barrier")
        assertTrue(transonic > supersonic, "and falls away again well past it")
    }

    @Test
    fun `G7 shows much less drag than G1 above the speed of sound`() {
        // The whole reason boat-tail match bullets are quoted against G7.
        assertTrue(DragTables.G7.dragCoefficientAt(2.0) < DragTables.G1.dragCoefficientAt(2.0))
    }

    @Test
    fun `drag lookups outside the table clamp instead of extrapolating`() {
        assertTrue(DragTables.G1.dragCoefficientAt(-1.0) == DragTables.G1.dragCoefficientAt(0.0))
        assertTrue(DragTables.G1.dragCoefficientAt(99.0) == DragTables.G1.dragCoefficientAt(5.0))
    }

    private fun solutionFor(ammo: Ammo) = Ballistics.solve(
        BallisticInput(
            muzzleVelocityMps = ammo.muzzleVelocityMps!!,
            ballisticCoefficient = ammo.ballisticCoefficient!!,
            dragModel = ammo.dragModel,
            zeroDistanceM = 100 * yard,
            maxRangeM = 500 * yard,
        ),
    )
}
