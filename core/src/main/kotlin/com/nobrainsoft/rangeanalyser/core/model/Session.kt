package com.nobrainsoft.rangeanalyser.core.model

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import kotlinx.serialization.Serializable

/**
 * One analysed target: a string of shots plus everything needed to interpret them.
 *
 * The firearm, ammunition, distance and position are recorded on the session rather than only
 * referenced through the profile, because the profile can be edited later and history must stay
 * truthful about what was actually shot.
 */
@Serializable
data class Session(
    val id: String,
    val name: String,
    val profileId: String? = null,
    val firearmId: String,
    val ammoId: String? = null,
    val targetSpecId: String,
    val distanceM: Double,
    val position: ShootingPosition = ShootingPosition.UNKNOWN,
    val support: SupportType = SupportType.NONE,
    val handedness: Handedness = Handedness.UNKNOWN,
    val mode: SessionMode,
    val startedAtEpochMs: Long,
    val shots: List<Shot> = emptyList(),
    /**
     * Where the shooter was aiming, in target coordinates. Normally the centre, but a deliberate
     * hold-off or a group shot at a separate aiming mark makes it something else - and every
     * zeroing calculation is measured from here, not from the target centre.
     */
    val pointOfAim: PointMm = PointMm.ORIGIN,
    val weather: Weather? = null,
    val notes: String = "",
    val tags: List<String> = emptyList(),
    /** Annotated target image saved with the session. */
    val imagePath: String? = null,
    val isFavourite: Boolean = false,
) {
    val shotCount: Int get() = shots.count { !it.excluded }
}

enum class SessionMode { LIVE, PHOTO }

/**
 * Which hand the shooter uses.
 *
 * Only the coaching layer cares, and only for the pistol-shooting associations that are stated
 * left-or-right ("low and left for a right-handed shooter"). Unknown is a perfectly good answer:
 * those tips are then phrased without assuming a side.
 */
enum class Handedness { UNKNOWN, RIGHT, LEFT }

enum class ShootingPosition {
    UNKNOWN,
    PRONE,
    BENCH,
    STANDING,
    KNEELING,
    SITTING,
}

enum class SupportType {
    NONE,
    BIPOD,
    SANDBAG,
    FRONT_REST,
    REAR_BAG,
    BIPOD_AND_REAR_BAG,
    SLING,
    SHOOTING_STICKS,
    BENCH_REST,
}

@Serializable
data class Weather(
    val windSpeedMps: Double? = null,
    /** Degrees clockwise from downrange, so 90 is a pure left-to-right crosswind. */
    val windDirectionDeg: Double? = null,
    val temperatureC: Double? = null,
    val pressureHpa: Double? = null,
    val humidityPercent: Double? = null,
)
