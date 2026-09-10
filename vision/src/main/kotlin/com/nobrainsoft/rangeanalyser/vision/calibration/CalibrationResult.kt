package com.nobrainsoft.rangeanalyser.vision.calibration

import com.nobrainsoft.rangeanalyser.core.model.CalibrationMethod
import com.nobrainsoft.rangeanalyser.core.model.SavedCalibration
import com.nobrainsoft.rangeanalyser.vision.geometry.Transform2d
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import kotlin.math.abs
import kotlin.math.max

/**
 * One method's answer to "how many millimetres is a pixel, and where is the target?".
 *
 * Attempts are kept separate rather than merged so the wizard can show what each method found,
 * and so a disagreement between two of them surfaces instead of being silently resolved.
 */
data class CalibrationAttempt(
    val method: CalibrationMethod,
    /** Image pixels to target millimetres. */
    val transform: Transform2d,
    val millimetresPerPixel: Double,
    /** 0..1. Shown to the user; a low number is a prompt to try another method, not a secret. */
    val confidence: Double,
    /**
     * Whether the transform undoes an off-axis camera angle, or only scales.
     *
     * Scale-only calibration is correct exactly when the camera was square on to the target. Any
     * tilt then shows up as a systematic error in shot positions, largest at the edges of the face.
     */
    val correctsPerspective: Boolean,
    /** What the method found, for the wizard to draw over the preview. */
    val evidence: CalibrationEvidence? = null,
) {
    fun toSaved(capturedAtEpochMs: Long = 0L) = SavedCalibration(
        method = method,
        mmPerPixel = millimetresPerPixel,
        homography = transform.values.toList(),
        capturedAtEpochMs = capturedAtEpochMs,
        confidence = confidence,
    )
}

/** What a calibration method actually latched onto, so the user can see whether it picked right. */
sealed interface CalibrationEvidence {
    /** The fitted ring or aiming mark, in image coordinates. */
    data class Ellipse(val fitted: RotatedRect, val knownDiameterMm: Double) : CalibrationEvidence

    /** The detected sheet outline, clockwise from the top left. */
    data class Quad(val corners: List<Point>, val widthMm: Double, val heightMm: Double) :
        CalibrationEvidence

    /** The two points the user marked and what they said the distance was. */
    data class Reference(val from: Point, val to: Point, val realDistanceMm: Double) :
        CalibrationEvidence

    /** Holes measured to infer scale from a known bullet diameter. */
    data class Holes(val diameterPx: List<Double>, val bulletDiameterMm: Double) :
        CalibrationEvidence
}

/**
 * The result of trying everything available.
 *
 * When two methods disagree materially the outcome says so rather than picking a winner quietly.
 * A silently wrong scale is the worst failure this app can have: every measurement, score and tip
 * downstream inherits it, and nothing looks broken.
 */
data class CalibrationOutcome(
    val best: CalibrationAttempt?,
    val alternatives: List<CalibrationAttempt>,
    val disagreement: CalibrationDisagreement?,
) {
    val all: List<CalibrationAttempt>
        get() = listOfNotNull(best) + alternatives

    val hasResult: Boolean get() = best != null
}

data class CalibrationDisagreement(
    val methods: List<CalibrationMethod>,
    /** How far apart the two scales are, as a fraction of the smaller. */
    val relativeDifference: Double,
) {
    val percent: Double get() = relativeDifference * 100.0
}

internal object Confidence {
    /** Keeps a computed score inside 0..1 without pretending to more precision than it has. */
    fun clamp(value: Double): Double = value.coerceIn(0.0, 1.0)

    /**
     * How close two scales are, as a fraction of the smaller. Used both to score agreement between
     * methods and to decide when to warn.
     */
    fun relativeDifference(a: Double, b: Double): Double {
        val smaller = kotlin.math.min(abs(a), abs(b))
        if (smaller <= 0.0) return Double.POSITIVE_INFINITY
        return abs(a - b) / smaller
    }

    /** Ramps from 0 at [floor] to 1 at [ceiling]. */
    fun ramp(value: Double, floor: Double, ceiling: Double): Double {
        if (ceiling <= floor) return if (value >= ceiling) 1.0 else 0.0
        return clamp((value - floor) / (ceiling - floor))
    }

    /** Penalises extreme foreshortening, where a fitted ellipse tells you less about scale. */
    fun tiltScore(minorAxis: Double, majorAxis: Double): Double {
        if (majorAxis <= 0.0) return 0.0
        return ramp(minorAxis / max(majorAxis, 1e-9), 0.25, 0.75)
    }
}
