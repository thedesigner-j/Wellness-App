package com.javon.healthdash.calc

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One activity from the Garmin Connect personal API activity list. Distance in meters. */
data class ApiActivity(
    val id: Long,
    val startMs: Long,
    val durationSec: Int,
    val sport: Sport?,
    val typeKey: String,
    val title: String?,
    val distanceM: Double?,
    val avgHr: Double?,
    val maxHr: Double?,
) {
    /** Treadmill, trainer and virtual sessions (no GPS). */
    val indoor: Boolean get() = GarminApi.isIndoor(typeKey)
}

/**
 * Parsing and sync-window rules for the Garmin Connect personal API
 * (`GET /activitylist-service/activities/search/activities`). See docs/garmin-personal-api.md.
 */
object GarminApi {
    /** `WorkoutEntity.source` for rows from the personal API. */
    const val SOURCE = "garmin-api"
    const val HISTORY_DAYS = 90L
    /** Re-read this many days before the newest activity, to pick up edits and late uploads. */
    const val OVERLAP_DAYS = 3L

    private val json = Json { ignoreUnknownKeys = true }

    /** Garmin type keys ("trail_running", "indoor_cycling", "strength_training") → plan sport. */
    fun sportOf(typeKey: String): Sport? = GarminCsv.sportOf(typeKey.replace('_', ' '))

    fun isIndoor(typeKey: String): Boolean = typeKey.lowercase().let { "treadmill" in it || "indoor" in it || "virtual" in it }

    /**
     * Activities in a response body. Accepts a bare JSON array or an object wrapping one
     * (e.g. `{"activityList": [...]}`). Entries without an id, start time or duration are skipped.
     * [zone] is only used when the response has local start times and no UTC ones.
     */
    fun parseActivities(body: String, zone: ZoneId): List<ApiActivity> {
        val list = entries(body) ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.let { o -> parseActivity(o, zone) } }
    }

    /** Raw entries in a page, parsed or not — a page shorter than the requested limit is the last one. */
    fun entryCount(body: String): Int = entries(body)?.size ?: 0

    private fun entries(body: String): JsonArray? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        return when (root) {
            is JsonArray -> root
            is JsonObject -> root.values.filterIsInstance<JsonArray>().firstOrNull { arr -> arr.any { (it as? JsonObject)?.containsKey("activityId") == true } }
            else -> null
        }
    }

    fun parseActivity(o: JsonObject, zone: ZoneId): ApiActivity? {
        val id = o.long("activityId") ?: return null
        val start = o.long("beginTimestamp")
            ?: o.string("startTimeGMT")?.let { parseTime(it, ZoneOffset.UTC) }
            ?: o.string("startTimeLocal")?.let { parseTime(it, zone) }
            ?: return null
        val duration = listOf("duration", "elapsedDuration", "movingDuration").firstNotNullOfOrNull { o.double(it)?.takeIf { d -> d > 0 } } ?: return null
        val typeKey = (o["activityType"] as? JsonObject)?.string("typeKey") ?: o.string("activityType") ?: ""
        return ApiActivity(
            id = id,
            startMs = start,
            durationSec = duration.toInt(),
            sport = sportOf(typeKey),
            typeKey = typeKey,
            title = o.string("activityName"),
            distanceM = o.double("distance")?.takeIf { it > 0 },
            avgHr = (o.double("averageHR") ?: o.double("averageHeartRate"))?.takeIf { it > 0 },
            maxHr = (o.double("maxHR") ?: o.double("maxHeartRate"))?.takeIf { it > 0 },
        )
    }

    private val timeFormats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm").map { DateTimeFormatter.ofPattern(it) }

    /** "2026-10-25 13:02:11", "2026-10-25T13:02:11.0" → epoch ms in [zone]. */
    fun parseTime(s: String, zone: ZoneId): Long? {
        val t = s.trim().replace('T', ' ').substringBefore('.')
        return timeFormats.firstNotNullOfOrNull { f -> runCatching { LocalDateTime.parse(t, f).atZone(zone).toInstant().toEpochMilli() }.getOrNull() }
    }

    /**
     * Inclusive date range to request: the full [HISTORY_DAYS] on the first sync, then from
     * [OVERLAP_DAYS] before the newest activity already stored (never more than [HISTORY_DAYS] back).
     */
    fun syncRange(today: LocalDate, newestStoredMs: Long?, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val earliest = today.minusDays(HISTORY_DAYS)
        val from = newestStoredMs?.let { localDateOf(it, zone).minusDays(OVERLAP_DAYS) }?.coerceAtLeast(earliest) ?: earliest
        return from.coerceAtMost(today) to today
    }

    /** The `exp` claim of a JWT access token, in epoch ms (signature not checked), or null. */
    fun jwtExpiryMs(token: String): Long? = runCatching {
        val payload = token.trim().split(".")[1]
        val bytes = java.util.Base64.getUrlDecoder().decode(payload.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })
        val o = json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as JsonObject
        (o["exp"] as JsonPrimitive).doubleOrNull!!.times(1000).toLong()
    }.getOrNull()

    private fun JsonObject.prim(k: String) = this[k] as? JsonPrimitive
    private fun JsonObject.long(k: String) = prim(k)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.double(k: String) = prim(k)?.doubleOrNull
    private fun JsonObject.string(k: String) = prim(k)?.takeIf { it.isString }?.contentOrNull
}

/**
 * The same activity can arrive twice: from the personal API and through Health Connect (or a CSV
 * import). Rows count as the same activity when they share a sport and start within [toleranceMs];
 * the API row wins because it has Garmin's own distance and HR.
 */
object WorkoutDedup {
    const val TOLERANCE_MS = 2 * MINUTE_MS

    fun <T> preferApi(
        rows: List<T>,
        isApi: (T) -> Boolean,
        startMs: (T) -> Long,
        sport: (T) -> Sport?,
        toleranceMs: Long = TOLERANCE_MS,
    ): List<T> {
        val api = rows.filter(isApi)
        if (api.isEmpty()) return rows
        return rows.filter { r ->
            isApi(r) || api.none { a -> sport(a) == sport(r) && kotlin.math.abs(startMs(a) - startMs(r)) <= toleranceMs }
        }
    }
}
