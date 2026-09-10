package com.nobrainsoft.rangeanalyser.vision.live

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.vision.detect.HoleDetector
import com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
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

    /** The camera or the target is still moving. Waiting for the picture to settle. */
    NOT_SETTLED,

    NOT_ARMED,
}

/**
 * Controls how eager the watcher is.
 *
 * The defaults are deliberately reluctant. A missed shot costs one tap to add by hand; a phantom
 * shot corrupts the group, the score and every piece of coaching built on them, and the shooter has
 * no way to know it was invented. So every threshold here is set to fail towards silence.
 */
data class WatchSettings(
    /**
     * Consecutive frames a candidate must appear on before it is called a shot.
     *
     * Frame counts alone are a poor gate because frame rate varies with light: at 30 fps three
     * frames is a tenth of a second, and a flicker easily lasts that long. [minimumPersistenceMs]
     * runs alongside this so the requirement means the same thing on any device.
     */
    val framesToConfirm: Int = 4,
    /** Wall-clock time a candidate must survive, independent of frame rate. */
    val minimumPersistenceMs: Long = 150,
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
    /**
     * Alignment shift, as a fraction of the frame, above which the picture counts as still moving.
     *
     * Holding the phone up and walking towards the target, or waving a target in front of the lens,
     * produces a stream of large shifts. Nothing detected while that is happening is trustworthy.
     */
    val motionSettleFraction: Double = 0.02,
    /** Settled frames required after motion before candidates are considered again. */
    val framesToSettle: Int = 3,
    /**
     * Detail difference, in grey levels, that counts as a change.
     *
     * A floor rather than the actual threshold: the real one is derived from each frame's own noise,
     * so a grainy picture in poor light does not become a stream of shots.
     */
    val minimumDifference: Double = 18.0,
    /** Multiples of the frame's own noise level a change must exceed. */
    val noiseMultiple: Double = 6.0,
    /** A new candidate this close to a shot already called is the same hole seen again. */
    val duplicateRadiusMm: Double = 6.0,
    /** Shortest gap between two called shots. Guards against a burst from one disturbance. */
    val minimumShotIntervalMs: Long = 200,
)

/**
 * Watches a live camera feed and reports each new hole as it appears.
 *
 * The hard part is not spotting a new dark spot; it is *not* spotting one for every other reason a
 * frame changes. On a real range the phone is nudged, the light shifts as clouds pass, the camera's
 * own auto-exposure hunts, somebody walks downrange, and the target carrier sways in the wind.
 *
 * The defence is in layers, and the first one does most of the work: frames are compared as
 * **high-pass detail**, not as raw pixels. Subtracting a heavily blurred copy of an image throws
 * away everything that varies slowly across it - exposure changes, drifting cloud shadow, a lamp
 * being switched on - while keeping everything the size of a bullet hole. A change in the light
 * moves every pixel in the frame together, so it vanishes almost entirely; a new hole does not.
 *
 * On top of that: each frame is aligned to the reference before comparison, frames that arrive while
 * the picture is still moving are set aside, the threshold is derived from the frame's own noise
 * rather than fixed, and a candidate must hold still in one place for both a number of frames and a
 * span of milliseconds before it is announced.
 *
 * After each confirmed shot the reference is replaced by the current frame, so the new hole becomes
 * part of the background instead of being announced again on every subsequent frame.
 *
 * **Threading.** [onFrame] runs on the camera thread while [arm], [disarm] and [reset] are called
 * from the UI. They share `Mat`s holding native memory, so every entry point is synchronised on this
 * object. Without that, tapping "New target" frees the reference frame while the camera thread is
 * still reading it, and the process dies in native code with no Kotlin stack trace.
 */
class ShotWatcher(
    /** Image pixels to target millimetres, from calibration at arming time. */
    private val imageToTargetMm: Transform2d,
    private val caliber: Caliber?,
    private val settings: WatchSettings = WatchSettings(),
) {
    private var reference: Mat? = null
    private var referenceDetail: Mat? = null
    private val pending = mutableListOf<PendingShot>()
    private val confirmedPositions = mutableListOf<PointMm>()
    private var framesOfWholesaleChange = 0
    private var settledFrames = 0
    private var shotCount = 0
    private var lastConfirmedAtMs = Long.MIN_VALUE
    private var lastShift: Point = Point(0.0, 0.0)

    /**
     * How far the latest frame sits from the reference the shots were measured against.
     *
     * The overlay needs this. Shot positions are fixed in the target's own millimetres, but the
     * phone is hand-held and drifts; adding this puts a marker back over the hole it belongs to
     * instead of leaving it where the target used to be.
     */
    val alignmentShift: Point @Synchronized get() = Point(lastShift.x, lastShift.y)

    val isArmed: Boolean @Synchronized get() = reference != null

    /** Shots confirmed so far this session. */
    val confirmedCount: Int @Synchronized get() = shotCount

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
    @Synchronized
    fun arm(frame: Mat) {
        releaseReference()
        reference = frame.clone()
        referenceDetail = detailOf(frame)
        lastShift = Point(0.0, 0.0)
        pending.clear()
        framesOfWholesaleChange = 0
        settledFrames = 0
    }

    @Synchronized
    fun disarm() {
        releaseReference()
        pending.clear()
        settledFrames = 0
    }

    /** Forgets the shot count as well as the reference, for a fresh target. */
    @Synchronized
    fun reset() {
        disarm()
        confirmedPositions.clear()
        shotCount = 0
        lastConfirmedAtMs = Long.MIN_VALUE
    }

    @Synchronized
    fun onFrame(frame: Mat, timestampMs: Long): FrameOutcome {
        val reference = this.reference ?: return FrameOutcome.Skipped(SkipReason.NOT_ARMED)
        val referenceDetail = this.referenceDetail ?: return FrameOutcome.Skipped(SkipReason.NOT_ARMED)
        if (expectedHoleDiameterPx < HoleDetector.MIN_HOLE_DIAMETER_PX) {
            return FrameOutcome.Skipped(SkipReason.ALIGNMENT_FAILED)
        }

        val shift = estimateShift(reference, frame)
        if (shift != null) lastShift = shift
        val motionLimit = max(frame.cols(), frame.rows()) * settings.motionSettleFraction
        val settled = shift != null && hypot(shift.x, shift.y) <= motionLimit

        // Aligning is only meaningful when there is a usable shift; otherwise compare as-is, which
        // is enough for the gross test below even though it is useless for finding holes.
        val aligned = if (shift != null) translate(frame, -shift.x, -shift.y) else frame.clone()

        // Gross comparison, exposure-matched, purely to decide whether the frame is usable at all.
        // This runs before the motion and alignment gates on purpose: swapping the target for a
        // different one produces both a wholesale change *and* a nonsense alignment, and the whole
        // point of noticing it is to prompt the shooter rather than sit there skipping frames.
        val grossFraction = grossChangedFraction(reference, aligned)

        if (grossFraction >= settings.targetChangedFraction) {
            framesOfWholesaleChange++
            settledFrames = 0
            aligned.release()
            return if (framesOfWholesaleChange >= settings.framesToDeclareTargetChanged) {
                FrameOutcome.TargetChanged
            } else {
                FrameOutcome.Skipped(SkipReason.TOO_MUCH_MOVEMENT)
            }
        }
        framesOfWholesaleChange = 0

        if (shift == null) {
            settledFrames = 0
            aligned.release()
            return FrameOutcome.Skipped(SkipReason.ALIGNMENT_FAILED)
        }

        // Anything still swinging through the frame is not a target being shot at; it is a target
        // being carried, or a phone being raised. Detection on those frames is meaningless.
        if (!settled) {
            settledFrames = 0
            aligned.release()
            return FrameOutcome.Skipped(SkipReason.NOT_SETTLED)
        }

        if (grossFraction >= settings.maximumChangedFraction) {
            settledFrames = 0
            aligned.release()
            return FrameOutcome.Skipped(SkipReason.TOO_MUCH_MOVEMENT)
        }

        if (settledFrames < settings.framesToSettle) {
            settledFrames++
            aligned.release()
            return FrameOutcome.Skipped(SkipReason.NOT_SETTLED)
        }

        val binary = detailDifference(referenceDetail, aligned)
        val candidates = findCandidates(binary)
        binary.release()

        val confirmed = track(candidates, timestampMs)

        if (confirmed.isNotEmpty()) {
            // Fold the new holes into the background so they are not announced again.
            releaseReference()
            this.reference = aligned.clone()
            this.referenceDetail = detailOf(aligned)
            shotCount += confirmed.size
            lastConfirmedAtMs = timestampMs
            confirmed.forEach { confirmedPositions += it.positionMm }
        }
        aligned.release()

        return if (confirmed.isEmpty()) FrameOutcome.Watching else FrameOutcome.Shots(confirmed)
    }

    /**
     * The high-pass detail of an image: what is left after removing everything that varies slowly.
     *
     * The blur radius is tied to the hole size rather than fixed, so the band that survives is
     * always the band a bullet hole occupies. Signed 16-bit, because which way the brightness moved
     * matters - a hole in white paper goes dark, a hole through the black bull often shows lighter
     * torn fibres, and discarding the sign would throw away half the evidence in each case.
     */
    private fun detailOf(image: Mat): Mat {
        val blurred = Mat()
        val sigma = (expectedHoleDiameterPx * DETAIL_SIGMA_FACTOR).coerceAtLeast(2.0)
        Imgproc.GaussianBlur(image, blurred, Size(0.0, 0.0), sigma)

        val signed = Mat()
        val blurredSigned = Mat()
        image.convertTo(signed, CvType.CV_16S)
        blurred.convertTo(blurredSigned, CvType.CV_16S)
        blurred.release()

        val detail = Mat()
        Core.subtract(signed, blurredSigned, detail)
        signed.release()
        blurredSigned.release()
        return detail
    }

    /**
     * Where this frame's detail differs from the reference's, as a binary mask.
     *
     * The threshold comes from the frame's own noise rather than a constant. The same camera in
     * failing light produces several times the grain it does in sunshine, and a fixed threshold is
     * therefore either deaf outdoors at noon or hallucinating at dusk.
     */
    private fun detailDifference(referenceDetail: Mat, aligned: Mat): Mat {
        val alignedDetail = detailOf(aligned)

        val difference = Mat()
        Core.absdiff(referenceDetail, alignedDetail, difference)
        alignedDetail.release()

        val magnitude = Mat()
        difference.convertTo(magnitude, CvType.CV_8U)
        difference.release()

        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        Core.meanStdDev(magnitude, mean, deviation)
        val noise = deviation.toArray().firstOrNull() ?: 0.0
        val centre = mean.toArray().firstOrNull() ?: 0.0
        mean.release()
        deviation.release()

        val threshold = max(settings.minimumDifference, centre + settings.noiseMultiple * noise)

        val binary = Mat()
        Imgproc.threshold(magnitude, binary, threshold, 255.0, Imgproc.THRESH_BINARY)
        magnitude.release()
        return binary
    }

    /**
     * How much of the picture changed outright, after matching the two frames' exposure.
     *
     * Used only to throw whole frames away. Auto-exposure moves the mean and gain moves the spread,
     * and neither is a change in the scene, so both are divided out before anything is counted.
     */
    private fun grossChangedFraction(reference: Mat, aligned: Mat): Double {
        val matched = exposureMatched(aligned, reference)

        val difference = Mat()
        Core.absdiff(reference, matched, difference)
        matched.release()

        val binary = Mat()
        Imgproc.threshold(difference, binary, GROSS_DIFFERENCE_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
        difference.release()

        val fraction = Core.countNonZero(binary).toDouble() / (binary.rows() * binary.cols())
        binary.release()
        return fraction
    }

    /** [source] rescaled so its mean and spread match [reference]'s. */
    private fun exposureMatched(source: Mat, reference: Mat): Mat {
        val sourceMean = MatOfDouble()
        val sourceDeviation = MatOfDouble()
        val referenceMean = MatOfDouble()
        val referenceDeviation = MatOfDouble()
        Core.meanStdDev(source, sourceMean, sourceDeviation)
        Core.meanStdDev(reference, referenceMean, referenceDeviation)

        val sourceSpread = sourceDeviation.toArray().firstOrNull() ?: 0.0
        val referenceSpread = referenceDeviation.toArray().firstOrNull() ?: 0.0
        val sourceCentre = sourceMean.toArray().firstOrNull() ?: 0.0
        val referenceCentre = referenceMean.toArray().firstOrNull() ?: 0.0
        sourceMean.release()
        sourceDeviation.release()
        referenceMean.release()
        referenceDeviation.release()

        // A frame with no spread at all carries no information to rescale; leave it alone.
        val gain = if (sourceSpread < 1e-6) 1.0 else (referenceSpread / sourceSpread)
        val offset = referenceCentre - gain * sourceCentre

        val matched = Mat()
        source.convertTo(matched, CvType.CV_8U, gain, offset)
        return matched
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
            // A hole already called is part of the target now. Re-detecting it - which happens when
            // the light shifts just after a shot - must not add a second one on top of it.
            if (confirmedPositions.any {
                    it.distanceTo(candidate.positionMm) <= settings.duplicateRadiusMm
                }
            ) {
                continue
            }

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
                    firstSeenMs = timestampMs,
                )
            }
        }

        val sinceLast = timestampMs - lastConfirmedAtMs
        val readyForAnother = lastConfirmedAtMs == Long.MIN_VALUE ||
            sinceLast >= settings.minimumShotIntervalMs

        val confirmed = if (!readyForAnother) {
            emptyList()
        } else {
            pending
                .filter {
                    !it.confirmed &&
                        it.sightings >= settings.framesToConfirm &&
                        timestampMs - it.firstSeenMs >= settings.minimumPersistenceMs
                }
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

    private fun releaseReference() {
        reference?.release()
        reference = null
        referenceDetail?.release()
        referenceDetail = null
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
        val firstSeenMs: Long,
        var sightings: Int = 1,
        var framesSinceSeen: Int = 0,
        var confirmed: Boolean = false,
    )

    private companion object {
        const val ALIGNMENT_DOWNSCALE = 4
        const val MAX_SHIFT_FRACTION = 0.25

        /** Blur radius for the high-pass, as a multiple of the hole diameter. */
        const val DETAIL_SIGMA_FACTOR = 1.5

        /** Grey levels of outright difference that count, once exposure has been matched. */
        const val GROSS_DIFFERENCE_THRESHOLD = 30.0

        /** Used only to size kernels when the calibre has not been set. Roughly a .22. */
        const val DEFAULT_ASSUMED_CALIBRE_MM = 5.6
    }
}
