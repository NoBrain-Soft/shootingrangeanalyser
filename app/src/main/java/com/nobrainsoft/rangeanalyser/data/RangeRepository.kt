package com.nobrainsoft.rangeanalyser.data

import com.nobrainsoft.rangeanalyser.core.analysis.AnalysedSession
import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.Firearm
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.model.Session
import com.nobrainsoft.rangeanalyser.core.target.TargetLibrary
import com.nobrainsoft.rangeanalyser.core.target.TargetSpec
import com.nobrainsoft.rangeanalyser.data.Mappers.toDomain
import com.nobrainsoft.rangeanalyser.data.Mappers.toEntity
import com.nobrainsoft.rangeanalyser.data.db.RangeDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The app's single door to stored data.
 *
 * Everything crossing this boundary is a `:core` domain object, so screens and view models never see
 * a Room entity and the analysis engine never learns that a database exists.
 */
class RangeRepository(private val database: RangeDatabase) {

    // --- Firearms --------------------------------------------------------------------------------

    fun observeFirearms(): Flow<List<Firearm>> =
        database.firearms().observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun findFirearm(id: String): Firearm? = database.firearms().find(id)?.toDomain()

    suspend fun saveFirearm(firearm: Firearm) = database.firearms().upsert(firearm.toEntity())

    suspend fun deleteFirearm(firearm: Firearm) = database.firearms().delete(firearm.toEntity())

    // --- Ammunition ------------------------------------------------------------------------------

    fun observeAmmo(): Flow<List<Ammo>> =
        database.ammo().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeAmmoForCaliber(caliberId: String): Flow<List<Ammo>> =
        database.ammo().observeForCaliber(caliberId).map { rows -> rows.map { it.toDomain() } }

    suspend fun findAmmo(id: String): Ammo? = database.ammo().find(id)?.toDomain()

    suspend fun saveAmmo(ammo: Ammo) = database.ammo().upsert(ammo.toEntity())

    suspend fun deleteAmmo(ammo: Ammo) = database.ammo().delete(ammo.toEntity())

    // --- Profiles --------------------------------------------------------------------------------

    fun observeProfiles(): Flow<List<Profile>> =
        database.profiles().observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun findProfile(id: String): Profile? = database.profiles().find(id)?.toDomain()

    suspend fun saveProfile(profile: Profile) {
        // Only one profile can be the default, and the UI should not have to police that.
        if (profile.isDefault) database.profiles().clearDefaults()
        database.profiles().upsert(profile.toEntity())
    }

    suspend fun markProfileUsed(id: String, epochMs: Long) = database.profiles().touch(id, epochMs)

    suspend fun deleteProfile(profile: Profile) = database.profiles().delete(profile.toEntity())

    // --- Targets ---------------------------------------------------------------------------------

    /** Built-in faces plus anything the user defined, as one list. */
    fun observeTargets(): Flow<List<TargetSpec>> =
        database.customTargets().observeAll().map { rows ->
            TargetLibrary.all + rows.map { it.toDomain() }
        }

    suspend fun findTarget(id: String): TargetSpec? =
        TargetLibrary.find(id) ?: database.customTargets().find(id)?.toDomain()

    suspend fun saveCustomTarget(spec: TargetSpec, createdAtEpochMs: Long) =
        database.customTargets().upsert(spec.toEntity(createdAtEpochMs))

    suspend fun deleteCustomTarget(spec: TargetSpec) =
        database.customTargets().find(spec.id)?.let { database.customTargets().delete(it) }

    // --- Sessions --------------------------------------------------------------------------------

    fun observeSessions(): Flow<List<Session>> =
        database.sessions().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeSession(id: String): Flow<Session?> =
        database.sessions().observe(id).map { it?.toDomain() }

    suspend fun findSession(id: String): Session? = database.sessions().find(id)?.toDomain()

    suspend fun saveSession(session: Session) = database.sessions().save(
        session = session.toEntity(),
        shots = session.shots.map { it.toEntity(session.id) },
    )

    suspend fun deleteSession(id: String) = database.sessions().deleteSession(id)

    suspend fun setFavourite(id: String, favourite: Boolean) =
        database.sessions().setFavourite(id, favourite)

    /**
     * Sessions with their statistics already worked out, and their firearm, load and target
     * resolved.
     *
     * Everything downstream - history, comparison, trends, coaching - wants exactly this, so it is
     * assembled once here rather than re-derived on each screen.
     */
    fun observeAnalysedSessions(): Flow<List<AnalysedSession>> = combine(
        database.sessions().observeAll(),
        database.firearms().observeAll(),
        database.ammo().observeAll(),
        database.customTargets().observeAll(),
    ) { sessions, firearms, ammo, customTargets ->
        val firearmsById = firearms.associate { it.id to it.toDomain() }
        val ammoById = ammo.associate { it.id to it.toDomain() }
        val targetsById = (TargetLibrary.all + customTargets.map { it.toDomain() })
            .associateBy { it.id }

        sessions.mapNotNull { row ->
            val session = row.toDomain()
            AnalysedSession.analyse(
                session = session,
                firearm = firearmsById[session.firearmId],
                ammo = session.ammoId?.let { ammoById[it] },
                target = targetsById[session.targetSpecId],
            )
        }
    }
}
