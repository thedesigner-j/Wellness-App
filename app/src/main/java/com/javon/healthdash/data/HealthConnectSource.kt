package com.javon.healthdash.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.javon.healthdash.data.db.AppDao
import com.javon.healthdash.data.db.BodyEntity
import com.javon.healthdash.data.db.GlucoseEntity
import com.javon.healthdash.data.db.ReadingEntity
import com.javon.healthdash.data.db.SleepEntity
import com.javon.healthdash.data.db.StepsEntity
import com.javon.healthdash.data.db.SyncStateEntity
import com.javon.healthdash.data.db.WorkoutEntity
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/** Health Connect exercise type → plan sport (null = other activities, e.g. walking). */
fun sportOf(exerciseType: Int): com.javon.healthdash.calc.Sport? = when (exerciseType) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> com.javon.healthdash.calc.Sport.Run
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> com.javon.healthdash.calc.Sport.Ride
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> com.javon.healthdash.calc.Sport.Strength
    else -> null
}

/** The data types shown on the Data sources screen. */
enum class DataType(val label: String) {
    Glucose("Blood glucose"), Steps("Steps"), Workouts("Workouts"), Sleep("Sleep"), Hrv("Heart rate variability"),
    RestingHr("Resting heart rate"), Vo2Max("VO2 max"), Weight("Weight"), BodyFat("Body fat"),
}

/**
 * Read-only access to Health Connect. Each sync re-reads the last few days so late-arriving CGM
 * data (official apps can lag ~3 h) is merged; meal responses are computed from the database on
 * every view, so they update automatically when that data lands.
 */
class HealthConnectSource(private val context: Context, private val dao: AppDao) {

    val sdkStatus: Int get() = HealthConnectClient.getSdkStatus(context)
    val available: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        "android.permission.health.READ_HEALTH_DATA_HISTORY",
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
    )

    /** The Phase 1 types the dashboard needs. */
    val corePermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    suspend fun granted(): Set<String> = if (available) runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet()) else emptySet()

    private suspend fun <T : Record> readAll(type: KClass<T>, start: Instant, end: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageSize = 5000, pageToken = token))
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /**
     * Syncs [days] back from now. The first sync reads 90 days (needs the history permission;
     * Health Connect otherwise returns the last 30 days only).
     */
    suspend fun sync(days: Long = 3) {
        if (!available) return
        val now = Instant.now()
        val start = now.minus(Duration.ofDays(days))
        val dayAgo = now.minus(Duration.ofDays(1)).toEpochMilli()

        suspend fun <T> step(type: DataType, block: suspend () -> Triple<Int, Long?, Set<String>>) {
            val state = runCatching { block() }
            dao.upsertSync(
                state.fold(
                    onSuccess = { (count, last, origins) -> SyncStateEntity(type.name, now.toEpochMilli(), last, count, origins.joinToString(","), null) },
                    onFailure = { e ->
                        val msg = if (e is SecurityException) "Permission not granted" else (e.message ?: e.javaClass.simpleName)
                        SyncStateEntity(type.name, now.toEpochMilli(), null, 0, "", msg)
                    },
                ),
            )
        }

        step<BloodGlucoseRecord>(DataType.Glucose) {
            val rows = readAll(BloodGlucoseRecord::class, start, now)
            dao.upsertGlucose(rows.map { GlucoseEntity(it.metadata.id, it.time.toEpochMilli(), it.level.inMilligramsPerDeciliter, it.metadata.dataOrigin.packageName) })
            Triple(rows.count { it.time.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.time.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
        step<StepsRecord>(DataType.Steps) {
            val rows = readAll(StepsRecord::class, start, now)
            dao.upsertSteps(rows.map { StepsEntity(it.metadata.id, it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.count, it.metadata.dataOrigin.packageName) })
            Triple(rows.count { it.endTime.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.endTime.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
        step<ExerciseSessionRecord>(DataType.Workouts) {
            val rows = readAll(ExerciseSessionRecord::class, start, now)
            dao.upsertWorkouts(
                rows.map {
                    // Average / max HR during the session, for zones and the workout detail screen.
                    val hr = runCatching {
                        client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX), TimeRangeFilter.between(it.startTime, it.endTime)))
                    }.getOrNull()
                    WorkoutEntity(
                        it.metadata.id, it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.exerciseType, it.title, it.metadata.dataOrigin.packageName,
                        avgHr = hr?.get(HeartRateRecord.BPM_AVG)?.toDouble(), maxHr = hr?.get(HeartRateRecord.BPM_MAX)?.toDouble(),
                    )
                },
            )
            Triple(rows.count { it.endTime.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.endTime.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
        step<SleepSessionRecord>(DataType.Sleep) {
            val rows = readAll(SleepSessionRecord::class, start.minus(Duration.ofHours(12)), now)
            dao.upsertSleep(
                rows.map { r ->
                    val deep = r.stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP }
                        .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
                    SleepEntity(r.metadata.id, r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), deep.takeIf { r.stages.isNotEmpty() }, r.metadata.dataOrigin.packageName)
                },
            )
            Triple(rows.count { it.endTime.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.endTime.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
        step<HeartRateVariabilityRmssdRecord>(DataType.Hrv) {
            val rows = readAll(HeartRateVariabilityRmssdRecord::class, start, now)
            dao.upsertReadings(rows.map { ReadingEntity(it.metadata.id, it.time.toEpochMilli(), ReadingEntity.HRV, it.heartRateVariabilityMillis, it.metadata.dataOrigin.packageName) })
            Triple(rows.count { it.time.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.time.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
        step<RestingHeartRateRecord>(DataType.RestingHr) {
            val rows = readAll(RestingHeartRateRecord::class, start, now)
            dao.upsertReadings(rows.map { ReadingEntity(it.metadata.id, it.time.toEpochMilli(), ReadingEntity.RESTING_HR, it.beatsPerMinute.toDouble(), it.metadata.dataOrigin.packageName) })
            Triple(rows.count { it.time.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.time.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
        step<Vo2MaxRecord>(DataType.Vo2Max) {
            val rows = readAll(Vo2MaxRecord::class, now.minus(Duration.ofDays(maxOf(days, 30))), now)
            dao.upsertReadings(rows.map { ReadingEntity(it.metadata.id, it.time.toEpochMilli(), ReadingEntity.VO2_MAX, it.vo2MillilitersPerMinuteKilogram, it.metadata.dataOrigin.packageName) })
            Triple(rows.count { it.time.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.time.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
        step<WeightRecord>(DataType.Weight) {
            val rows = readAll(WeightRecord::class, now.minus(Duration.ofDays(maxOf(days, 14))), now)
            dao.upsertBody(rows.map { BodyEntity(it.metadata.id, it.time.toEpochMilli(), BodyEntity.WEIGHT, it.weight.inPounds, it.metadata.dataOrigin.packageName) })
            Triple(rows.count { it.time.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.time.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
        step<BodyFatRecord>(DataType.BodyFat) {
            val rows = readAll(BodyFatRecord::class, now.minus(Duration.ofDays(maxOf(days, 14))), now)
            dao.upsertBody(rows.map { BodyEntity(it.metadata.id, it.time.toEpochMilli(), BodyEntity.BODY_FAT, it.percentage.value, it.metadata.dataOrigin.packageName) })
            Triple(rows.count { it.time.toEpochMilli() >= dayAgo }, rows.maxOfOrNull { it.time.toEpochMilli() }, rows.map { it.metadata.dataOrigin.packageName }.toSet())
        }
    }
}
