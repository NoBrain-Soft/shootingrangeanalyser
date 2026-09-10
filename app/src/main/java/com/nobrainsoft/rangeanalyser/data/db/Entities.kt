package com.nobrainsoft.rangeanalyser.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Storage shapes, kept separate from the domain model in `:core`.
 *
 * The duplication is deliberate. `:core` stays a plain Kotlin module with no Room annotations, which
 * is what lets every scoring and statistics rule be unit-tested on a desktop JVM. The cost is a
 * mapping layer; the benefit is that the part of the app most likely to be wrong is also the part
 * that is fully tested.
 *
 * Nested values - sight setups, calibrations, weather - are stored as JSON rather than flattened
 * into columns. They are read and written whole, never queried on.
 */
@Entity(tableName = "firearms")
data class FirearmEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val caliberId: String,
    val action: String,
    val barrelLengthMm: Double?,
    val twistRateMm: Double?,
    val sightJson: String,
    val zeroDistanceM: Double?,
    val photoPath: String?,
    val notes: String,
)

@Entity(tableName = "ammo")
data class AmmoEntity(
    @PrimaryKey val id: String,
    val brand: String,
    val line: String,
    val caliberId: String,
    val bulletWeightGrains: Double?,
    val bulletType: String,
    val muzzleVelocityMps: Double?,
    val velocitySdMps: Double?,
    val ballisticCoefficient: Double?,
    val dragModel: String,
    val lot: String,
    val notes: String,
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val firearmId: String,
    val ammoId: String?,
    val targetSpecId: String,
    val distanceM: Double,
    val position: String,
    val support: String,
    val handedness: String,
    val calibrationJson: String?,
    val unitsJson: String,
    val lastUsedEpochMs: Long,
    val isDefault: Boolean,
)

/** A target face the user defined or measured, stored whole as JSON. */
@Entity(tableName = "custom_targets")
data class CustomTargetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val specJson: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "sessions",
    indices = [Index("startedAtEpochMs"), Index("firearmId"), Index("ammoId"), Index("profileId")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val profileId: String?,
    val firearmId: String,
    val ammoId: String?,
    val targetSpecId: String,
    val distanceM: Double,
    val position: String,
    val support: String,
    val handedness: String,
    val mode: String,
    val startedAtEpochMs: Long,
    val pointOfAimXMm: Double,
    val pointOfAimYMm: Double,
    val weatherJson: String?,
    val notes: String,
    val tagsJson: String,
    val imagePath: String?,
    val isFavourite: Boolean,
)

@Entity(
    tableName = "shots",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            // Deleting a session must take its shots with it; an orphaned shot would quietly
            // corrupt every aggregate that scans the table.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ShotEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val xMm: Double,
    val yMm: Double,
    val orderIndex: Int,
    val timestampMs: Long?,
    val diameterMm: Double?,
    val confidence: Double,
    val source: String,
    val cropPath: String?,
    val excluded: Boolean,
)
