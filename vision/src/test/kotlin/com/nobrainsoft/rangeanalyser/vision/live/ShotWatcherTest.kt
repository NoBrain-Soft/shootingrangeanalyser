package com.nobrainsoft.rangeanalyser.vision.live

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import com.nobrainsoft.rangeanalyser.vision.SyntheticTarget
import com.nobrainsoft.rangeanalyser.vision.TestOpenCv
import com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d
import org.junit.Before
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShotWatcherTest {

    @Before
    fun setUp() = TestOpenCv.ensureLoaded()

    private val target = TargetLibrary.ISSF_PISTOL_25M
    private val caliber = Calibers.P_45
    private val pixelsPerMm = 3.0

    // --- Detecting shots --------------------------------------------------------------------------

    @Test
    fun `a new hole is reported once, after enough frames to be sure`() {
        val session = session()
        session.armWith(emptyList())

        // The same new hole on several frames running.
        val shot = PointMm(30.0, 40.0)
        val outcomes = (1..5).map { frame -> session.feed(listOf(shot), timestampMs = frame * 100L) }

        // Nothing on the first frames: one blip is not a shot.
        assertTrue(
            outcomes.take(2).all { it is FrameOutcome.Watching },
            "should wait for confirmation, got ${outcomes.take(2)}",
        )

        val reports = outcomes.filterIsInstance<FrameOutcome.Shots>()
        assertEquals(1, reports.size, "the same hole must not be announced twice")
        assertEquals(1, reports.single().shots.size)

        val found = reports.single().shots.single()
        assertEquals(shot.x, found.positionMm.x, 4.0)
        assertEquals(shot.y, found.positionMm.y, 4.0)
        session.release()
    }

    @Test
    fun `shots are reported in the order they arrive`() {
        val session = session()
        session.armWith(emptyList())

        val first = PointMm(-40.0, 20.0)
        val second = PointMm(50.0, -30.0)
        val reported = mutableListOf<LiveShot>()

        repeat(5) { frame -> session.collect(listOf(first), frame * 100L, reported) }
        repeat(5) { frame -> session.collect(listOf(first, second), 500L + frame * 100L, reported) }

        assertEquals(2, reported.size, "expected exactly two shots, got ${reported.map { it.positionMm }}")
        assertEquals(first.x, reported[0].positionMm.x, 4.0)
        assertEquals(second.x, reported[1].positionMm.x, 4.0)
        assertTrue(reported[0].timestampMs < reported[1].timestampMs)
        session.release()
    }

    @Test
    fun `an already-present hole is not announced`() {
        // Arming on a target that has been shot before must not report the existing holes.
        val session = session()
        val existing = listOf(PointMm(10.0, 10.0), PointMm(-20.0, 30.0))
        session.armWith(existing)

        val outcomes = (1..6).map { frame -> session.feed(existing, frame * 100L) }
        assertTrue(
            outcomes.none { it is FrameOutcome.Shots },
            "holes that were already there are not new shots",
        )
        session.release()
    }

    @Test
    fun `a still target produces nothing at all`() {
        val session = session()
        session.armWith(emptyList())

        val outcomes = (1..10).map { frame -> session.feed(emptyList(), frame * 100L) }
        assertTrue(outcomes.all { it is FrameOutcome.Watching }, "got $outcomes")
        session.release()
    }

    // --- Coping with a real range -----------------------------------------------------------------

    @Test
    fun `camera drift is compensated`() {
        // A phone on a cheap tripod wanders. Without alignment every frame reads as a total change.
        val session = session()
        session.armWith(emptyList())

        val shot = PointMm(25.0, -35.0)
        val reported = mutableListOf<LiveShot>()
        repeat(6) { frame ->
            session.collect(
                holes = listOf(shot),
                timestampMs = frame * 100L,
                into = reported,
                // Creeping a few pixels further each frame.
                shiftPx = Point(frame * 1.5, frame * -1.0),
            )
        }

        assertEquals(1, reported.size, "drift should not hide the shot, nor invent extras")
        assertEquals(shot.x, reported.single().positionMm.x, 5.0)
        session.release()
    }

    @Test
    fun `somebody walking downrange does not become a shot`() {
        val session = session()
        session.armWith(emptyList())

        // A large dark shape crosses the frame for a few frames, then leaves.
        val outcomes = (1..4).map { frame ->
            session.feed(emptyList(), frame * 100L, occlusion = true)
        } + (5..8).map { frame -> session.feed(emptyList(), frame * 100L) }

        assertTrue(
            outcomes.none { it is FrameOutcome.Shots },
            "an occlusion must never register as a hit",
        )
        assertTrue(
            outcomes.take(4).all { it is FrameOutcome.Skipped },
            "the occluded frames should be discarded, got ${outcomes.take(4)}",
        )
        session.release()
    }

    @Test
    fun `a genuine shot is still caught after an interruption`() {
        val session = session()
        session.armWith(emptyList())

        repeat(3) { frame -> session.feed(emptyList(), frame * 100L, occlusion = true) }

        val shot = PointMm(-30.0, -30.0)
        val reported = mutableListOf<LiveShot>()
        // A disturbance resets the settling count, so recovery costs a few frames of stillness
        // before the confirmation frames start counting again - about a fifth of a second at 30 fps.
        repeat(10) { frame -> session.collect(listOf(shot), 400L + frame * 100L, reported) }

        assertEquals(1, reported.size, "the watcher must recover once the view clears")
        session.release()
    }

    @Test
    fun `the light changing is not a shot`() {
        // Reported from a range: shots appearing by themselves as the light changed. Auto-exposure
        // hunting, a cloud, or a lamp switching on moves every pixel at once - which is exactly what
        // comparing high-pass detail rather than raw pixels is meant to survive.
        val session = session()
        session.armWith(emptyList())

        val exposures = listOf(238.0, 210.0, 250.0, 195.0, 238.0, 225.0, 205.0, 245.0)
        val outcomes = exposures.mapIndexed { frame, paper ->
            session.feed(emptyList(), frame * 100L, paperGray = paper)
        }

        assertTrue(
            outcomes.none { it is FrameOutcome.Shots },
            "a change in exposure invented shots: $outcomes",
        )
        session.release()
    }

    @Test
    fun `a shadow moving across the target is not a shot`() {
        val session = session()
        session.armWith(emptyList())

        val outcomes = (0..7).map { frame ->
            session.feed(emptyList(), frame * 100L, vignette = 0.1 * frame)
        }

        assertTrue(
            outcomes.none { it is FrameOutcome.Shots },
            "drifting shade invented shots: $outcomes",
        )
        session.release()
    }

    @Test
    fun `a real shot is still called when the light is changing`() {
        // The other half of the bargain: rejecting lighting must not cost us the shot itself.
        val session = session()
        session.armWith(emptyList())

        val shot = PointMm(20.0, -25.0)
        val reported = mutableListOf<LiveShot>()
        val exposures = listOf(238.0, 232.0, 244.0, 228.0, 240.0, 236.0, 230.0, 242.0, 234.0)
        exposures.forEachIndexed { frame, paper ->
            session.collect(listOf(shot), frame * 100L, reported, paperGray = paper)
        }

        assertEquals(1, reported.size, "expected the shot, got ${reported.map { it.positionMm }}")
        assertEquals(shot.x, reported.single().positionMm.x, 5.0)
        session.release()
    }

    @Test
    fun `a target being carried about in front of the camera is not shot at`() {
        // Holding a target up to the lens and moving it around: every frame is a large shift.
        val session = session()
        session.armWith(emptyList())

        val outcomes = (1..10).map { frame ->
            val sway = if (frame % 2 == 0) 34.0 else -29.0
            session.feed(emptyList(), frame * 100L, shiftPx = Point(sway, sway * 0.6))
        }

        assertTrue(
            outcomes.none { it is FrameOutcome.Shots },
            "a target being waved about invented shots: $outcomes",
        )
        session.release()
    }

    @Test
    fun `the same hole is not called twice after the reference is rebuilt`() {
        val session = session()
        session.armWith(emptyList())

        val shot = PointMm(-15.0, 35.0)
        val reported = mutableListOf<LiveShot>()
        // Long after the shot is called, with the light moving underneath it.
        repeat(20) { frame ->
            session.collect(
                listOf(shot),
                frame * 100L,
                reported,
                paperGray = if (frame % 3 == 0) 246.0 else 230.0,
            )
        }

        assertEquals(1, reported.size, "one hole, one call: got ${reported.map { it.positionMm }}")
        session.release()
    }

    @Test
    fun `resetting while frames are arriving does not crash`() {
        // arm/disarm/reset are called from the UI thread while onFrame runs on the camera thread,
        // and they share Mats holding native memory. Unsynchronised, this frees the reference frame
        // under the camera thread and the process dies in native code. A regression here does not
        // fail politely - it takes the JVM with it - which is the point of pinning it down.
        val session = session()
        session.armWith(emptyList())
        val frame = session.frameOf(listOf(PointMm(5.0, 5.0)))

        val watcher = session.watcher
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)

        val camera = Thread {
            runCatching {
                var tick = 0L
                while (!stop.get()) {
                    watcher.onFrame(frame, tick)
                    tick += 50
                }
            }.onFailure { failure.set(it) }
        }
        val ui = Thread {
            runCatching {
                repeat(400) {
                    watcher.reset()
                    watcher.arm(frame)
                    watcher.disarm()
                }
            }.onFailure { failure.set(it) }
        }

        camera.start()
        ui.start()
        ui.join()
        stop.set(true)
        camera.join()

        failure.get()?.let { throw AssertionError("concurrent access failed", it) }
        frame.release()
        session.release()
    }

    @Test
    fun `replacing the target is recognised as such`() {
        val session = session()
        session.armWith(emptyList())

        // The whole frame changes, and keeps being different.
        val outcomes = (1..8).map { frame ->
            session.feed(emptyList(), frame * 100L, wholesaleChange = true)
        }

        assertTrue(
            outcomes.any { it is FrameOutcome.TargetChanged },
            "a completely different picture should prompt a new target, got $outcomes",
        )
        session.release()
    }

    @Test
    fun `frames before arming are refused rather than guessed at`() {
        val watcher = ShotWatcher(Transform2d.IDENTITY, caliber)
        val frame = Mat(100, 100, CvType.CV_8UC1, Scalar(255.0))

        val outcome = watcher.onFrame(frame, 0L)
        assertEquals(FrameOutcome.Skipped(SkipReason.NOT_ARMED), outcome)
        frame.release()
    }

    @Test
    fun `the shot count tracks confirmations`() {
        val session = session()
        session.armWith(emptyList())
        assertEquals(0, session.watcher.confirmedCount)

        val reported = mutableListOf<LiveShot>()
        repeat(5) { frame -> session.collect(listOf(PointMm(0.0, 20.0)), frame * 100L, reported) }

        assertEquals(1, session.watcher.confirmedCount)
        session.release()
    }

    // --- Feasibility ------------------------------------------------------------------------------

    @Test
    fun `a close target with a big calibre is fine`() {
        val assessment = LiveFeasibility.assess(
            caliber = Calibers.P_45,
            distanceM = 10.0,
            focalLengthMm = 26.0,
            pixelPitchMm = 0.0014,
            zoomRatio = 1.0,
        )
        assertEquals(LiveFeasibility.Verdict.GOOD, assessment.verdict)
    }

    @Test
    fun `a small calibre at distance is called out as unworkable`() {
        // A .223 hole at 200 m through a phone lens is a couple of pixels. Saying so beforehand is
        // worth far more than silently detecting nothing.
        val assessment = LiveFeasibility.assess(
            caliber = Calibers.R_223,
            distanceM = 200.0,
            focalLengthMm = 26.0,
            pixelPitchMm = 0.0014,
            zoomRatio = 1.0,
        )
        assertEquals(LiveFeasibility.Verdict.UNRELIABLE, assessment.verdict)
        assertTrue(assessment.holeDiameterPx < 6.0)
    }

    @Test
    fun `zoom is suggested when it would actually help`() {
        val assessment = LiveFeasibility.assess(
            caliber = Calibers.RF_22_LR,
            distanceM = 50.0,
            focalLengthMm = 26.0,
            pixelPitchMm = 0.0014,
            zoomRatio = 1.0,
            maximumZoomRatio = 10.0,
        )
        val zoom = assertNotNull(assessment.suggestedZoomRatio, "should suggest zooming in")
        assertTrue(zoom > 1.0 && zoom <= 10.0)
    }

    @Test
    fun `no zoom is suggested when the camera cannot reach it`() {
        val assessment = LiveFeasibility.assess(
            caliber = Calibers.AIR_177,
            distanceM = 300.0,
            focalLengthMm = 26.0,
            pixelPitchMm = 0.0014,
            zoomRatio = 1.0,
            maximumZoomRatio = 5.0,
        )
        assertEquals(LiveFeasibility.Verdict.UNRELIABLE, assessment.verdict)
        assertEquals(null, assessment.suggestedZoomRatio)
    }

    // --- Fixture ----------------------------------------------------------------------------------

    private fun session() = WatchSession(target, caliber, pixelsPerMm)

    /** Builds frames of a target being shot, and drives a watcher with them. */
    private class WatchSession(
        val spec: com.nobrainsoft.rangeanalyser.core.target.TargetSpec,
        val caliber: com.nobrainsoft.rangeanalyser.core.model.Caliber,
        val pixelsPerMm: Double,
    ) {
        lateinit var watcher: ShotWatcher
        private val frames = mutableListOf<Mat>()

        private fun render(
            holes: List<PointMm>,
            paperGray: Double = 238.0,
            vignette: Double = 0.0,
        ): Mat {
            val rendered = SyntheticTarget.render(
                spec,
                SyntheticTarget.Config(
                    pixelsPerMm = pixelsPerMm,
                    holes = holes.map { SyntheticTarget.Hole(it, caliber.bulletDiameterMm) },
                    noiseSigma = 2.0,
                    paperGray = paperGray,
                    vignette = vignette,
                ),
            )
            val image = rendered.image
            if (!::watcher.isInitialized) {
                watcher = ShotWatcher(rendered.imageToTargetMm, caliber)
            }
            frames += image
            return image
        }

        /** A frame the caller owns and releases itself, for driving the watcher directly. */
        fun frameOf(holes: List<PointMm>): Mat = render(holes).clone()

        fun armWith(holes: List<PointMm>) {
            // render() builds the watcher on first call, since it is what knows the true transform.
            val frame = render(holes)
            watcher.arm(frame)
            // The watcher waits for the picture to settle before it will call anything, so give it
            // the still frames it asks for. A real session gets these while the shooter takes aim.
            repeat(SETTLING_FRAMES) { watcher.onFrame(render(holes), -1L) }
        }

        fun feed(
            holes: List<PointMm>,
            timestampMs: Long,
            shiftPx: Point = Point(0.0, 0.0),
            occlusion: Boolean = false,
            wholesaleChange: Boolean = false,
            paperGray: Double = 238.0,
            vignette: Double = 0.0,
        ): FrameOutcome {
            var frame = render(holes, paperGray, vignette)
            if (shiftPx.x != 0.0 || shiftPx.y != 0.0) {
                frame = shifted(frame, shiftPx)
                frames += frame
            }
            if (occlusion) {
                frame = frame.clone()
                // A person-sized dark shape across a third of the frame.
                Imgproc.rectangle(
                    frame,
                    Point(frame.cols() * 0.25, 0.0),
                    Point(frame.cols() * 0.6, frame.rows().toDouble()),
                    Scalar(40.0),
                    -1,
                )
                frames += frame
            }
            if (wholesaleChange) {
                frame = Mat(frame.rows(), frame.cols(), CvType.CV_8UC1, Scalar(90.0))
                Imgproc.circle(
                    frame,
                    Point(frame.cols() / 3.0, frame.rows() / 3.0),
                    frame.cols() / 5,
                    Scalar(220.0),
                    -1,
                )
                frames += frame
            }
            return watcher.onFrame(frame, timestampMs)
        }

        fun collect(
            holes: List<PointMm>,
            timestampMs: Long,
            into: MutableList<LiveShot>,
            shiftPx: Point = Point(0.0, 0.0),
            paperGray: Double = 238.0,
        ) {
            val outcome = feed(holes, timestampMs, shiftPx, paperGray = paperGray)
            if (outcome is FrameOutcome.Shots) into += outcome.shots
        }

        private fun shifted(source: Mat, by: Point): Mat {
            val matrix = Mat(2, 3, CvType.CV_64F)
            matrix.put(0, 0, 1.0, 0.0, by.x, 0.0, 1.0, by.y)
            val result = Mat()
            Imgproc.warpAffine(
                source,
                result,
                matrix,
                source.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
                Scalar(255.0),
            )
            matrix.release()
            return result
        }

        fun release() {
            frames.forEach { it.release() }
            frames.clear()
            watcher.reset()
        }

        companion object {
            /** Matches WatchSettings.framesToSettle. */
            const val SETTLING_FRAMES = 3
        }
    }
}
