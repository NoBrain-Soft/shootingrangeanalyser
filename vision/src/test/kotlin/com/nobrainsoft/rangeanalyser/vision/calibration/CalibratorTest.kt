package com.nobrainsoft.rangeanalyser.vision.calibration

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.CalibrationMethod
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import com.nobrainsoft.rangeanalyser.vision.SyntheticTarget
import com.nobrainsoft.rangeanalyser.vision.TestOpenCv
import org.junit.Before
import org.junit.Test
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibratorTest {

    @Before
    fun setUp() = TestOpenCv.ensureLoaded()

    private val airPistol = TargetLibrary.ISSF_AIR_PISTOL_10M

    // --- Ring geometry ---------------------------------------------------------------------------

    @Test
    fun `scale is recovered from the printed aiming mark`() {
        val rendered = SyntheticTarget.render(
            airPistol,
            SyntheticTarget.Config(pixelsPerMm = 4.0),
        )

        val attempt = assertNotNull(Calibrator.fromRingGeometry(rendered.image, airPistol))

        // Rendered at 4 px/mm, so a pixel is 0.25 mm.
        assertEquals(0.25, attempt.millimetresPerPixel, 0.01)
        assertEquals(CalibrationMethod.RING_GEOMETRY, attempt.method)
        assertTrue(attempt.confidence > 0.5, "a clean target should calibrate confidently")
        rendered.release()
    }

    @Test
    fun `shot positions come back where they were put`() {
        // The end-to-end claim: a hole rendered at a known millimetre position must map back to
        // that position through the recovered transform.
        val holes = listOf(
            SyntheticTarget.Hole(PointMm(0.0, 0.0), 4.5),
            SyntheticTarget.Hole(PointMm(30.0, 20.0), 4.5),
            SyntheticTarget.Hole(PointMm(-45.0, -35.0), 4.5),
        )
        val rendered = SyntheticTarget.render(
            airPistol,
            SyntheticTarget.Config(pixelsPerMm = 4.0, holes = holes),
        )

        val attempt = assertNotNull(Calibrator.fromRingGeometry(rendered.image, airPistol))

        for (hole in holes) {
            // Where the renderer put it, in image pixels, via the ground-truth transform.
            val imagePoint = assertNotNull(rendered.imageToTargetMm.inverse())
                .toImagePoint(hole.positionMm)
            val recovered = attempt.transform.toTargetMm(imagePoint)

            assertEquals(hole.positionMm.x, recovered.x, 1.0, "x for hole at ${hole.positionMm}")
            assertEquals(hole.positionMm.y, recovered.y, 1.0, "y for hole at ${hole.positionMm}")
        }
        rendered.release()
    }

    @Test
    fun `a tilted target is still measured correctly`() {
        // An ellipse-shaped aiming mark is the signature of an off-axis photo. Without correcting
        // for it, every distance across the short axis reads too small.
        val holes = listOf(SyntheticTarget.Hole(PointMm(40.0, 0.0), 4.5))
        val rendered = SyntheticTarget.render(
            airPistol,
            SyntheticTarget.Config(pixelsPerMm = 4.0, holes = holes, verticalTilt = 0.22),
        )

        val attempt = assertNotNull(Calibrator.fromRingGeometry(rendered.image, airPistol))
        assertTrue(attempt.correctsPerspective)

        val imagePoint = assertNotNull(rendered.imageToTargetMm.inverse())
            .toImagePoint(PointMm(40.0, 0.0))
        val recovered = attempt.transform.toTargetMm(imagePoint)

        assertEquals(40.0, recovered.radius, 2.5, "recovered $recovered from a tilted target")
        rendered.release()
    }

    @Test
    fun `a rotated target is handled`() {
        val rendered = SyntheticTarget.render(
            airPistol,
            SyntheticTarget.Config(pixelsPerMm = 4.0, rotationDeg = 20.0),
        )
        val attempt = assertNotNull(Calibrator.fromRingGeometry(rendered.image, airPistol))

        // Compared against the renderer's own ground truth rather than the nominal 4 px/mm: a
        // rotated square needs a bigger bounding box, so the renderer shrinks the target to keep it
        // in frame and the real scale is no longer the requested one.
        val trueScale = rendered.imageToTargetMm.millimetresPerPixelAtCentre()
        assertEquals(trueScale, attempt.millimetresPerPixel, trueScale * 0.05)
        rendered.release()
    }

    @Test
    fun `blur, noise and uneven lighting do not break it`() {
        val rendered = SyntheticTarget.render(
            airPistol,
            SyntheticTarget.Config(
                pixelsPerMm = 4.0,
                blurSigma = 1.8,
                noiseSigma = 7.0,
                vignette = 0.45,
            ),
        )
        val attempt = assertNotNull(Calibrator.fromRingGeometry(rendered.image, airPistol))
        assertEquals(0.25, attempt.millimetresPerPixel, 0.02)
        rendered.release()
    }

    @Test
    fun `a face with no printed aiming mark declines rather than inventing a scale`() {
        val rendered = SyntheticTarget.render(TargetLibrary.BLANK_A4, SyntheticTarget.Config())
        assertNull(Calibrator.fromRingGeometry(rendered.image, TargetLibrary.BLANK_A4))
        rendered.release()
    }

    @Test
    fun `a target too small in frame is refused`() {
        // Two pixels per millimetre puts the air rifle bull at about 60 px across, and the black is
        // only 30.5 mm - too little to measure reliably.
        val rendered = SyntheticTarget.render(
            TargetLibrary.ISSF_AIR_RIFLE_10M,
            SyntheticTarget.Config(pixelsPerMm = 1.0),
        )
        assertNull(Calibrator.fromRingGeometry(rendered.image, TargetLibrary.ISSF_AIR_RIFLE_10M))
        rendered.release()
    }

    @Test
    fun `confidence falls away as the target is seen more edge on`() {
        val squareOn = SyntheticTarget.render(airPistol, SyntheticTarget.Config(pixelsPerMm = 4.0))
        val steep = SyntheticTarget.render(
            airPistol,
            SyntheticTarget.Config(pixelsPerMm = 4.0, verticalTilt = 0.55),
        )

        val squareOnAttempt = assertNotNull(Calibrator.fromRingGeometry(squareOn.image, airPistol))
        val steepAttempt = assertNotNull(Calibrator.fromRingGeometry(steep.image, airPistol))

        assertTrue(
            steepAttempt.confidence < squareOnAttempt.confidence,
            "a steeply angled shot should report less confidence",
        )
        squareOn.release()
        steep.release()
    }

    // --- Other methods -----------------------------------------------------------------------------

    @Test
    fun `a marked reference length sets the scale`() {
        val attempt = assertNotNull(
            Calibrator.fromReferenceLength(
                from = Point(100.0, 500.0),
                to = Point(300.0, 500.0),
                realDistanceMm = 50.0,
                targetCentre = Point(200.0, 500.0),
            ),
        )

        assertEquals(0.25, attempt.millimetresPerPixel, 1e-9)
        // 200 px right of centre at 0.25 mm/px is 50 mm right.
        assertEquals(50.0, attempt.transform.toTargetMm(Point(400.0, 500.0)).x, 1e-9)
        // Image y counts down, so a point above the centre must come back positive.
        assertTrue(attempt.transform.toTargetMm(Point(200.0, 400.0)).y > 0)
    }

    @Test
    fun `a longer reference line is trusted more than a short one`() {
        val short = assertNotNull(
            Calibrator.fromReferenceLength(Point(0.0, 0.0), Point(60.0, 0.0), 15.0, Point(30.0, 0.0)),
        )
        val long = assertNotNull(
            Calibrator.fromReferenceLength(Point(0.0, 0.0), Point(600.0, 0.0), 150.0, Point(300.0, 0.0)),
        )
        assertTrue(long.confidence > short.confidence)
    }

    @Test
    fun `a degenerate reference is rejected`() {
        assertNull(
            Calibrator.fromReferenceLength(Point(10.0, 10.0), Point(11.0, 10.0), 50.0, Point(0.0, 0.0)),
        )
    }

    @Test
    fun `hole size gives a scale but never a confident one`() {
        val attempt = assertNotNull(
            Calibrator.fromBulletHoles(
                holeDiametersPx = listOf(22.0, 23.0, 22.5, 21.0, 60.0),
                caliber = Calibers.RF_22_LR,
                targetCentre = Point(500.0, 500.0),
            ),
        )

        // Median of the sorted list is 22.5 px for a 5.69 mm bullet.
        assertEquals(5.69 / 22.5, attempt.millimetresPerPixel, 1e-9)
        assertTrue(
            attempt.confidence < Calibrator.LOW_CONFIDENCE,
            "paper springs back after the bullet passes, so this can never be trusted much",
        )
    }

    @Test
    fun `optics give a scale from focal length and distance`() {
        // A 25 m target, 26 mm equivalent lens, 1.4 micron pixels, no zoom.
        val attempt = assertNotNull(
            Calibrator.fromOptics(
                distanceM = 25.0,
                focalLengthMm = 26.0,
                pixelPitchMm = 0.0014,
                zoomRatio = 1.0,
                targetCentre = Point(0.0, 0.0),
            ),
        )

        assertEquals(0.0014 * 25000.0 / 26.0, attempt.millimetresPerPixel, 1e-9)

        // Zooming in makes each pixel cover less of the target.
        val zoomed = assertNotNull(
            Calibrator.fromOptics(25.0, 26.0, 0.0014, 5.0, Point(0.0, 0.0)),
        )
        assertEquals(attempt.millimetresPerPixel / 5.0, zoomed.millimetresPerPixel, 1e-9)
    }

    @Test
    fun `four tapped corners recover full perspective`() {
        val rendered = SyntheticTarget.render(
            TargetLibrary.BLANK_A4,
            SyntheticTarget.Config(pixelsPerMm = 3.0, verticalTilt = 0.25),
        )
        val inverse = assertNotNull(rendered.imageToTargetMm.inverse())

        val half = PointMm(210.0 / 2, 297.0 / 2)
        val corners = listOf(
            inverse.toImagePoint(PointMm(-half.x, half.y)),
            inverse.toImagePoint(PointMm(half.x, half.y)),
            inverse.toImagePoint(PointMm(half.x, -half.y)),
            inverse.toImagePoint(PointMm(-half.x, -half.y)),
        )

        val attempt = assertNotNull(Calibrator.fromCorners(corners, 210.0, 297.0))
        assertTrue(attempt.correctsPerspective)

        val probe = PointMm(60.0, -80.0)
        val recovered = attempt.transform.toTargetMm(inverse.toImagePoint(probe))
        assertEquals(probe.x, recovered.x, 0.5)
        assertEquals(probe.y, recovered.y, 0.5)
        rendered.release()
    }

    @Test
    fun `corners are ordered consistently however they are tapped`() {
        val expected = listOf(
            Point(10.0, 10.0),
            Point(90.0, 12.0),
            Point(95.0, 88.0),
            Point(8.0, 92.0),
        )
        // Shuffled into a different order, the same four points must come back the same way round.
        val shuffled = listOf(expected[2], expected[0], expected[3], expected[1])
        assertEquals(expected, Calibrator.orderCorners(shuffled))
    }

    // --- Reconciliation -------------------------------------------------------------------------

    @Test
    fun `perspective-correcting methods are preferred`() {
        val rendered = SyntheticTarget.render(airPistol, SyntheticTarget.Config(pixelsPerMm = 4.0))
        val rings = assertNotNull(Calibrator.fromRingGeometry(rendered.image, airPistol))
        val reference = assertNotNull(
            Calibrator.fromReferenceLength(Point(0.0, 0.0), Point(400.0, 0.0), 100.0, Point(200.0, 0.0)),
        )

        val outcome = Calibrator.reconcile(listOf(reference, rings))
        assertEquals(CalibrationMethod.RING_GEOMETRY, outcome.best?.method)
        rendered.release()
    }

    @Test
    fun `methods that disagree raise a warning instead of one quietly winning`() {
        // A silently wrong scale is the worst thing this app can do: every score, group size and
        // tip downstream inherits it and nothing looks broken.
        val first = assertNotNull(
            Calibrator.fromReferenceLength(Point(0.0, 0.0), Point(400.0, 0.0), 100.0, Point(0.0, 0.0)),
        )
        val second = assertNotNull(
            Calibrator.fromReferenceLength(Point(0.0, 0.0), Point(400.0, 0.0), 130.0, Point(0.0, 0.0)),
        )

        val outcome = Calibrator.reconcile(listOf(first, second))
        val disagreement = assertNotNull(outcome.disagreement)
        assertTrue(abs(disagreement.percent - 30.0) < 1.0, "30% apart, reported ${disagreement.percent}")
    }

    @Test
    fun `methods that agree raise nothing`() {
        val first = assertNotNull(
            Calibrator.fromReferenceLength(Point(0.0, 0.0), Point(400.0, 0.0), 100.0, Point(0.0, 0.0)),
        )
        val second = assertNotNull(
            Calibrator.fromReferenceLength(Point(0.0, 0.0), Point(404.0, 0.0), 101.0, Point(0.0, 0.0)),
        )
        assertNull(Calibrator.reconcile(listOf(first, second)).disagreement)
    }

    @Test
    fun `a rough cross-check does not on its own cast doubt on a good calibration`() {
        // Hole-size calibration is deliberately imprecise; it should not trigger a warning against
        // a solid ring-geometry result just by being different.
        val rendered = SyntheticTarget.render(airPistol, SyntheticTarget.Config(pixelsPerMm = 4.0))
        val rings = assertNotNull(Calibrator.fromRingGeometry(rendered.image, airPistol))
        val holes = assertNotNull(
            Calibrator.fromBulletHoles(listOf(14.0), Calibers.AIR_177, Point(0.0, 0.0)),
        )

        assertNull(Calibrator.reconcile(listOf(rings, holes)).disagreement)
        rendered.release()
    }

    @Test
    fun `nothing at all yields no calibration rather than a default`() {
        val outcome = Calibrator.reconcile(emptyList())
        assertNull(outcome.best)
        assertTrue(!outcome.hasResult)
    }
}
