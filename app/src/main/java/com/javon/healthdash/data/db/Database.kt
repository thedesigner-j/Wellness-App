package com.javon.healthdash.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "glucose", indices = [Index("timeMs")])
data class GlucoseEntity(@PrimaryKey val id: String, val timeMs: Long, val mgdl: Double, val source: String)

@Entity(tableName = "steps", indices = [Index("startMs")])
data class StepsEntity(@PrimaryKey val id: String, val startMs: Long, val endMs: Long, val count: Long, val source: String)

@Entity(tableName = "workouts", indices = [Index("startMs")])
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val startMs: Long,
    val endMs: Long,
    val exerciseType: Int,
    val title: String?,
    val source: String,
)

/** Weight (lb) and body-fat (%) readings. */
@Entity(tableName = "body", indices = [Index("timeMs")])
data class BodyEntity(@PrimaryKey val id: String, val timeMs: Long, val kind: String, val value: Double, val source: String) {
    companion object {
        const val WEIGHT = "weight_lb"
        const val BODY_FAT = "body_fat_pct"
    }
}

@Entity(tableName = "meals", indices = [Index("timeMs")])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val photoPath: String?,
    /** Comma-separated tags (alcohol, caffeine, …). */
    val tags: String,
    /** Non-null when the meal was saved as a "usual meal". */
    val usualName: String?,
    /** Key of the saved AI result this meal came from, if any. */
    val aiResultKey: String?,
    val sample: Boolean = false,
)

@Entity(
    tableName = "meal_items",
    foreignKeys = [ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mealId")],
)
data class MealItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val name: String,
    val amount: Double,
    val unit: String,
    /** Grams; null until the user (or a USDA portion) converts the amount. */
    val grams: Double?,
    val fdcId: Long?,
    val fdcDescription: String?,
    /** Nutrients per 100 g from USDA FoodData Central. */
    val carbs100: Double?,
    val protein100: Double?,
    val fat100: Double?,
    val fiber100: Double?,
    val kcal100: Double?,
    val aiConfidence: String?,
)

/** One AI result, saved once per input hash so re-viewing never makes a new call (SPEC rule 7). */
@Entity(tableName = "ai_results")
data class AiResultEntity(@PrimaryKey val inputHash: String, val kind: String, val model: String, val json: String, val createdAt: Long)

/** Editable targets table (SPEC §5: targets live in a table, not in code). */
@Entity(tableName = "targets")
data class TargetEntity(@PrimaryKey val key: String, val value: Double)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val type: String,
    val lastSyncMs: Long,
    val lastRecordMs: Long?,
    val countLast24h: Int,
    /** Comma-separated package names of apps writing this type. */
    val origins: String,
    val error: String?,
)

data class MealWithItems(
    @androidx.room.Embedded val meal: MealEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "mealId") val items: List<MealItemEntity>,
)

@Dao
interface AppDao {
    @Upsert suspend fun upsertGlucose(rows: List<GlucoseEntity>)
    @Upsert suspend fun upsertSteps(rows: List<StepsEntity>)
    @Upsert suspend fun upsertWorkouts(rows: List<WorkoutEntity>)
    @Upsert suspend fun upsertBody(rows: List<BodyEntity>)
    @Upsert suspend fun upsertSync(row: SyncStateEntity)
    @Upsert suspend fun upsertTarget(row: TargetEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAiResult(row: AiResultEntity)

    @Query("SELECT * FROM glucose WHERE timeMs BETWEEN :from AND :to ORDER BY timeMs")
    fun glucose(from: Long, to: Long): Flow<List<GlucoseEntity>>

    @Query("SELECT * FROM steps WHERE endMs >= :from AND startMs <= :to ORDER BY startMs")
    fun steps(from: Long, to: Long): Flow<List<StepsEntity>>

    @Query("SELECT * FROM workouts WHERE endMs >= :from AND startMs <= :to ORDER BY startMs")
    fun workouts(from: Long, to: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM body WHERE kind = :kind ORDER BY timeMs DESC LIMIT 14")
    fun recentBody(kind: String): Flow<List<BodyEntity>>

    @Query("SELECT DISTINCT source FROM glucose WHERE timeMs >= :from")
    suspend fun glucoseSources(from: Long): List<String>

    @Query("SELECT * FROM sync_state")
    fun syncStates(): Flow<List<SyncStateEntity>>

    @Query("SELECT * FROM targets")
    fun targets(): Flow<List<TargetEntity>>

    @Query("SELECT * FROM ai_results WHERE inputHash = :hash")
    suspend fun aiResult(hash: String): AiResultEntity?

    @Insert suspend fun insertMeal(meal: MealEntity): Long
    @Insert suspend fun insertItems(items: List<MealItemEntity>)
    @Query("UPDATE meals SET usualName = :name WHERE id = :id") suspend fun setUsualName(id: Long, name: String?)
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteMeal(id: Long)

    @Transaction
    @Query("SELECT * FROM meals ORDER BY timeMs DESC")
    fun meals(): Flow<List<MealWithItems>>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :id")
    fun meal(id: Long): Flow<MealWithItems?>

    @Transaction
    @Query("SELECT * FROM meals WHERE usualName IS NOT NULL ORDER BY timeMs DESC")
    fun usualMeals(): Flow<List<MealWithItems>>

    @Transaction
    suspend fun saveMeal(meal: MealEntity, items: List<MealItemEntity>): Long {
        val id = insertMeal(meal)
        insertItems(items.map { it.copy(mealId = id) })
        return id
    }

    @Query("DELETE FROM glucose WHERE source = 'sample'") suspend fun clearSampleGlucose()
    @Query("DELETE FROM steps WHERE source = 'sample'") suspend fun clearSampleSteps()
    @Query("DELETE FROM workouts WHERE source = 'sample'") suspend fun clearSampleWorkouts()
    @Query("DELETE FROM body WHERE source = 'sample'") suspend fun clearSampleBody()
    @Query("DELETE FROM meals WHERE sample = 1") suspend fun clearSampleMeals()
}

@Database(
    entities = [
        GlucoseEntity::class, StepsEntity::class, WorkoutEntity::class, BodyEntity::class,
        MealEntity::class, MealItemEntity::class, AiResultEntity::class, TargetEntity::class, SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "health.db").build()
    }
}
