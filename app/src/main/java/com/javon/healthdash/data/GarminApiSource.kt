package com.javon.healthdash.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.javon.healthdash.calc.ApiActivity
import com.javon.healthdash.calc.GarminApi
import com.javon.healthdash.calc.HOUR_MS
import com.javon.healthdash.calc.MINUTE_MS
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.calc.WorkoutDedup
import com.javon.healthdash.data.db.AppDao
import com.javon.healthdash.data.db.SyncStateEntity
import com.javon.healthdash.data.db.WorkoutEntity
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GarminApiException(message: String, val code: Int? = null) : IOException(message)

/**
 * Optional Garmin Connect personal API connection (pull-only, read-only). Fetches runs, rides and
 * other activities into the workouts table with source [GarminApi.SOURCE]. Does nothing without a key,
 * so Health Connect stays the only source unless the user adds one. See docs/garmin-personal-api.md.
 */
class GarminApiSource(private val keys: SecureKeys, private val dao: AppDao) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val base = "https://personal.connectapi.garmin.com"

    fun hasKey() = keys.has(SecureKeys.GARMIN)

    /**
     * Pulls the last 90 days on the first run, then from a few days before the newest activity.
     * Background syncs run at most every [MIN_INTERVAL_MS]; [force] skips that wait (Sync now).
     */
    suspend fun sync(force: Boolean = false, zone: ZoneId = ZoneId.systemDefault()) {
        val key = keys.get(SecureKeys.GARMIN) ?: return
        val prev = dao.syncState(TYPE)
        val now = System.currentTimeMillis()
        if (!force && prev != null && now - prev.lastSyncMs < MIN_INTERVAL_MS) return
        try {
            val (from, to) = GarminApi.syncRange(LocalDate.now(zone), prev?.lastRecordMs, zone)
            val acts = fetchAll(key, from, to, zone)
            dao.upsertWorkouts(acts.map { it.toEntity() })
            val newest = listOfNotNull(prev?.lastRecordMs, acts.maxOfOrNull { it.startMs }).maxOrNull()
            dao.upsertSync(SyncStateEntity(TYPE, now, newest, acts.count { it.startMs >= now - 24 * HOUR_MS }, ORIGIN, null))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Keep the newest-record mark so the next sync stays incremental.
            dao.upsertSync(SyncStateEntity(TYPE, now, prev?.lastRecordMs, 0, ORIGIN, e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    /** Forget the key and its token. Already-synced workouts stay. */
    suspend fun disconnect() {
        keys.put(SecureKeys.GARMIN, "")
        keys.put(SecureKeys.GARMIN_TOKEN, "")
        dao.syncState(TYPE)?.let { dao.upsertSync(it.copy(error = "Disconnected")) }
    }

    private suspend fun fetchAll(key: String, from: LocalDate, to: LocalDate, zone: ZoneId): List<ApiActivity> {
        val out = mutableListOf<ApiActivity>()
        var start = 0
        repeat(MAX_PAGES) {
            val url = "$base/activitylist-service/activities/search/activities".toHttpUrl().newBuilder()
                .addQueryParameter("start", start.toString())
                .addQueryParameter("limit", PAGE.toString())
                .addQueryParameter("startDate", from.toString())
                .addQueryParameter("endDate", to.toString())
                .build()
            val body = authedGet(key, url)
            val page = GarminApi.parseActivities(body, zone)
            out += page
            if (GarminApi.entryCount(body) < PAGE) return out.distinctBy { it.id }
            start += PAGE
        }
        return out.distinctBy { it.id }
    }

    /** GET with a cached access token; on 401 gets a fresh token once and retries. */
    private suspend fun authedGet(key: String, url: okhttp3.HttpUrl): String {
        val first = runCatching { get(url, token(key, refresh = false)) }
        val err = first.exceptionOrNull()
        if (err is GarminApiException && err.code == 401) return get(url, token(key, refresh = true))
        return first.getOrThrow()
    }

    private suspend fun get(url: okhttp3.HttpUrl, token: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw GarminApiException(errorText(resp.code), resp.code)
            resp.body.string()
        }
    }

    /** Access tokens last up to 24 h; reuse the cached one while it has over an hour left. */
    private suspend fun token(key: String, refresh: Boolean): String {
        if (!refresh) {
            keys.get(SecureKeys.GARMIN_TOKEN)?.let { cached ->
                val exp = GarminApi.jwtExpiryMs(cached)
                if (exp != null && exp - System.currentTimeMillis() > HOUR_MS) return cached
            }
        }
        val fresh = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$base$TOKEN_PATH")
                .header("Authorization", "ApiKey $key").header("Accept", "*/*")
                .post(ByteArray(0).toRequestBody())
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw GarminApiException(
                        if (resp.code == 401 || resp.code == 403) "Garmin rejected the key — it may have expired or been deleted. Create a new one." else errorText(resp.code),
                        resp.code,
                    )
                }
                resp.body.string().trim()
            }
        }
        if (fresh.isEmpty()) throw GarminApiException("Garmin returned an empty token")
        keys.put(SecureKeys.GARMIN_TOKEN, fresh)
        return fresh
    }

    private fun errorText(code: Int) = when (code) {
        401, 403 -> "Garmin rejected the request (sign-in expired)"
        429 -> "Garmin is limiting requests — will try again later"
        else -> "Garmin request failed ($code)"
    }

    companion object {
        /** sync_state row for this source (not a Health Connect [DataType]). */
        const val TYPE = "GarminApi"
        const val ORIGIN = "connect.garmin.com"
        private const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        private const val PAGE = 100
        private const val MAX_PAGES = 20
        const val MIN_INTERVAL_MS = 30 * MINUTE_MS

        /** Drops Health Connect / CSV copies of activities the API also returned. */
        fun dedup(rows: List<WorkoutEntity>): List<WorkoutEntity> =
            WorkoutDedup.preferApi(rows, { it.source == GarminApi.SOURCE }, { it.startMs }, { sportOf(it.exerciseType) })
    }
}

fun ApiActivity.toEntity() = WorkoutEntity(
    id = "${GarminApi.SOURCE}-$id",
    startMs = startMs,
    endMs = startMs + durationSec * 1000L,
    exerciseType = when (sport) {
        Sport.Run -> if (indoor) ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL else ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        Sport.Ride -> if (indoor) ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY else ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        Sport.Strength -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        null -> if ("walk" in typeKey) ExerciseSessionRecord.EXERCISE_TYPE_WALKING else ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    },
    title = title,
    source = GarminApi.SOURCE,
    avgHr = avgHr,
    maxHr = maxHr,
    distanceM = distanceM,
)
