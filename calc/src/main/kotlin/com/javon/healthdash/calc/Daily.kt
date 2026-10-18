package com.javon.healthdash.calc

import java.time.LocalDate
import java.time.ZoneId

/** A sleep session from the watch. */
data class SleepSession(val window: TimeWindow, val deepMin: Int? = null)

/** A timestamped value (HRV ms, resting HR bpm, …). */
data class Reading(val timeMs: Long, val value: Double)

enum class SleepQuality { Good, Poor }

/** Split filter for Trends (SPEC §6). */
enum class SplitFilter { All, Everyday, Training, Overnight }

enum class SleepFilter { Any, Good, Poor }

fun LocalDate.window(zone: ZoneId) =
    TimeWindow(atStartOfDay(zone).toInstant().toEpochMilli(), plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

fun localDateOf(timeMs: Long, zone: ZoneId): LocalDate = java.time.Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()

object Daily {

    /** Glucose summary for one local day (coverage-checked). */
    fun day(date: LocalDate, points: List<GlucosePoint>, zone: ZoneId, targets: Targets = Targets()) =
        GlucoseStats.summarize(points, date.window(zone), targets)

    /**
     * Rolling GMI: for each date, GMI over the [windowDays] days ending that date.
     * Used for the 90-day GMI line on A1c Progress.
     */
    fun rollingGmi(dates: List<LocalDate>, points: List<GlucosePoint>, zone: ZoneId, windowDays: Int = 14, targets: Targets = Targets()): List<Pair<LocalDate, Metric<Double>>> =
        dates.map { d ->
            val w = TimeWindow(d.minusDays(windowDays - 1L).window(zone).startMs, d.window(zone).endMs)
            when (val s = GlucoseStats.summarize(points, w, targets)) {
                is Metric.Value -> d to Metric.Value(s.value.gmi, s.coverage)
                is Metric.NotEnoughData -> d to s
            }
        }

    /**
     * Summary over only the moments where [include] is true. Coverage is measured against the
     * CGM slots that themselves satisfy [include], so a filter (e.g. Training only) is judged on
     * its own time, not on the whole period.
     */
    fun summarizeWhere(points: List<GlucosePoint>, window: TimeWindow, targets: Targets = Targets(), include: (Long) -> Boolean): Metric<GlucoseSummary> {
        val slotMs = targets.cgmIntervalMin * MINUTE_MS
        var expected = 0
        val wanted = HashSet<Long>()
        var t = window.startMs
        while (t < window.endMs) {
            if (include(t)) { expected++; wanted += (t - window.startMs) / slotMs }
            t += slotMs
        }
        val kept = points.filter { it.timeMs in window && include(it.timeMs) }
        val filled = kept.map { (it.timeMs - window.startMs) / slotMs }.filter { it in wanted }.toSet().size
        val cov = if (expected == 0) 0.0 else filled.toDouble() / expected
        if (kept.isEmpty() || cov < targets.minCoverage) return Metric.NotEnoughData(cov)
        return Metric.Value(GlucoseStats.summarizeReadings(kept.map { it.mgdl }, targets), cov)
    }
}

object Fasting {
    /**
     * Fasting glucose for one day: mean of readings in the early-morning window, ending early if a
     * meal is logged inside it. Not enough data if the remaining window is under 30 min or its
     * coverage is below the minimum.
     */
    fun forDay(date: LocalDate, zone: ZoneId, points: List<GlucosePoint>, meals: List<Long>, targets: Targets = Targets()): Metric<Double> {
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val start = midnight + targets.fastingStartMin * MINUTE_MS
        var end = midnight + targets.fastingEndMin * MINUTE_MS
        meals.filter { it in start until end }.minOrNull()?.let { end = it }
        if (end - start < 30 * MINUTE_MS) return Metric.NotEnoughData(0.0)
        val w = TimeWindow(start, end)
        val inW = points.filter { it.timeMs in w }
        val cov = GlucoseStats.coverage(inW, w, targets.cgmIntervalMin)
        if (inW.isEmpty() || cov < targets.minCoverage) return Metric.NotEnoughData(cov)
        return Metric.Value(inW.sumOf { it.mgdl } / inW.size, cov)
    }
}

data class LabComparison(val lab: Double, val gmi: Double, val diff: Double, val differsNotably: Boolean)

object Labs {
    /**
     * Compares a lab A1c with GMI over the 90 days before the lab draw (SPEC §6: note if they
     * differ by more than 0.5).
     */
    fun compare(labTimeMs: Long, labA1c: Double, points: List<GlucosePoint>, targets: Targets = Targets(), threshold: Double = 0.5): Metric<LabComparison> {
        val w = TimeWindow(labTimeMs - 90 * DAY_MS, labTimeMs)
        return when (val s = GlucoseStats.summarize(points, w, targets)) {
            is Metric.NotEnoughData -> s
            is Metric.Value -> {
                val diff = labA1c - s.value.gmi
                Metric.Value(LabComparison(labA1c, s.value.gmi, diff, kotlin.math.abs(diff) > threshold), s.coverage)
            }
        }
    }
}

object SleepContext {

    /** Sessions belonging to the night before [date]: they end between noon the day before and noon on [date]. */
    fun previousNight(date: LocalDate, zone: ZoneId, sessions: List<SleepSession>): List<SleepSession> {
        val noon = date.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * HOUR_MS
        return sessions.filter { it.window.endMs in (noon - DAY_MS) until noon }
    }

    fun totalMinutes(sessions: List<SleepSession>): Int = (sessions.sumOf { it.window.durationMs } / MINUTE_MS).toInt()

    fun quality(date: LocalDate, zone: ZoneId, sessions: List<SleepSession>, targets: Targets = Targets()): SleepQuality? {
        val night = previousNight(date, zone, sessions)
        if (night.isEmpty()) return null
        return if (totalMinutes(night) >= targets.goodSleepMin) SleepQuality.Good else SleepQuality.Poor
    }

    /**
     * Windows that count as Overnight: real sleep sessions, plus the default window for any night
     * in [from]..[to] that has no recorded sleep.
     */
    fun overnightWindows(from: LocalDate, to: LocalDate, zone: ZoneId, sessions: List<SleepSession>, targets: Targets = Targets()): List<TimeWindow> {
        val out = sessions.map { it.window }.toMutableList()
        var d = from
        while (!d.isAfter(to)) {
            if (previousNight(d, zone, sessions).isEmpty()) {
                val midnight = d.atStartOfDay(zone).toInstant().toEpochMilli()
                out += TimeWindow(midnight - DAY_MS + targets.overnightStartMin * MINUTE_MS, midnight + targets.overnightEndMin * MINUTE_MS)
            }
            d = d.plusDays(1)
        }
        return out
    }

    /** A morning value (HRV, resting HR) for [date]: mean of readings from midnight to noon. */
    fun morningValue(date: LocalDate, zone: ZoneId, readings: List<Reading>): Double? {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val r = readings.filter { it.timeMs in start until start + 12 * HOUR_MS }
        return r.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
    }

    /** Personal baseline: mean of the morning values over the [days] before [date]; needs at least 7 days. */
    fun baseline(date: LocalDate, zone: ZoneId, readings: List<Reading>, days: Int = 28): Double? {
        val values = (1..days).mapNotNull { morningValue(date.minusDays(it.toLong()), zone, readings) }
        return if (values.size >= 7) values.average() else null
    }
}

object Trends {
    /** Inclusion test for the Trends filters (split × sleep quality of the night before). */
    fun include(
        split: SplitFilter,
        sleep: SleepFilter,
        zone: ZoneId,
        workouts: List<TimeWindow>,
        overnight: List<TimeWindow>,
        sessions: List<SleepSession>,
        targets: Targets = Targets(),
    ): (Long) -> Boolean {
        val qualityCache = HashMap<LocalDate, SleepQuality?>()
        return { t ->
            val okSplit = split == SplitFilter.All || when (ContextSplit.classify(t, workouts, overnight, targets)) {
                GlucoseContext.Training -> split == SplitFilter.Training
                GlucoseContext.Overnight -> split == SplitFilter.Overnight
                GlucoseContext.Everyday -> split == SplitFilter.Everyday
            }
            okSplit && (sleep == SleepFilter.Any || run {
                val q = qualityCache.getOrPut(localDateOf(t, zone)) { SleepContext.quality(localDateOf(t, zone), zone, sessions, targets) }
                q == if (sleep == SleepFilter.Good) SleepQuality.Good else SleepQuality.Poor
            })
        }
    }
}
