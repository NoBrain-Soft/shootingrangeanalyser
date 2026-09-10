package com.nobrainsoft.rangeanalyser.vision.detect

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.vision.SyntheticTarget
import com.nobrainsoft.rangeanalyser.vision.TestOpenCv
import com.nobrainsoft.rangeanalyser.vision.calibration.RectifiedImage
import com.nobrainsoft.rangeanalyser.vision.calibration.Rectifier
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HoleDetectorTest {

    @Before
    fun setUp() = TestOpenCv.ensureLoaded()

    private val airPistol = TargetLibrary.ISSF_AIR_PISTOL_10M
    private val pellet = Calibers.AIR_177

    // --- The basic claim -------------------------------------------------------------------------

    @Test
    fun `holes are found where they were put`() {
        val truth = listOf(
            PointMm(0.0, 30.0),
            PointMm(25.0, -15.0),
            PointMm(-30.0, -25.0),
            PointMm(45.0, 40.0),
        )
        val found = detect(truth)

        assertEquals(truth.size, found.holes.size, "expected one hole per shot")
        assertMatched(truth, found.holes, toleranceMm = 1.5)
    }

    @Test
    fun `a clean target yields no holes at all`() {
        // False positives are worse than misses here: a phantom hole silently corrupts the group
        // statistics, whereas a missed one is visible on the review screen.
        val found = detect(emptyList())
        assertTrue(found.holes.isEmpty(), "found ${found.holes.size} holes in an unshot target")
    }

    @Test
    fun `printed ring lines are not mistaken for holes`() {
        // The air pistol face has ten concentric rings. A detector that opens before it closes,
        // or does not filter by shape, reports them as a ring of hits.
        val found = detect(listOf(PointMm(0.0, 0.0)))
        assertEquals(1, found.holes.size, "the rings should not register as shots")
    }

    // --- The hard case ---------------------------------------------------------------------------

    @Test
    fun `a hole inside the black aiming mark is found`() {
        // The whole reason for subtracting a synthesised target. Inside the black there is no
        // contrast between crater and ink; only the torn paper ring gives the hole away.
        val insideBlack = listOf(PointMm(5.0, 5.0), PointMm(-8.0, 10.0))
        val found = detect(insideBlack)

        assertEquals(insideBlack.size, found.holes.size)
        assertMatched(insideBlack, found.holes, toleranceMm = 1.5)
    }

    @Test
    fun `holes on both sides of the black edge are found together`() {
        // The awkward mixture: some shots on white paper, some on ink, in one photograph. Any
        // approach tuned for one polarity fails half of these.
        val truth = listOf(
            PointMm(0.0, 0.0), // dead centre, on ink
            PointMm(20.0, 10.0), // still on ink
            PointMm(40.0, 0.0), // out on the paper
            PointMm(-45.0, -20.0), // paper
        )
        val found = detect(truth)

        assertEquals(truth.size, found.holes.size)
        assertMatched(truth, found.holes, toleranceMm = 1.5)
    }

    // --- Robustness ------------------------------------------------------------------------------

    @Test
    fun `blur, noise and uneven lighting do not lose the holes`() {
        val truth = listOf(PointMm(0.0, 25.0), PointMm(30.0, -20.0), PointMm(-35.0, 5.0))
        val found = detect(
            truth,
            config = baseConfig(truth).copy(blurSigma = 1.5, noiseSigma = 6.0, vignette = 0.4),
        )

        assertEquals(truth.size, found.holes.size)
        assertMatched(truth, found.holes, toleranceMm = 2.0)
    }

    @Test
    fun `an off-axis photograph is handled once rectified`() {
        val truth = listOf(PointMm(0.0, 20.0), PointMm(35.0, -25.0), PointMm(-40.0, 0.0))
        val found = detect(truth, config = baseConfig(truth).copy(verticalTilt = 0.18))

        assertEquals(truth.size, found.holes.size)
        assertMatched(truth, found.holes, toleranceMm = 2.5)
    }

    @Test
    fun `touching holes are separated rather than counted once`() {
        // Two pellets 3.5 mm apart overlap heavily - a good ten-metre group does this constantly.
        // Reporting one hole would quietly flatter the shooter's score and their group size.
        val truth = listOf(PointMm(0.0, 30.0), PointMm(3.5, 30.0))
        val found = detect(truth)

        assertEquals(2, found.holes.size, "an overlapping pair should still be two shots")
        assertMatched(truth, found.holes, toleranceMm = 2.0)
    }

    @Test
    fun `a larger calibre is measured as larger`() {
        // Each calibre on a face it is actually fired at. Putting a .45 on a ten-metre air pistol
        // target makes a hole the size of its ten ring, which is a fair test of nothing.
        val pelletHoles = detect(
            listOf(PointMm(0.0, 40.0)),
            caliber = pellet,
            holeDiameterMm = 4.5,
        )
        val fortyFive = detect(
            listOf(PointMm(0.0, 120.0)),
            spec = TargetLibrary.ISSF_PISTOL_25M,
            caliber = Calibers.P_45,
            holeDiameterMm = 11.48,
        )

        assertTrue(
            fortyFive.holes.single().diameterMm > pelletHoles.holes.single().diameterMm * 2,
            "a .45 hole should measure far bigger than a pellet hole",
        )
    }

    @Test
    fun `measured hole size is about right`() {
        val found = detect(listOf(PointMm(0.0, 40.0)))
        val hole = found.holes.single()

        // The torn ring makes the visible hole slightly larger than the pellet.
        assertTrue(
            hole.diameterMm in 4.0..7.0,
            "a 4.5 mm pellet hole measured ${hole.diameterMm} mm",
        )
    }

    @Test
    fun `shots off the edge of the face are ignored`() {
        val truth = listOf(PointMm(0.0, 30.0), PointMm(300.0, 300.0))
        val found = detect(truth)

        assertEquals(1, found.holes.size, "the shot off the paper should not be counted")
    }

    // --- Honesty about its own limits -------------------------------------------------------------

    @Test
    fun `detection reports whether it knew the calibre`() {
        val truth = listOf(PointMm(0.0, 25.0), PointMm(20.0, -20.0))

        assertTrue(detect(truth, caliber = pellet).calibreKnown)
        assertTrue(!detect(truth, caliber = null).calibreKnown)
    }

    @Test
    fun `holes are still found without being told the calibre`() {
        val truth = listOf(PointMm(0.0, 25.0), PointMm(20.0, -20.0), PointMm(-25.0, 10.0))
        val found = detect(truth, caliber = null)

        assertEquals(truth.size, found.holes.size)
        assertMatched(truth, found.holes, toleranceMm = 2.0)
    }

    @Test
    fun `a target too coarse to resolve holes returns nothing rather than noise`() {
        // At half a pixel per millimetre a pellet hole is about two pixels across; there is no
        // information there and the honest answer is none.
        val truth = listOf(PointMm(0.0, 25.0))
        val rendered = SyntheticTarget.render(
            airPistol,
            baseConfig(truth).copy(pixelsPerMm = 0.5),
        )
        val rectified = assertNotNull(
            Rectifier.rectify(rendered.image, rendered.imageToTargetMm, airPistol, pixelsPerMm = 0.5),
        )

        val found = HoleDetector.detect(rectified, airPistol, pellet)
        assertTrue(found.holes.isEmpty())

        rectified.release()
        rendered.release()
    }

    @Test
    fun `confident detections on a clean image are marked confident`() {
        val found = detect(listOf(PointMm(0.0, 30.0), PointMm(25.0, -15.0)))
        assertTrue(
            found.uncertain.isEmpty(),
            "a clean synthetic target should not produce doubtful calls",
        )
    }

    @Test
    fun `a blank face with no rings still works`() {
        val truth = listOf(PointMm(0.0, 20.0), PointMm(30.0, -25.0))
        val found = detect(truth, spec = TargetLibrary.BLANK_A4, caliber = Calibers.R_308, holeDiameterMm = 7.82)

        assertEquals(truth.size, found.holes.size)
        assertMatched(truth, found.holes, toleranceMm = 2.0)
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun baseConfig(
        positions: List<PointMm>,
        holeDiameterMm: Double = 4.5,
        pixelsPerMm: Double = 6.0,
    ) = SyntheticTarget.Config(
        pixelsPerMm = pixelsPerMm,
        holes = positions.map { SyntheticTarget.Hole(it, holeDiameterMm) },
    )

    private fun detect(
        positions: List<PointMm>,
        spec: TargetSpec = airPistol,
        caliber: Caliber? = pellet,
        holeDiameterMm: Double = 4.5,
        config: SyntheticTarget.Config = baseConfig(positions, holeDiameterMm),
    ): DetectionResult {
        val rendered = SyntheticTarget.render(spec, config)
        val rectified: RectifiedImage = assertNotNull(
            Rectifier.rectify(rendered.image, rendered.imageToTargetMm, spec),
        )
        val result = HoleDetector.detect(rectified, spec, caliber)
        rectified.release()
        rendered.release()
        return result
    }

    /** Every true shot must have a detection near it, and each detection used only once. */
    private fun assertMatched(
        truth: List<PointMm>,
        found: List<DetectedHole>,
        toleranceMm: Double,
    ) {
        val unmatched = found.toMutableList()
        for (expected in truth) {
            val nearest = unmatched.minByOrNull { it.positionMm.distanceTo(expected) }
            assertNotNull(nearest, "nothing left to match $expected")
            val error = nearest.positionMm.distanceTo(expected)
            assertTrue(
                error <= toleranceMm,
                "closest detection to $expected was ${nearest.positionMm}, ${error}mm away",
            )
            unmatched.remove(nearest)
        }
    }
}
