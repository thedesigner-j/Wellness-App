package com.javon.healthdash.calc

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// Hourly air temperature from Open-Meteo (free, no key) for heat adjustment. Only city coordinates are sent.

object Weather {
    /** Las Vegas. */
    const val LAT = 36.17
    const val LON = -115.14
    const val TIMEZONE = "America/Los_Angeles"

    /** Past 92 days + next 8 days of hourly °F. */
    fun url() = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&hourly=temperature_2m&temperature_unit=fahrenheit&past_days=92&forecast_days=8&timezone=$TIMEZONE"

    private val json = Json { ignoreUnknownKeys = true }

    /** `hourly.time` (local "yyyy-MM-ddTHH:mm") + `hourly.temperature_2m` → (epoch ms, °F). */
    fun parse(body: String, zone: ZoneId = ZoneId.of(TIMEZONE)): List<Pair<Long, Double>> {
        val hourly = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("hourly") as? JsonObject ?: return emptyList()
        val times = (hourly["time"] as? JsonArray) ?: return emptyList()
        val temps = (hourly["temperature_2m"] as? JsonArray) ?: return emptyList()
        return times.indices.mapNotNull { i ->
            val t = (times[i] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val f = (temps.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            runCatching { LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() to f }.getOrNull()
        }
    }

    /** Average °F over [startMs, endMs] (hours touching the run); nearest hour within 2 h when none overlap. */
    fun average(hourly: List<Pair<Long, Double>>, startMs: Long, endMs: Long): Double? {
        val hour = 3_600_000L
        val inRun = hourly.filter { it.first + hour > startMs && it.first <= endMs }
        if (inRun.isNotEmpty()) return inRun.map { it.second }.average()
        return hourly.minByOrNull { kotlin.math.abs(it.first - startMs) }?.takeIf { kotlin.math.abs(it.first - startMs) <= 2 * hour }?.second
    }

    /** Forecast at the usual run time on [date]. */
    fun at(hourly: List<Pair<Long, Double>>, date: LocalDate, time: LocalTime, zone: ZoneId): Double? {
        val ms = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return average(hourly, ms, ms + 3_600_000L)
    }
}
