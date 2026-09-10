package com.nobrainsoft.rangeanalyser.vision.live

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.vision.detect.HoleDetector
import com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/** A shot the watcher is confident enough to announce. */
data class LiveShot(
    val positionMm: PointMm,
    val diameterMm: Double,
    val confidence: Double,
    /** Milliseconds since the session started, as supplied by the caller. */
    val timestampMs: Long,
    val pixelCentre: Point,
)

/** What the watcher made of one frame. */
sealed interface FrameOutcome {
    /** Nothing new; keep watching. */
    data object Watching : FrameOutcome

    /** One or more shots confirmed on this frame. */
    data class Shots(val shots: List<LiveShot>) : FrameOutcome

    /** The frame could not be used. Not an error - it happens constantly on a live range. */
    data class Skipped(val reason: SkipReason) : FrameOutcome

    /** So much has changed that this is probably a different target. */
    data object TargetChanged : FrameOutcome
}

enum class SkipReason {
    /** Something large moved through the shot: a person downrange, a hand, the target carrier. */
    TOO_MUCH_MOVEMENT,

    /** The frame could not be lined up with the reference. */
    ALIGNMENT_FAILED,

    NOT_ARMED,
}

/**
 * Controls how eager the watcher is.
 */
data class WatchSettings(
    /**
     * Consecutive frames a candidate must appear on before it is called a shot.
     *
     * The single most important setting here. One frame of confirmation and every leaf blowing past
     * the target becomes a ten. Too many and the announcement lags the shot noticeably.
     */
    val framesToConfirm: Int = 3,
    /** How far a candidate may wander between frames and still be the same candidate. */
    val trackingToleranceMm: Double = 2.0,
    /**
     * Fraction of the frame that may differ from the reference before it is discarded.
     *
     * A bullet hole changes a tiny part of the picture. Anything larger is somebody walking
     * downrange, the target carrier moving, or the light changing - none of which is a shot.
     */
    val maximumChangedFraction: Double = 0.15,
    /** Above this, for several frames running, the target itself has probably been replaced. */
    val targetChangedFraction: Double = 0.45,
    val framesToDeclareTargetChanged: Int = 5,
    /** A candidate not seen for this many frames is forgotten. */
    val framesToForget: Int = 6,
    val differenceThreshold: Double = 30.0,
)

/**
 * Watches a live camera feed and reports each new hole as it appears.
 *
 * The hard part is not spotting a new dark spot; it is *not* spotting one for every other reason a
 * frame changes. On a real range the phone is nudged, the light shifts as clouds pass, somebody
 * walks downrange, and the target carrier sways in the wind. So each frame is aligned to the
 * reference before being compared, frames that changed too much are discarded whole, and a candidate
 * has to survive several consecutive frames in the same place before it is announced.
 *
 * After each confirmed shot the reference is replaced by the current frame, so the new hole becomes
 * part of the background instead of being announced again on every subsequent frame.
 */
class ShotWatcher(
    /** Image pixels to target millimetres, from calibration at arming time. */
    private val imageToTargetMm: Transform2d,
    private val caliber: Caliber?,
    private val settings: WatchSettings = WatchSettings(),
) {
    private var reference: Mat? = null
    private val pending = mutableListOf<PendingShot>()
    private var framesOfWholesaleChange = 0
    private var shotCount = 0

    val isArmed: Boolean get() = reference != null

    /** Shots confirmed so far this session. */
    val confirmedCount: Int get() = shotCount

    /**
     * Expected hole size in camera pixels, from the calibration.
     *
     * Also what [LiveFeasibility] uses to warn the shooter before they start, when the target is too
     * far away or the zoom too low for this to work at all.
     */
    val expectedHoleDiameterPx: Double
        get() {
            val millimetresPerPixel = imageToTargetMm.millimetresPerPixelAtCentre()
            val diameter = caliber?.bulletDiameterMm ?: DEFAULT_ASSUMED_CALIBRE_MM
            return if (millimetresPerPixel <= 0.0) 0.0 else diameter / millimetresPerPixel
        }

    /** Captures the target as it currently stands. Everything after this is measured against it. */
    fun arm(frame: Mat) {
        reference?.release()
        reference = frame.clone()
        pending.clear()
        framesOfWholesaleChange = 0
    }

    fun disarm() {
        reference?.release()
        reference = null
        pending.clear()
    }

    /** Forgets the shot count as well as the reference, for a fresh target. */
    fun reset() {
        disarm()
        shotCount = 0
    }

    fun onFrame(frame: Mat, timestampMs: Long): FrameOutcome {
        val reference = this.reference ?: return FrameOutcome.Skipped(SkipReason.NOT_ARMED)
        if (expectedHoleDiameterPx < HoleDetector.MIN_HOLE_DIAMETER_PX) {
            return FrameOutcome.Skipped(SkipReason.ALIGNMENT_FAILED)
        }

        val shift = estimateShift(reference, frame)
            ?: return FrameOutcome.Skipped(SkipReason.ALIGNMENT_FAILED)

        val aligned = translate(frame, -shift.x, -shift.y)
        val difference = Mat()
        Core.absdiff(reference, aligned, difference)

        val binary = Mat()
        Imgproc.threshold(difference, binary, settings.differenceThreshold, 255.0, Imgproc.THRESH_BINARY)
        difference.release()

        val changedFraction = Core.countNonZero(binary).toDouble() / (binary.rows() * binary.cols())

        if (changedFraction >= settings.targetChangedFraction) {
            framesOfWholesaleChange++
            binary.release()
            aligned.release()
            return if (framesOfWholesaleChange >= settings.framesToDeclareTargetChanged) {
                FrameOutcome.TargetChanged
            } else {
                FrameOutcome.Skipped(SkipReason.TOO_MUCH_MOVEMENT)
            }
        }
        framesOfWholesaleChange = 0

        if (changedFraction >= settings.maximumChangedFraction) {
            binary.release()
            aligned.release()
            return FrameOutcome.Skipped(SkipReason.TOO_MUCH_MOVEMENT)
        }

        val candidates = findCandidates(binary)
        binary.release()

        val confirmed = track(candidates, timestampMs)

        if (confirmed.isNotEmpty()) {
            // Fold the new holes into the background so they are not announced again.
            this.reference?.release()
            this.reference = aligned.clone()
            shotCount += confirmed.size
        }
        aligned.release()

        return if (confirmed.isEmpty()) FrameOutcome.Watching else FrameOutcome.Shots(confirmed)
    }

    /**
     * The difference image contains only what is new, so this is a much simpler problem than
     * analysing a photograph - there are no printed rings to reject. The same shape filters are
     * reused so that live mode and photo mode agree about what a hole looks like.
     */
    private fun findCandidates(binary: Mat): List<Candidate> {
        val diameter = expectedHoleDiameterPx
        HoleDetector.bridgeGaps(binary, diameter)
        HoleDetector.fillEnclosedHoles(binary, diameter)
        HoleDetector.removeThinStructures(binary, diameter)

        return HoleDetector.candidatesFrom(binary, diameter).map { blob ->
            Candidate(
                pixelCentre = blob.centroid,
                positionMm = imageToTargetMm.toTargetMm(blob.centroid),
                diameterMm = 2.0 * kotlin.math.sqrt(blob.enclosedArea / Math.PI) *
                    imageToTargetMm.millimetresPerPixelAtCentre(),
            )
        }
    }

    /**
     * Matches this frame's candidates against those already being watched, and promotes any that
     * have now been seen often enough.
     */
    private fun track(candidates: List<Candidate>, timestampMs: Long): List<LiveShot> {
        pending.forEach { it.framesSinceSeen++ }

        for (candidate in candidates) {
            val existing = pending
                .filter { !it.confirmed }
                .minByOrNull { it.positionMm.distanceTo(candidate.positionMm) }

            if (existing != null &&
                existing.positionMm.distanceTo(candidate.positionMm) <= settings.trackingToleranceMm
            ) {
                existing.sightings++
                existing.framesSinceSeen = 0
                existing.positionMm = candidate.positionMm
                existing.diameterMm = candidate.diameterMm
                existing.pixelCentre = candidate.pixelCentre
            } else {
                pending += PendingShot(
                    positionMm = candidate.positionMm,
                    diameterMm = candidate.diameterMm,
                    pixelCentre = candidate.pixelCentre,
                )
            }
        }

        val confirmed = pending
            .filter { !it.confirmed && it.sightings >= settings.framesToConfirm }
            .map { shot ->
                shot.confirmed = true
                LiveShot(
                    positionMm = shot.positionMm,
                    diameterMm = shot.diameterMm,
                    // A candidate seen on more frames than the minimum is a surer thing.
                    confidence = (shot.sightings.toDouble() / (settings.framesToConfirm * 2))
                        .coerceIn(0.4, 1.0),
                    timestampMs = timestampMs,
                    pixelCentre = shot.pixelCentre,
                )
            }

        // Anything that flickered and vanished was never a hole.
        pending.removeAll { it.confirmed || it.framesSinceSeen > settings.framesToForget }

        return confirmed
    }

    /**
     * Translation between the reference and this frame, by phase correlation on downscaled copies.
     *
     * Phase correlation is used rather than feature matching because it is cheap enough to run on
     * every frame and degrades gracefully: a target with almost no texture still gives a usable
     * answer, where feature detectors give none at all.
     */
    private fun estimateShift(reference: Mat, frame: Mat): Point? {
        if (reference.size() != frame.size()) return null

        val scale = ALIGNMENT_DOWNSCALE
        val width = (frame.cols() / scale).coerceAtLeast(32)
        val height = (frame.rows() / scale).coerceAtLeast(32)

        val referenceSmall = Mat()
        val frameSmall = Mat()
        Imgproc.resize(reference, referenceSmall, Size(width.toDouble(), height.toDouble()))
        Imgproc.resize(frame, frameSmall, Size(width.toDouble(), height.toDouble()))

        val referenceFloat = Mat()
        val frameFloat = Mat()
        referenceSmall.convertTo(referenceFloat, CvType.CV_32F)
        frameSmall.convertTo(frameFloat, CvType.CV_32F)
        referenceSmall.release()
        frameSmall.release()

        val window = Mat()
        Imgproc.createHanningWindow(window, referenceFloat.size(), CvType.CV_32F)
        val shift = Imgproc.phaseCorrelate(referenceFloat, frameFloat, window)

        referenceFloat.release()
        frameFloat.release()
        window.release()

        val scaledShift = Point(shift.x * scale, shift.y * scale)
        // A shift larger than this is not camera shake; something is badly wrong.
        val limit = max(frame.cols(), frame.rows()) * MAX_SHIFT_FRACTION
        if (abs(scaledShift.x) > limit || abs(scaledShift.y) > limit) return null

        return scaledShift
    }

    private fun translate(source: Mat, dx: Double, dy: Double): Mat {
        val matrix = Mat(2, 3, CvType.CV_64F)
        matrix.put(0, 0, 1.0, 0.0, dx, 0.0, 1.0, dy)

        val result = Mat()
        Imgproc.warpAffine(
            source,
            result,
            matrix,
            source.size(),
            Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE,
            org.opencv.core.Scalar(0.0),
        )
        matrix.release()
        return result
    }

    private data class Candidate(
        val pixelCentre: Point,
        val positionMm: PointMm,
        val diameterMm: Double,
    )

    private class PendingShot(
        var positionMm: PointMm,
        var diameterMm: Double,
        var pixelCentre: Point,
        var sightings: Int = 1,
        var framesSinceSeen: Int = 0,
        var confirmed: Boolean = false,
    )

    private companion object {
        const val ALIGNMENT_DOWNSCALE = 4
        const val MAX_SHIFT_FRACTION = 0.25

        /** Used only to size kernels when the calibre has not been set. Roughly a .22. */
        const val DEFAULT_ASSUMED_CALIBRE_MM = 5.6
    }
}
