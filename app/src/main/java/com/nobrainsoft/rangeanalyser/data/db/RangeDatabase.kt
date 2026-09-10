package com.nobrainsoft.rangeanalyser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FirearmEntity::class,
        AmmoEntity::class,
        ProfileEntity::class,
        CustomTargetEntity::class,
        SessionEntity::class,
        ShotEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class RangeDatabase : RoomDatabase() {
    abstract fun firearms(): FirearmDao

    abstract fun ammo(): AmmoDao

    abstract fun profiles(): ProfileDao

    abstract fun customTargets(): CustomTargetDao

    abstract fun sessions(): SessionDao

    companion object {
        @Volatile
        private var instance: RangeDatabase? = null

        fun get(context: Context): RangeDatabase = instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(
                    context.applicationContext,
                    RangeDatabase::class.java,
                    "range-analyser.db",
                )
                // Foreign keys are what guarantee a deleted session takes its shots with it.
                .build()
                .also { instance = it }
        }
    }
}
