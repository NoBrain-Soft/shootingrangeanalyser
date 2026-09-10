package com.nobrainsoft.rangeanalyser.core.model

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import kotlinx.serialization.Serializable

/**
 * One hole in the target.
 *
 * [confidence] and [source] exist because no detector is perfect: the review screen shows what the
 * app is unsure about, and records when a human overruled it. Analysis never silently drops a
 * low-confidence shot - only [excluded] does that, and only a person sets it.
 */
@Serializable
data class Shot(
    val id: String,
    val position: PointMm,
    val orderIndex: Int,
    /** Milliseconds since the session started. Live mode only; null for photo analysis. */
    val timestampMs: Long? = null,
    /** Measured hole diameter, used as a calibration cross-check and a plausibility filter. */
    val diameterMm: Double? = null,
    val confidence: Double = 1.0,
    val source: ShotSource = ShotSource.MANUAL,
    /** Small image crop saved at detection time so the user can check a doubtful call. */
    val cropPath: String? = null,
    /** Set by the user for sighters or a hole that turned out not to be a shot. */
    val excluded: Boolean = false,
)

enum class ShotSource {
    /** Found by the detector, unreviewed. */
    AUTO,

    /** Found by the detector, then moved or confirmed by the user. */
    AUTO_EDITED,

    /** Placed by the user. */
    MANUAL,
}

/** Shots that count towards scoring and statistics, in firing order. */
fun List<Shot>.scoring(): List<Shot> = filterNot { it.excluded }.sortedBy { it.orderIndex }

/** Just the positions, for the statistics layer. */
fun List<Shot>.positions(): List<PointMm> = map { it.position }
