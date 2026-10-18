package com.javon.healthdash.calc

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One activity from a Garmin Connect "Export CSV" file. */
data class CsvActivity(val startMs: Long, val sport: Sport?, val type: String, val title: String, val distanceMi: Double?, val durationSec: Int, val avgHr: Int?, val maxHr: Int?)

/**
 * Parses the activities CSV that Garmin Connect exports (Activities → Export CSV).
 * Columns are found by header name, so column order changes don't matter. Distances are in the
 * account's units, which the caller passes in.
 */
object GarminCsv {

    fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    /** "01:19:14", "79:14" or "1:19:14.5" → seconds. */
    fun durationSec(s: String): Int? {
        val parts = s.trim().split(":").map { it.toDoubleOrNull() ?: return null }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toInt()
            2 -> (parts[0] * 60 + parts[1]).toInt()
            else -> null
        }
    }

    fun sportOf(type: String): Sport? {
        val t = type.lowercase()
        return when {
            "run" in t -> Sport.Run
            "cycl" in t || "bik" in t || "ride" in t -> Sport.Ride
            "strength" in t || "weight" in t -> Sport.Strength
            else -> null
        }
    }

    private val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "M/d/yyyy H:mm", "M/d/yyyy h:mm a").map { DateTimeFormatter.ofPattern(it) }

    fun parseDate(s: String, zone: ZoneId): Long? = formats.firstNotNullOfOrNull { f ->
        runCatching { LocalDateTime.parse(s.trim(), f).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    fun parse(text: String, zone: ZoneId, distanceInKm: Boolean): List<CsvActivity> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val header = parseLine(lines.first().removePrefix("﻿")).map { it.lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val iType = col("activity type") ?: return emptyList()
        val iDate = col("date") ?: return emptyList()
        val iTitle = col("title")
        val iDist = col("distance")
        val iTime = col("time", "elapsed time", "moving time") ?: return emptyList()
        val iAvgHr = col("avg hr")
        val iMaxHr = col("max hr")
        return lines.drop(1).mapNotNull { line ->
            val c = parseLine(line)
            val start = c.getOrNull(iDate)?.let { parseDate(it, zone) } ?: return@mapNotNull null
            val dur = c.getOrNull(iTime)?.let { durationSec(it) } ?: return@mapNotNull null
            val dist = iDist?.let { c.getOrNull(it)?.replace(",", "")?.toDoubleOrNull() }?.let { if (distanceInKm) it / 1.609344 else it }
            CsvActivity(
                startMs = start,
                sport = sportOf(c[iType]),
                type = c[iType],
                title = iTitle?.let { c.getOrNull(it) } ?: c[iType],
                distanceMi = dist?.takeIf { it > 0 },
                durationSec = dur,
                avgHr = iAvgHr?.let { c.getOrNull(it)?.toIntOrNull() },
                maxHr = iMaxHr?.let { c.getOrNull(it)?.toIntOrNull() },
            )
        }
    }
}
