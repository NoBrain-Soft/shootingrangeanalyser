package com.nobrainsoft.rangeanalyser.vision.live

import com.nobrainsoft.rangeanalyser.core.model.Caliber
import com.nobrainsoft.rangeanalyser.vision.detect.HoleDetector

/**
 * Tells the shooter, before they start, whether live tracking can work at all.
 *
 * At 200 m a .223 hole subtends a few thousandths of a degree. No amount of processing recovers a
 * hole that landed on two pixels, and an app that silently produces nothing - or worse, produces
 * noise - is worse than one that says so up front. This is the honest version of the answer, and it
 * costs the user one glance at the framing screen.
 */
object LiveFeasibility {

    /** Comfortable margin over the detector's absolute floor. */
    private const val GOOD_DIAMETER_PX = 14.0
    private const val MARGINAL_DIAMETER_PX = HoleDetector.MIN_HOLE_DIAMETER_PX

    enum class Verdict {
        /** Holes are big enough in frame to track reliably. */
        GOOD,

        /** It may work, but expect misses. Zooming in or moving closer would help. */
        MARGINAL,

        /** Holes are too small in frame. Live tracking will not work here. */
        UNRELIABLE,
    }

    data class Assessment(
        val verdict: Verdict,
        val holeDiameterPx: Double,
        /** Zoom that would reach [Verdict.GOOD], or null if already there. */
        val suggestedZoomRatio: Double?,
    )

    /**
     * How many pixels across a hole of this calibre will be.
     *
     * Straight from similar triangles: the sensor sees an object of size `s` at distance `d` as
     * `s * f / d` on the sensor, which is that divided by the pixel pitch in pixels.
     */
    fun holeDiameterPx(
        caliber: Caliber,
        distanceM: Double,
        focalLengthMm: Double,
        pixelPitchMm: Double,
        zoomRatio: Double,
    ): Double {
        if (distanceM <= 0.0 || pixelPitchMm <= 0.0) return 0.0
        val distanceMm = distanceM * 1000.0
        return caliber.bulletDiameterMm * (focalLengthMm * zoomRatio) / distanceMm / pixelPitchMm
    }

    fun assess(
        caliber: Caliber,
        distanceM: Double,
        focalLengthMm: Double,
        pixelPitchMm: Double,
        zoomRatio: Double,
        maximumZoomRatio: Double = zoomRatio,
    ): Assessment {
        val diameter = holeDiameterPx(caliber, distanceM, focalLengthMm, pixelPitchMm, zoomRatio)

        val verdict = when {
            diameter >= GOOD_DIAMETER_PX -> Verdict.GOOD
            diameter >= MARGINAL_DIAMETER_PX -> Verdict.MARGINAL
            else -> Verdict.UNRELIABLE
        }

        val neededZoom = if (diameter <= 0.0) {
            null
        } else {
            (zoomRatio * GOOD_DIAMETER_PX / diameter).takeIf {
                verdict != Verdict.GOOD && it <= maximumZoomRatio
            }
        }

        return Assessment(verdict, diameter, neededZoom)
    }

    /** Scale a calibrated live view is working at, when camera intrinsics are not available. */
    fun assessFromScale(caliber: Caliber, millimetresPerPixel: Double): Assessment {
        val diameter = if (millimetresPerPixel <= 0.0) {
            0.0
        } else {
            caliber.bulletDiameterMm / millimetresPerPixel
        }
        val verdict = when {
            diameter >= GOOD_DIAMETER_PX -> Verdict.GOOD
            diameter >= MARGINAL_DIAMETER_PX -> Verdict.MARGINAL
            else -> Verdict.UNRELIABLE
        }
        return Assessment(verdict, diameter, null)
    }
}
