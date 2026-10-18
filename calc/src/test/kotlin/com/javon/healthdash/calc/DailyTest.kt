package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class DailyTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 15)
    private val midnight = day.atStartOfDay(utc).toInstant().toEpochMilli()
    private fun at(h: Double) = midnight + (h * HOUR_MS).toLong()

    /** Readings every 5 min from [from] (hours) to [to], value from [v]. */
    private fun series(from: Double, to: Double, v: (Long) -> Double): List<GlucosePoint> {
        val out = mutableListOf<GlucosePoint>()
        var t = at(from)
        while (t < at(to)) { out += GlucosePoint(t, v(t)); t += 5 * MINUTE_MS }
        return out
    }

    @Test fun fastingIsMeanOfMorningWindow() {
        // 5:00–5:55 at 90, 6:00–6:55 at 110 → mean 100.
        val pts = series(4.0, 8.0) { if (it < at(6.0)) 90.0 else 110.0 }
        assertThat(Fasting.forDay(day, utc, pts, emptyList()).valueOrNull()!!).isWithin(1e-9).of(100.0)
    }

    @Test fun fastingStopsAtFirstMeal() {
        val pts = series(4.0, 8.0) { if (it < at(6.0)) 90.0 else 110.0 }
        assertThat(Fasting.forDay(day, utc, pts, listOf(at(6.0))).valueOrNull()!!).isWithin(1e-9).of(90.0)
        // A meal 10 minutes into the window leaves too little time.
        assertThat(Fasting.forDay(day, utc, pts, listOf(at(5.0 + 10.0 / 60)))).isInstanceOf(Metric.NotEnoughData::class.java)
    }

    @Test fun fastingNeedsCoverage() {
        val sparse = series(5.0, 5.5) { 95.0 }
        assertThat(Fasting.forDay(day, utc, sparse, emptyList())).isInstanceOf(Metric.NotEnoughData::class.java)
    }

    @Test fun labComparisonFlagsLargeDifference() {
        val labTime = at(9.0)
        val pts = mutableListOf<GlucosePoint>()
        var t = labTime - 90 * DAY_MS
        while (t < labTime) { pts += GlucosePoint(t, 119.0); t += 5 * MINUTE_MS }
        // GMI = 3.31 + 0.02392 × 119 = 6.15648
        val far = Labs.compare(labTime, 6.8, pts).valueOrNull()!!
        assertThat(far.gmi).isWithin(1e-9).of(6.15648)
        assertThat(far.diff).isWithin(1e-9).of(0.64352)
        assertThat(far.differsNotably).isTrue()
        assertThat(Labs.compare(labTime, 6.3, pts).valueOrNull()!!.differsNotably).isFalse()
        assertThat(Labs.compare(labTime, 6.3, pts.takeLast(100))).isInstanceOf(Metric.NotEnoughData::class.java)
    }

    @Test fun summarizeWhereJudgesCoverageOnFilteredTime() {
        val window = TimeWindow(at(0.0), at(1.0))
        val firstHalf = series(0.0, 0.5) { 100.0 }
        val r = Daily.summarizeWhere(firstHalf, window) { it < at(0.5) }
        assertThat((r as Metric.Value).coverage).isWithin(1e-9).of(1.0)
        assertThat(Daily.summarizeWhere(firstHalf, window) { false }).isInstanceOf(Metric.NotEnoughData::class.java)
        // Over the whole hour the same readings are only 50% coverage.
        assertThat(Daily.summarizeWhere(firstHalf, window) { true }).isInstanceOf(Metric.NotEnoughData::class.java)
    }

    @Test fun rollingGmi() {
        val pts = mutableListOf<GlucosePoint>()
        var t = day.minusDays(20).atStartOfDay(utc).toInstant().toEpochMilli()
        while (t < midnight + DAY_MS) { pts += GlucosePoint(t, 100.0); t += 5 * MINUTE_MS }
        val r = Daily.rollingGmi(listOf(day, day.minusDays(40)), pts, utc)
        assertThat(r[0].second.valueOrNull()!!).isWithin(1e-9).of(5.702)
        assertThat(r[1].second).isInstanceOf(Metric.NotEnoughData::class.java)
    }

    @Test fun sleepNightsAndQuality() {
        val good = SleepSession(TimeWindow(at(-1.0), at(6.5)))   // 23:00 → 06:30, 7.5 h
        assertThat(SleepContext.previousNight(day, utc, listOf(good))).containsExactly(good)
        assertThat(SleepContext.totalMinutes(listOf(good))).isEqualTo(450)
        assertThat(SleepContext.quality(day, utc, listOf(good))).isEqualTo(SleepQuality.Good)
        val short = SleepSession(TimeWindow(at(0.5), at(6.5)))  // 6 h
        assertThat(SleepContext.quality(day, utc, listOf(short))).isEqualTo(SleepQuality.Poor)
        assertThat(SleepContext.quality(day.plusDays(2), utc, listOf(good))).isNull()
    }

    @Test fun defaultOvernightWindowWhenNoSleepRecorded() {
        val good = SleepSession(TimeWindow(at(-1.0), at(6.5)))
        val w = SleepContext.overnightWindows(day, day.plusDays(1), utc, listOf(good))
        assertThat(w).hasSize(2)
        // Night before Oct 16 has no session → default 22:30 → 06:00.
        assertThat(w[1]).isEqualTo(TimeWindow(at(22.5), at(30.0)))
    }

    @Test fun morningValueAndBaseline() {
        val readings = (1..10).map { Reading(midnight - it * DAY_MS + 6 * HOUR_MS, 50.0) } + Reading(at(6.0), 40.0)
        assertThat(SleepContext.morningValue(day, utc, readings)).isWithin(1e-9).of(40.0)
        assertThat(SleepContext.baseline(day, utc, readings)!!).isWithin(1e-9).of(50.0)
        assertThat(SleepContext.baseline(day, utc, readings.take(5))).isNull()
    }

    @Test fun trendFilters() {
        val workout = TimeWindow(at(6.0), at(7.0))
        val sleep = listOf(SleepSession(TimeWindow(at(-1.0), at(6.5))))
        val overnight = SleepContext.overnightWindows(day, day, utc, sleep)
        val training = Trends.include(SplitFilter.Training, SleepFilter.Any, utc, listOf(workout), overnight, sleep)
        assertThat(training(at(6.5))).isTrue()
        assertThat(training(at(12.0))).isFalse()
        val goodNights = Trends.include(SplitFilter.All, SleepFilter.Good, utc, emptyList(), overnight, sleep)
        assertThat(goodNights(at(12.0))).isTrue()
        assertThat(goodNights(at(36.0))).isFalse() // next day has no recorded night
        val poor = Trends.include(SplitFilter.All, SleepFilter.Poor, utc, emptyList(), overnight, sleep)
        assertThat(poor(at(12.0))).isFalse()
    }

    @Test fun fakeDataIncludesContextData() {
        val d = FakeData.generate(30 * DAY_MS, days = 14, zone = utc)
        assertThat(d.sleep).isNotEmpty()
        assertThat(d.hrvMs).hasSize(d.sleep.size)
        assertThat(d.labA1c).hasSize(2)
    }
}
