package com.javon.healthdash.domain

import com.javon.healthdash.calc.ContextSplit
import com.javon.healthdash.calc.DAY_MS
import com.javon.healthdash.calc.Daily
import com.javon.healthdash.calc.Fasting
import com.javon.healthdash.calc.GlucoseContext
import com.javon.healthdash.calc.GlucoseStats
import com.javon.healthdash.calc.GlucoseSummary
import com.javon.healthdash.calc.LabComparison
import com.javon.healthdash.calc.Labs
import com.javon.healthdash.calc.Metric
import com.javon.healthdash.calc.SleepContext
import com.javon.healthdash.calc.SleepFilter
import com.javon.healthdash.calc.SleepQuality
import com.javon.healthdash.calc.SplitFilter
import com.javon.healthdash.calc.TimeWindow
import com.javon.healthdash.calc.Trends
import com.javon.healthdash.calc.localDateOf
import com.javon.healthdash.calc.window
import com.javon.healthdash.data.db.LabEntity
import com.javon.healthdash.ui.components.TrendPoint
import java.time.LocalDate
import java.time.ZoneId

// Screen models for Phase 2. Everything numeric comes from :calc; these only assemble it.

enum class TrendMetric(val label: String, val unit: String, val goalKey: String, val goalAbove: Boolean, val defaultGoal: Double) {
    TightRange("Time in tight range", "%", "tirGoal", true, 85.0),
    Mean("Mean glucose", "mg/dL", "a1cGoal", false, 5.7),
    Above140("Time above 140", "%", "", false, 0.0),
    Below70("Time below 70", "%", "lowGoal", false, 4.0),
    Variability("Variability (CV)", "%", "cvGoal", false, 20.0),
    Fasting("Fasting glucose", "mg/dL", "fastingGoal", false, 100.0);

    fun of(s: GlucoseSummary): Double = when (this) {
        TightRange -> s.pctTight
        Mean -> s.mean
        Above140 -> s.pctAboveTight
        Below70 -> s.pctBelowLow
        Variability -> s.cv
        Fasting -> s.mean
    }

    /** Target value in this metric's unit (Mean converts the A1c goal to mg/dL via the GMI formula). */
    fun goal(values: Map<String, Double>): Double? = when (this) {
        Above140 -> null
        Mean -> ((values["a1cGoal"] ?: 5.7) - GlucoseStats.GMI_INTERCEPT) / GlucoseStats.GMI_SLOPE
        else -> values[goalKey] ?: defaultGoal
    }
}

private fun dates(end: LocalDate, days: Int) = (days - 1 downTo 0).map { end.minusDays(it.toLong()) }

private fun DashboardState.mealTimes() = meals.map { it.event.timeMs }

// ---------------- A1c Progress ----------------

data class A1cModel(
    val gmi90: Metric<GlucoseSummary>,
    val gmi30: Metric<GlucoseSummary>,
    val gmi14: Metric<GlucoseSummary>,
    val rolling: List<TrendPoint>,
    /** Change in 14-day GMI from the first to the last day with enough data in the 90-day line. */
    val change: Double?,
    val changeSince: Long?,
    val labsA1c: List<LabEntity>,
    val latestLab: LabEntity?,
    val comparison: Metric<LabComparison>?,
    val fasting: List<TrendPoint>,
    val fastingAvg14: Double?,
    val fastingDays14: Int,
)

fun buildA1c(s: DashboardState, zone: ZoneId): A1cModel {
    val now = s.nowMs
    fun period(days: Int) = GlucoseStats.summarize(Analysis.slice(s.glucose, now - days * DAY_MS, now), TimeWindow(now - days * DAY_MS, now), s.targets)
    val today = localDateOf(now, zone)
    val ds = dates(today, 90)
    val rolling = Daily.rollingGmi(ds, s.glucose, zone, 14, s.targets).map { (d, m) -> TrendPoint(d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), m.valueOrNull()) }
    val withData = rolling.filter { it.value != null }
    val change = if (withData.size >= 2 && withData.last().timeMs - withData.first().timeMs >= 14 * DAY_MS) withData.last().value!! - withData.first().value!! else null
    val a1c = s.labs.filter { it.kind == LabEntity.A1C }
    val latest = a1c.maxByOrNull { it.timeMs }
    val meals = s.mealTimes()
    val fasting = ds.map { d -> TrendPoint(d.atTime(6, 0).atZone(zone).toInstant().toEpochMilli(), Fasting.forDay(d, zone, s.glucose, meals, s.targets).valueOrNull()) }
    val last14 = fasting.takeLast(14).mapNotNull { it.value }
    return A1cModel(
        gmi90 = period(90), gmi30 = period(30), gmi14 = period(14),
        rolling = rolling, change = change, changeSince = withData.firstOrNull()?.timeMs,
        labsA1c = a1c, latestLab = latest,
        comparison = latest?.let { Labs.compare(it.timeMs, it.value, s.glucose, s.targets) },
        fasting = fasting, fastingAvg14 = last14.takeIf { it.isNotEmpty() }?.average(), fastingDays14 = last14.size,
    )
}

// ---------------- Trends ----------------

data class DayRow(val date: LocalDate, val tir: Double?, val sleepMin: Int?, val quality: SleepQuality?)

data class TrendsModel(
    val summary: Metric<GlucoseSummary>,
    val fastingAvg: Double?,
    val fastingDays: Int,
    val sleepAvgMin: Double?,
    val nights: Int,
    val hrvAvg: Double?,
    val restingHrAvg: Double?,
    val stepsPerDay: Double,
    val days: List<DayRow>,
)

private fun includeFor(s: DashboardState, zone: ZoneId, days: Int, split: SplitFilter, sleep: SleepFilter): (Long) -> Boolean {
    val today = localDateOf(s.nowMs, zone)
    val overnight = SleepContext.overnightWindows(today.minusDays(days.toLong()), today, zone, s.sleep, s.targets)
    return Trends.include(split, sleep, zone, s.workoutWindows, overnight, s.sleep, s.targets)
}

fun buildTrends(s: DashboardState, zone: ZoneId, days: Int, split: SplitFilter, sleep: SleepFilter): TrendsModel {
    val now = s.nowMs
    val w = TimeWindow(now - days * DAY_MS, now)
    val include = includeFor(s, zone, days, split, sleep)
    val today = localDateOf(now, zone)
    val ds = dates(today, days)
    val sleepOk: (LocalDate) -> Boolean = { d ->
        sleep == SleepFilter.Any || SleepContext.quality(d, zone, s.sleep, s.targets) == (if (sleep == SleepFilter.Good) SleepQuality.Good else SleepQuality.Poor)
    }
    val meals = s.mealTimes()
    val fasting = ds.filter(sleepOk).mapNotNull { Fasting.forDay(it, zone, s.glucose, meals, s.targets).valueOrNull() }
    val nights = ds.map { SleepContext.totalMinutes(SleepContext.previousNight(it, zone, s.sleep)) }.filter { it > 0 }
    val hrv = ds.filter(sleepOk).mapNotNull { SleepContext.morningValue(it, zone, s.hrv) }
    val rhr = ds.filter(sleepOk).mapNotNull { SleepContext.morningValue(it, zone, s.restingHr) }
    val steps = s.steps.sumOf { st ->
        val overlap = minOf(now, st.endMs) - maxOf(w.startMs, st.startMs)
        if (overlap <= 0) 0.0 else st.count * overlap.toDouble() / (st.endMs - st.startMs).coerceAtLeast(1)
    }
    return TrendsModel(
        summary = Daily.summarizeWhere(Analysis.slice(s.glucose, w.startMs, w.endMs), w, s.targets, include),
        fastingAvg = fasting.takeIf { it.isNotEmpty() }?.average(), fastingDays = fasting.size,
        sleepAvgMin = nights.takeIf { it.isNotEmpty() }?.average(), nights = nights.size,
        hrvAvg = hrv.takeIf { it.isNotEmpty() }?.average(),
        restingHrAvg = rhr.takeIf { it.isNotEmpty() }?.average(),
        stepsPerDay = steps / days,
        days = ds.reversed().take(14).map { d ->
            val night = SleepContext.previousNight(d, zone, s.sleep)
            DayRow(d, Daily.day(d, s.glucose, zone, s.targets).valueOrNull()?.pctTight, SleepContext.totalMinutes(night).takeIf { it > 0 }, SleepContext.quality(d, zone, s.sleep, s.targets))
        },
    )
}

// ---------------- Metric detail ----------------

data class MetricDetailModel(
    val series: List<TrendPoint>,
    val period: Metric<Double>,
    val byContext: List<Triple<GlucoseContext, Metric<Double>, Double>>,
    val good: Pair<Metric<Double>, Int>,
    val poor: Pair<Metric<Double>, Int>,
)

private fun Metric<GlucoseSummary>.map(m: TrendMetric): Metric<Double> = when (this) {
    is Metric.Value -> Metric.Value(m.of(value), coverage)
    is Metric.NotEnoughData -> this
}

fun buildMetricDetail(s: DashboardState, zone: ZoneId, metric: TrendMetric, days: Int): MetricDetailModel {
    val now = s.nowMs
    val today = localDateOf(now, zone)
    val ds = dates(today, days)
    val meals = s.mealTimes()
    val w = TimeWindow(now - days * DAY_MS, now)
    val pts = Analysis.slice(s.glucose, w.startMs, w.endMs)
    if (metric == TrendMetric.Fasting) {
        val byDay = ds.map { d -> d to Fasting.forDay(d, zone, s.glucose, meals, s.targets) }
        fun avg(filter: (LocalDate) -> Boolean): Pair<Metric<Double>, Int> {
            val v = byDay.filter { filter(it.first) }.mapNotNull { it.second.valueOrNull() }
            return (if (v.isEmpty()) Metric.NotEnoughData(0.0) else Metric.Value(v.average(), 1.0)) to v.size
        }
        return MetricDetailModel(
            series = byDay.map { (d, m) -> TrendPoint(d.atTime(6, 0).atZone(zone).toInstant().toEpochMilli(), m.valueOrNull()) },
            period = avg { true }.first,
            byContext = emptyList(),
            good = avg { SleepContext.quality(it, zone, s.sleep, s.targets) == SleepQuality.Good },
            poor = avg { SleepContext.quality(it, zone, s.sleep, s.targets) == SleepQuality.Poor },
        )
    }
    val overnight = SleepContext.overnightWindows(today.minusDays(days.toLong()), today, zone, s.sleep, s.targets)
    val byContext = GlucoseContext.entries.map { ctx ->
        val inc: (Long) -> Boolean = { ContextSplit.classify(it, s.workoutWindows, overnight, s.targets) == ctx }
        val m = Daily.summarizeWhere(pts, w, s.targets, inc)
        Triple(ctx, m.map(metric), (m as? Metric.Value)?.value?.count?.toDouble() ?: 0.0)
    }
    fun bySleep(f: SleepFilter): Pair<Metric<Double>, Int> {
        val n = ds.count { SleepContext.quality(it, zone, s.sleep, s.targets) == (if (f == SleepFilter.Good) SleepQuality.Good else SleepQuality.Poor) }
        return Daily.summarizeWhere(pts, w, s.targets, Trends.include(SplitFilter.All, f, zone, s.workoutWindows, overnight, s.sleep, s.targets)).map(metric) to n
    }
    return MetricDetailModel(
        series = ds.map { d -> TrendPoint(d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), Daily.day(d, s.glucose, zone, s.targets).map(metric).valueOrNull()) },
        period = GlucoseStats.summarize(pts, w, s.targets).map(metric),
        byContext = byContext,
        good = bySleep(SleepFilter.Good),
        poor = bySleep(SleepFilter.Poor),
    )
}

// ---------------- Day context ----------------

data class DayContextModel(
    val date: LocalDate,
    val window: TimeWindow,
    val summary: Metric<GlucoseSummary>,
    val fasting: Metric<Double>,
    val sleepMin: Int?,
    val deepMin: Int?,
    val quality: SleepQuality?,
    val hrv: Double?,
    val hrvBaseline: Double?,
    val restingHr: Double?,
    /** Average time in range on days with HRV at/above vs below baseline (last 30 days), with counts. */
    val hrvSplit: Pair<Pair<Double, Int>, Pair<Double, Int>>?,
)

fun buildDayContext(s: DashboardState, zone: ZoneId, date: LocalDate): DayContextModel {
    val night = SleepContext.previousNight(date, zone, s.sleep)
    val meals = s.mealTimes()
    val above = mutableListOf<Double>()
    val below = mutableListOf<Double>()
    for (i in 1..30) {
        val d = date.minusDays(i.toLong() - 1)
        val v = SleepContext.morningValue(d, zone, s.hrv) ?: continue
        val base = SleepContext.baseline(d, zone, s.hrv) ?: continue
        val tir = Daily.day(d, s.glucose, zone, s.targets).valueOrNull()?.pctTight ?: continue
        if (v >= base) above += tir else below += tir
    }
    return DayContextModel(
        date = date,
        window = date.window(zone),
        summary = Daily.day(date, s.glucose, zone, s.targets),
        fasting = Fasting.forDay(date, zone, s.glucose, meals, s.targets),
        sleepMin = SleepContext.totalMinutes(night).takeIf { it > 0 },
        deepMin = night.mapNotNull { it.deepMin }.takeIf { it.isNotEmpty() }?.sum(),
        quality = SleepContext.quality(date, zone, s.sleep, s.targets),
        hrv = SleepContext.morningValue(date, zone, s.hrv),
        hrvBaseline = SleepContext.baseline(date, zone, s.hrv),
        restingHr = SleepContext.morningValue(date, zone, s.restingHr),
        hrvSplit = if (above.size >= 3 && below.size >= 3) (above.average() to above.size) to (below.average() to below.size) else null,
    )
}
