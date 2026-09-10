package com.nobrainsoft.rangeanalyser.vision.detect

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.target.Discipline
import com.nobrainsoft.rangeanalyser.core.target.Ring
import com.nobrainsoft.rangeanalyser.core.target.ScoringModel
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.vision.SyntheticTarget
import com.nobrainsoft.rangeanalyser.vision.TestOpenCv
import com.nobrainsoft.rangeanalyser.vision.calibration.Rectifier
import org.junit.Before
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two things a real range does that a tidy fixture does not: shoot tight groups, and print its
 * own target face.
 *
 * Reported from a range: eleven holes in a club's own A4 face, one detected. The cause was not the
 * unfamiliar face at all - it was the group. Five shots inside the nine ring merged into one blob,
 * which was then too wide to pass for a hole and too small to be printing, so it was discarded
 * whole. The closer someone shot, the less the app found.
 */
class RealRangeTargetTest {

    @Before
    fun setUp() = TestOpenCv.ensureLoaded()

    private val caliber = Calibers.P_9MM

    /**
     * A club face: a black bull about 90 mm across on A4, rings inside it, numbered outward.
     *
     * Deliberately not any published standard, which is the point - it is what a range prints.
     */
    private val clubFace = TargetSpec(
        id = "test-club",
        name = "Club face",
        discipline = Discipline.BULLSEYE_PISTOL,
        scoring = ScoringModel.Rings(
            rings = listOf(
                Ring(10, 24.0),
                Ring(9, 48.0),
                Ring(8, 72.0),
                Ring(7, 96.0),
                Ring(6, 120.0),
                Ring(5, 144.0),
                Ring(4, 168.0),
            ),
            supportsDecimal = true,
        ),
        sheetWidthMm = 210.0,
        sheetHeightMm = 297.0,
        blackDiameterMm = 96.0,
        defaultDistanceM = 25.0,
        isBuiltIn = false,
    )

    private val shots = listOf(
        PointMm(2.0, 14.0),
        PointMm(-9.0, 6.0),
        PointMm(6.0, 2.0),
        PointMm(-4.0, -8.0),
        PointMm(12.0, -14.0),
        PointMm(-18.0, 22.0),
        PointMm(24.0, 9.0),
        PointMm(-2.0, 34.0),
        PointMm(31.0, -5.0),
        PointMm(-26.0, -20.0),
        PointMm(8.0, 45.0),
    )

    @Test
    fun `a tight central group is not swallowed whole`() {
        // The reported failure, reduced: five shots inside the nine ring and nothing else. Every one
        // of them is found alone; welded into a single blob they were all lost.
        val cluster = shots.take(5)
        val found = detectAs(clubFace, cluster)
        val missed = cluster.filter { shot ->
            found.holes.none { it.positionMm.distanceTo(shot) <= 4.0 }
        }
        assertTrue(missed.isEmpty(), "missed ${missed.size} of ${cluster.size} in a tight group: $missed")
    }

    @Test
    fun `the right face finds the shots`() {
        // The control. If this ever fails the fallback is not the thing that broke.
        val found = detectAs(clubFace)
        val missed = shots.filter { shot ->
            found.holes.none { it.positionMm.distanceTo(shot) <= 4.0 }
        }
        val spurious = found.holes.filter { hole ->
            shots.none { it.distanceTo(hole.positionMm) <= 4.0 }
        }
        assertTrue(
            missed.isEmpty(),
            "missed ${missed.size}: $missed\n" +
                "found ${found.holes.size}: ${found.holes.map { h ->
                    "%.0f,%.0f d=%.1f n=%d".format(
                        h.positionMm.x, h.positionMm.y, h.diameterMm, h.shotsInBlob,
                    )
                }}\n" +
                "spurious ${spurious.size}\n" +
                "expected hole px = ${found.expectedHoleDiameterPx}",
        )
    }

    @Test
    fun `the wrong face still finds most of the shots`() {
        // ISSF 25 m precision is a 500 mm face; the club's is 168 mm. Subtracting one from a
        // photograph of the other leaves ring lines everywhere and buries the holes. Detection has
        // to survive being pointed at the wrong target, because that is a setting a shooter can get
        // wrong in one tap and has no way to notice.
        val found = detectAs(TargetLibrary.ISSF_PISTOL_25M)

        assertTrue(
            found.holes.size >= shots.size / 2,
            "found only ${found.holes.size} of ${shots.size} shots against a mismatched face",
        )
    }

    // --- Fixture ----------------------------------------------------------------------------------

    private fun rendered(only: List<PointMm> = shots) = SyntheticTarget.render(
        clubFace,
        SyntheticTarget.Config(
            pixelsPerMm = 6.0,
            holes = only.map { SyntheticTarget.Hole(it, caliber.bulletDiameterMm) },
            noiseSigma = 1.5,
            blurSigma = 0.6,
        ),
    )

    private fun detectAs(spec: TargetSpec, only: List<PointMm> = shots): DetectionResult {
        val render = rendered(only)
        val rectified = assertNotNull(
            Rectifier.rectify(render.image, render.imageToTargetMm, spec),
        )
        val result = HoleDetector.detect(rectified, spec, caliber)
        rectified.release()
        render.release()
        return result
    }
}
