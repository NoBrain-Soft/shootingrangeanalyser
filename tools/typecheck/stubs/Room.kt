@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.room

import android.content.Context
import android.content.stub
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS) annotation class Dao
@Target(AnnotationTarget.CLASS) annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val primaryKeys: Array<String> = [],
)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD) annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD) annotation class ColumnInfo(val name: String = "")
@Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) annotation class Insert(val onConflict: Int = 1)
@Target(AnnotationTarget.FUNCTION) annotation class Upsert
@Target(AnnotationTarget.FUNCTION) annotation class Update
@Target(AnnotationTarget.FUNCTION) annotation class Delete
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS) annotation class Transaction
@Target(AnnotationTarget.CLASS) annotation class Database(
    val entities: Array<KClass<*>>,
    val version: Int,
    val exportSchema: Boolean = true,
)
@Target(AnnotationTarget.CLASS) annotation class TypeConverters(vararg val value: KClass<*>)
@Target(AnnotationTarget.FUNCTION) annotation class TypeConverter

annotation class Index(vararg val value: String, val unique: Boolean = false)

annotation class ForeignKey(
    val entity: KClass<*>,
    val parentColumns: Array<String>,
    val childColumns: Array<String>,
    val onDelete: Int = NO_ACTION,
    val onUpdate: Int = NO_ACTION,
) {
    companion object {
        const val NO_ACTION: Int = 1
        const val RESTRICT: Int = 2
        const val SET_NULL: Int = 3
        const val SET_DEFAULT: Int = 4
        const val CASCADE: Int = 5
    }
}

object OnConflictStrategy {
    const val REPLACE: Int = 1
    const val ABORT: Int = 3
    const val IGNORE: Int = 5
}

abstract class RoomDatabase

class RoomDatabaseBuilder<T : RoomDatabase> {
    fun fallbackToDestructiveMigration(): RoomDatabaseBuilder<T> = this
    fun fallbackToDestructiveMigrationOnDowngrade(): RoomDatabaseBuilder<T> = this
    fun build(): T = stub()
}

object Room {
    fun <T : RoomDatabase> databaseBuilder(
        context: Context,
        klass: Class<T>,
        name: String,
    ): RoomDatabaseBuilder<T> = RoomDatabaseBuilder()
}

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
annotation class Embedded(val prefix: String = "")

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
annotation class Relation(
    val parentColumn: String,
    val entityColumn: String,
    val entity: KClass<*> = Any::class,
    val associateBy: Junction = Junction(Any::class),
    val projection: Array<String> = [],
)

annotation class Junction(
    val value: KClass<*>,
    val parentColumn: String = "",
    val entityColumn: String = "",
)
