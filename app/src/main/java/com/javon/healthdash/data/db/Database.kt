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
    /** Average / max heart rate during the workout (Health Connect aggregate). */
    val avgHr: Double? = null,
    val maxHr: Double? = null,
    val distanceM: Double? = null,
)

/** A race plan (SPEC §8). Plans chain in sequence by race date. */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceType: String,
    val raceDate: Long,
    val startDate: Long,
    val goalMin: Int?,
    /** Days, long day and strength days as DayOfWeek values, comma-separated. */
    val days: String,
    val longDay: Int,
    val strengthDays: String,
    val weeklyMin: Int,
    val createdAt: Long,
)

/** One planned session. Dates are epoch days. */
@Entity(
    tableName = "planned",
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId"), Index("date")],
)
data class PlannedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val date: Long,
    val sport: String,
    val kind: String,
    val durationMin: Int,
    val note: String,
    val phase: String,
    val week: Int,
    /** Planned, Dropped, Moved, Swapped, Missed. */
    val status: String = "Planned",
)

/** Log of rule-based plan changes, with what is needed to undo a swap. */
@Entity(tableName = "adjustments", indices = [Index("sessionId")])
data class AdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMs: Long,
    val kind: String,
    val summary: String,
    val reason: String,
    val originalKind: String?,
    val originalDurationMin: Int?,
    val originalNote: String?,
    val reverted: Boolean = false,
)

/** Fuel taken during a workout (gels, drinks…), in grams of carbs. */
@Entity(tableName = "fuel", indices = [Index("workoutId")])
data class FuelEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val workoutId: String, val timeMs: Long, val label: String, val carbsG: Double)

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

@Entity(tableName = "sleep", indices = [Index("endMs")])
data class SleepEntity(@PrimaryKey val id: String, val startMs: Long, val endMs: Long, val deepMin: Int?, val source: String)

/** Point readings from Health Connect: HRV (ms), resting HR (bpm), VO2 max (ml/kg/min). */
@Entity(tableName = "readings", indices = [Index("kind", "timeMs")])
data class ReadingEntity(@PrimaryKey val id: String, val timeMs: Long, val kind: String, val value: Double, val source: String) {
    companion object {
        const val HRV = "hrv_ms"
        const val RESTING_HR = "resting_hr"
        const val VO2_MAX = "vo2_max"
    }
}

/** Manually entered lab results (SPEC §4). */
@Entity(tableName = "labs", indices = [Index("timeMs")])
data class LabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val kind: String,
    val value: Double,
    val lab: String?,
    val sample: Boolean = false,
) {
    companion object {
        const val A1C = "a1c"
        const val FASTING = "fasting_glucose"
    }
}

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

    @Upsert suspend fun upsertSleep(rows: List<SleepEntity>)
    @Upsert suspend fun upsertReadings(rows: List<ReadingEntity>)
    @Insert suspend fun insertLab(row: LabEntity): Long
    @Query("DELETE FROM labs WHERE id = :id") suspend fun deleteLab(id: Long)

    @Query("SELECT * FROM sleep WHERE endMs >= :from ORDER BY startMs")
    fun sleep(from: Long): Flow<List<SleepEntity>>

    @Query("SELECT * FROM readings WHERE timeMs >= :from ORDER BY timeMs")
    fun readings(from: Long): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM labs ORDER BY timeMs DESC")
    fun labs(): Flow<List<LabEntity>>

    @Query("DELETE FROM sleep WHERE source = 'sample'") suspend fun clearSampleSleep()
    @Query("DELETE FROM readings WHERE source = 'sample'") suspend fun clearSampleReadings()
    @Query("DELETE FROM labs WHERE sample = 1") suspend fun clearSampleLabs()

    @Insert suspend fun insertPlan(p: PlanEntity): Long
    @Insert suspend fun insertPlanned(rows: List<PlannedEntity>)
    @androidx.room.Update suspend fun updatePlanned(row: PlannedEntity)
    @Query("DELETE FROM plans WHERE id = :id") suspend fun deletePlan(id: Long)
    @Insert suspend fun insertAdjustment(a: AdjustmentEntity): Long
    @androidx.room.Update suspend fun updateAdjustment(a: AdjustmentEntity)
    @Insert suspend fun insertFuel(f: FuelEntity): Long
    @Query("DELETE FROM fuel WHERE id = :id") suspend fun deleteFuel(id: Long)

    @Query("SELECT * FROM plans ORDER BY raceDate") fun plans(): Flow<List<PlanEntity>>
    @Query("SELECT * FROM planned ORDER BY date, id") fun planned(): Flow<List<PlannedEntity>>
    @Query("SELECT * FROM planned ORDER BY date, id") suspend fun plannedNow(): List<PlannedEntity>
    @Query("SELECT * FROM adjustments ORDER BY timeMs DESC") fun adjustments(): Flow<List<AdjustmentEntity>>
    @Query("SELECT * FROM adjustments") suspend fun adjustmentsNow(): List<AdjustmentEntity>
    @Query("SELECT * FROM fuel ORDER BY timeMs") fun fuel(): Flow<List<FuelEntity>>
    @Query("SELECT * FROM workouts WHERE startMs >= :from ORDER BY startMs") suspend fun workoutsNow(from: Long): List<WorkoutEntity>
}

@Database(
    entities = [
        GlucoseEntity::class, StepsEntity::class, WorkoutEntity::class, BodyEntity::class,
        MealEntity::class, MealItemEntity::class, AiResultEntity::class, TargetEntity::class, SyncStateEntity::class,
        SleepEntity::class, ReadingEntity::class, LabEntity::class,
        PlanEntity::class, PlannedEntity::class, AdjustmentEntity::class, FuelEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // Each version only adds tables or nullable columns, so Room migrates automatically and keeps data.
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2), androidx.room.AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "health.db").build()
    }
}
