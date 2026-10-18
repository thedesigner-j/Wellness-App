package com.javon.healthdash.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object Fmt {
    private val zone get() = ZoneId.systemDefault()
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayLong = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
    private val dayShort = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val hourTick = DateTimeFormatter.ofPattern("ha", Locale.US)

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(time)
    fun dayLong(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dayLong)
    fun dayShort(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dayShort)
    fun hourTick(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(hourTick).lowercase().removeSuffix("m")

    fun dayLabel(ms: Long, nowMs: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> d.format(dayShort)
        }
    }

    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun ago(ms: Long, nowMs: Long): String {
        val min = ((nowMs - ms) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} d ago"
        }
    }

    fun int(v: Double): String = "%,d".format(Locale.US, v.roundToLong())
    fun int(v: Long): String = "%,d".format(Locale.US, v)
    fun one(v: Double): String = "%.1f".format(Locale.US, v)
    fun two(v: Double): String = "%.2f".format(Locale.US, v)
    fun signed(v: Double): String = (if (v >= 0) "+" else "−") + abs(v).roundToInt()
    fun amount(v: Double): String = if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
    fun grams(v: Double): String = "${v.roundToInt()} g"

    /** Readable names for Health Connect source packages. */
    fun app(pkg: String): String = when {
        pkg == "sample" -> "Sample data"
        pkg.contains("garmin") -> "Garmin Connect"
        pkg.contains("dexcom") -> "Dexcom"
        pkg.contains("xdrip", ignoreCase = true) || pkg.contains("eveningoutpost") -> "xDrip+"
        pkg.contains("juggluco", ignoreCase = true) -> "Juggluco"
        pkg.contains("libre", ignoreCase = true) || pkg.contains("abbott") -> "LibreLink"
        pkg.contains("renpho", ignoreCase = true) -> "Renpho"
        pkg.contains("fitindex", ignoreCase = true) -> "Fitindex"
        pkg.contains("shealth") || pkg.contains("samsung") -> "Samsung Health"
        pkg.contains("google") -> "Google Fit"
        else -> pkg.substringAfterLast('.')
    }
}
