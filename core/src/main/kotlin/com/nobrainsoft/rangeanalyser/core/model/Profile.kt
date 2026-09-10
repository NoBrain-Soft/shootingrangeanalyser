package com.nobrainsoft.rangeanalyser.core.model

import com.nobrainsoft.rangeanalyser.core.geometry.UnitPreference
import kotlinx.serialization.Serializable

/**
 * A saved loadout: gun, ammunition, target, distance and how you shoot it.
 *
 * This is the object the whole UX pivots on. Setting up a session at the range means picking a
 * profile, not filling in six fields with cold hands - and because a profile can carry a
 * [SavedCalibration], a regular setup calibrates once and never again.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val firearmId: String,
    val ammoId: String? = null,
    val targetSpecId: String,
    val distanceM: Double,
    val position: ShootingPosition = ShootingPosition.UNKNOWN,
    val support: SupportType = SupportType.NONE,
    val calibration: SavedCalibration? = null,
    val units: UnitPreference = UnitPreference(),
    val lastUsedEpochMs: Long = 0L,
    val isDefault: Boolean = false,
)

/**
 * A calibration result, stored so it can be reused and audited.
 *
 * Lives in `:core` rather than `:vision` so profiles and sessions can carry one without the domain
 * model depending on OpenCV. `:vision` produces these and consumes them again.
 */
@Serializable
data class SavedCalibration(
    val method: CalibrationMethod,
    val mmPerPixel: Double,
    /**
     * Row-major 3x3 mapping image pixels to target millimetres, when the method recovered
     * perspective. Null means scale-only, which is correct only for a square-on shot.
     */
    val homography: List<Double>? = null,
    /** The real-world length the user measured, for [CalibrationMethod.MANUAL_REFERENCE]. */
    val referenceLengthMm: Double? = null,
    val capturedAtEpochMs: Long = 0L,
    /** 0..1. Surfaced in the UI - a low-confidence calibration is worth re-doing, not hiding. */
    val confidence: Double = 0.0,
) {
    init {
        require(homography == null || homography.size == 9) {
            "homography must be a row-major 3x3 matrix, got ${homography?.size} values"
        }
    }

    val correctsPerspective: Boolean get() = homography != null
}

enum class CalibrationMethod {
    /** Fit the target's own printed rings. Most accurate, and needs nothing from the user. */
    RING_GEOMETRY,

    /** Find the sheet outline and use its known paper size. */
    PAPER_QUAD,

    /** User marks two points and states the real distance between them. */
    MANUAL_REFERENCE,

    /** Infer scale from measured hole size against the known bullet diameter. Low confidence. */
    BULLET_CALIBER,

    /** Camera intrinsics plus a known target distance. */
    OPTICAL_DISTANCE,
}
