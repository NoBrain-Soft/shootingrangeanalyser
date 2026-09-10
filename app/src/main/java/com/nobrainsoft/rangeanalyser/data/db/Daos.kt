package com.nobrainsoft.rangeanalyser.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FirearmDao {
    @Query("SELECT * FROM firearms ORDER BY name")
    fun observeAll(): Flow<List<FirearmEntity>>

    @Query("SELECT * FROM firearms WHERE id = :id")
    suspend fun find(id: String): FirearmEntity?

    @Upsert
    suspend fun upsert(firearm: FirearmEntity)

    @Delete
    suspend fun delete(firearm: FirearmEntity)
}

@Dao
interface AmmoDao {
    @Query("SELECT * FROM ammo ORDER BY brand, line")
    fun observeAll(): Flow<List<AmmoEntity>>

    @Query("SELECT * FROM ammo WHERE caliberId = :caliberId ORDER BY brand, line")
    fun observeForCaliber(caliberId: String): Flow<List<AmmoEntity>>

    @Query("SELECT * FROM ammo WHERE id = :id")
    suspend fun find(id: String): AmmoEntity?

    @Upsert
    suspend fun upsert(ammo: AmmoEntity)

    @Delete
    suspend fun delete(ammo: AmmoEntity)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY isDefault DESC, lastUsedEpochMs DESC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun find(id: String): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("UPDATE profiles SET lastUsedEpochMs = :epochMs WHERE id = :id")
    suspend fun touch(id: String, epochMs: Long)

    @Query("UPDATE profiles SET isDefault = 0")
    suspend fun clearDefaults()

    @Delete
    suspend fun delete(profile: ProfileEntity)
}

@Dao
interface CustomTargetDao {
    @Query("SELECT * FROM custom_targets ORDER BY name")
    fun observeAll(): Flow<List<CustomTargetEntity>>

    @Query("SELECT * FROM custom_targets WHERE id = :id")
    suspend fun find(id: String): CustomTargetEntity?

    @Upsert
    suspend fun upsert(target: CustomTargetEntity)

    @Delete
    suspend fun delete(target: CustomTargetEntity)
}

/** A session with its shots. Room assembles this from the two tables. */
data class SessionWithShots(
    @androidx.room.Embedded val session: SessionEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "sessionId")
    val shots: List<ShotEntity>,
)

@Dao
interface SessionDao {
    @Transaction
    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<SessionWithShots>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE profileId = :profileId ORDER BY startedAtEpochMs")
    fun observeForProfile(profileId: String): Flow<List<SessionWithShots>>

    @Transaction
    @Query(
        """
        SELECT * FROM sessions
        WHERE firearmId = :firearmId
          AND (:ammoId IS NULL OR ammoId = :ammoId)
        ORDER BY startedAtEpochMs
        """,
    )
    fun observeForFirearm(firearmId: String, ammoId: String?): Flow<List<SessionWithShots>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionWithShots?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun find(id: String): SessionWithShots?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShots(shots: List<ShotEntity>)

    @Query("DELETE FROM shots WHERE sessionId = :sessionId")
    suspend fun deleteShotsFor(sessionId: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("UPDATE sessions SET isFavourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: String, favourite: Boolean)

    /**
     * Replaces a session and its shots as one unit.
     *
     * Saving the session and its shots separately leaves a window where a half-written string is
     * readable, and the statistics layer would happily compute a group from it.
     */
    @Transaction
    suspend fun save(session: SessionEntity, shots: List<ShotEntity>) {
        insertSession(session)
        deleteShotsFor(session.id)
        insertShots(shots)
    }
}
