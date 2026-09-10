package com.nobrainsoft.rangeanalyser.data

import com.nobrainsoft.rangeanalyser.core.geometry.PointMm
import com.nobrainsoft.rangeanalyser.core.geometry.UnitPreference
import com.nobrainsoft.rangeanalyser.core.model.ActionType
import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.BulletType
import com.nobrainsoft.rangeanalyser.core.model.DragModel
import com.nobrainsoft.rangeanalyser.core.model.Firearm
import com.nobrainsoft.rangeanalyser.core.model.FirearmType
import com.nobrainsoft.rangeanalyser.core.model.Handedness
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.model.SavedCalibration
import com.nobrainsoft.rangeanalyser.core.model.Session
import com.nobrainsoft.rangeanalyser.core.model.SessionMode
import com.nobrainsoft.rangeanalyser.core.model.Shot
import com.nobrainsoft.rangeanalyser.core.model.ShootingPosition
import com.nobrainsoft.rangeanalyser.core.model.ShotSource
import com.nobrainsoft.rangeanalyser.core.model.SightSetup
import com.nobrainsoft.rangeanalyser.core.model.SupportType
import com.nobrainsoft.rangeanalyser.core.model.Weather
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.db.AmmoEntity
import com.nobrainsoft.rangeanalyser.data.db.CustomTargetEntity
import com.nobrainsoft.rangeanalyser.data.db.FirearmEntity
import com.nobrainsoft.rangeanalyser.data.db.ProfileEntity
import com.nobrainsoft.rangeanalyser.data.db.SessionEntity
import com.nobrainsoft.rangeanalyser.data.db.SessionWithShots
import com.nobrainsoft.rangeanalyser.data.db.ShotEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Translation between stored rows and the domain model.
 *
 * Enums are stored by name rather than ordinal: an ordinal silently changes meaning the moment
 * somebody inserts a value into the middle of an enum, and the damage only shows up as nonsense in
 * old sessions.
 */
object Mappers {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // --- Firearm ---------------------------------------------------------------------------------

    fun FirearmEntity.toDomain() = Firearm(
        id = id,
        name = name,
        type = enumOr(type, FirearmType.CENTREFIRE_RIFLE),
        caliberId = caliberId,
        action = enumOr(action, ActionType.UNKNOWN),
        barrelLengthMm = barrelLengthMm,
        twistRateMm = twistRateMm,
        sight = json.decodeFromString<SightSetup>(sightJson),
        zeroDistanceM = zeroDistanceM,
        photoPath = photoPath,
        notes = notes,
    )

    fun Firearm.toEntity() = FirearmEntity(
        id = id,
        name = name,
        type = type.name,
        caliberId = caliberId,
        action = action.name,
        barrelLengthMm = barrelLengthMm,
        twistRateMm = twistRateMm,
        sightJson = json.encodeToString(sight),
        zeroDistanceM = zeroDistanceM,
        photoPath = photoPath,
        notes = notes,
    )

    // --- Ammo ------------------------------------------------------------------------------------

    fun AmmoEntity.toDomain() = Ammo(
        id = id,
        brand = brand,
        line = line,
        caliberId = caliberId,
        bulletWeightGrains = bulletWeightGrains,
        bulletType = enumOr(bulletType, BulletType.UNKNOWN),
        muzzleVelocityMps = muzzleVelocityMps,
        velocitySdMps = velocitySdMps,
        ballisticCoefficient = ballisticCoefficient,
        dragModel = enumOr(dragModel, DragModel.G1),
        lot = lot,
        notes = notes,
    )

    fun Ammo.toEntity() = AmmoEntity(
        id = id,
        brand = brand,
        line = line,
        caliberId = caliberId,
        bulletWeightGrains = bulletWeightGrains,
        bulletType = bulletType.name,
        muzzleVelocityMps = muzzleVelocityMps,
        velocitySdMps = velocitySdMps,
        ballisticCoefficient = ballisticCoefficient,
        dragModel = dragModel.name,
        lot = lot,
        notes = notes,
    )

    // --- Profile ---------------------------------------------------------------------------------

    fun ProfileEntity.toDomain() = Profile(
        id = id,
        name = name,
        firearmId = firearmId,
        ammoId = ammoId,
        targetSpecId = targetSpecId,
        distanceM = distanceM,
        position = enumOr(position, ShootingPosition.UNKNOWN),
        support = enumOr(support, SupportType.NONE),
        handedness = enumOr(handedness, Handedness.UNKNOWN),
        calibration = calibrationJson?.let { json.decodeFromString<SavedCalibration>(it) },
        units = json.decodeFromString<UnitPreference>(unitsJson),
        lastUsedEpochMs = lastUsedEpochMs,
        isDefault = isDefault,
    )

    fun Profile.toEntity() = ProfileEntity(
        id = id,
        name = name,
        firearmId = firearmId,
        ammoId = ammoId,
        targetSpecId = targetSpecId,
        distanceM = distanceM,
        position = position.name,
        support = support.name,
        handedness = handedness.name,
        calibrationJson = calibration?.let { json.encodeToString(it) },
        unitsJson = json.encodeToString(units),
        lastUsedEpochMs = lastUsedEpochMs,
        isDefault = isDefault,
    )

    // --- Target ----------------------------------------------------------------------------------

    fun CustomTargetEntity.toDomain(): TargetSpec = json.decodeFromString(specJson)

    fun TargetSpec.toEntity(createdAtEpochMs: Long) = CustomTargetEntity(
        id = id,
        name = name,
        specJson = json.encodeToString(this),
        createdAtEpochMs = createdAtEpochMs,
    )

    // --- Session ---------------------------------------------------------------------------------

    fun SessionWithShots.toDomain() = Session(
        id = session.id,
        name = session.name,
        profileId = session.profileId,
        firearmId = session.firearmId,
        ammoId = session.ammoId,
        targetSpecId = session.targetSpecId,
        distanceM = session.distanceM,
        position = enumOr(session.position, ShootingPosition.UNKNOWN),
        support = enumOr(session.support, SupportType.NONE),
        handedness = enumOr(session.handedness, Handedness.UNKNOWN),
        mode = enumOr(session.mode, SessionMode.PHOTO),
        startedAtEpochMs = session.startedAtEpochMs,
        shots = shots.sortedBy { it.orderIndex }.map { it.toDomain() },
        pointOfAim = PointMm(session.pointOfAimXMm, session.pointOfAimYMm),
        weather = session.weatherJson?.let { json.decodeFromString<Weather>(it) },
        notes = session.notes,
        tags = json.decodeFromString<List<String>>(session.tagsJson),
        imagePath = session.imagePath,
        isFavourite = session.isFavourite,
    )

    fun Session.toEntity() = SessionEntity(
        id = id,
        name = name,
        profileId = profileId,
        firearmId = firearmId,
        ammoId = ammoId,
        targetSpecId = targetSpecId,
        distanceM = distanceM,
        position = position.name,
        support = support.name,
        handedness = handedness.name,
        mode = mode.name,
        startedAtEpochMs = startedAtEpochMs,
        pointOfAimXMm = pointOfAim.x,
        pointOfAimYMm = pointOfAim.y,
        weatherJson = weather?.let { json.encodeToString(it) },
        notes = notes,
        tagsJson = json.encodeToString(tags),
        imagePath = imagePath,
        isFavourite = isFavourite,
    )

    fun ShotEntity.toDomain() = Shot(
        id = id,
        position = PointMm(xMm, yMm),
        orderIndex = orderIndex,
        timestampMs = timestampMs,
        diameterMm = diameterMm,
        confidence = confidence,
        source = enumOr(source, ShotSource.MANUAL),
        cropPath = cropPath,
        excluded = excluded,
    )

    fun Shot.toEntity(sessionId: String) = ShotEntity(
        id = id,
        sessionId = sessionId,
        xMm = position.x,
        yMm = position.y,
        orderIndex = orderIndex,
        timestampMs = timestampMs,
        diameterMm = diameterMm,
        confidence = confidence,
        source = source.name,
        cropPath = cropPath,
        excluded = excluded,
    )

    /**
     * Reads an enum by name, falling back rather than throwing.
     *
     * A row written by a newer version of the app should degrade to a sensible default, not crash
     * the history screen.
     */
    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}
