package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlucoseStatsTest {

    @Test fun gmiMatchesFormula() {
        // 3.31 + 0.02392 × 100 = 5.702
        assertThat(GlucoseStats.gmi(100.0)).isWithin(1e-9).of(5.702)
        // 3.31 + 0.02392 × 119 = 6.15648
        assertThat(GlucoseStats.gmi(119.0)).isWithin(1e-9).of(6.15648)
    }

    @Test fun summaryOfFourReadings() {
        val s = GlucoseStats.summarizeReadings(listOf(60.0, 100.0, 150.0, 200.0))
        assertThat(s.mean).isWithin(1e-9).of(127.5)
        // squared deviations 4556.25 + 756.25 + 506.25 + 5256.25 = 11075; / 3 = 3691.667; sqrt = 60.7591
        assertThat(s.sd).isWithin(1e-3).of(60.7591)
        assertThat(s.cv).isWithin(1e-2).of(47.65)
        assertThat(s.pctTight).isWithin(1e-9).of(25.0)
        assertThat(s.pctBelowLow).isWithin(1e-9).of(25.0)
        assertThat(s.pctAboveTight).isWithin(1e-9).of(50.0)
        assertThat(s.pctAboveVeryHigh).isWithin(1e-9).of(25.0)
    }

    @Test fun boundariesCountAsInRange() {
        val s = GlucoseStats.summarizeReadings(listOf(70.0, 140.0))
        assertThat(s.pctTight).isWithin(1e-9).of(100.0)
    }

    @Test fun lowCoverageIsNotEnoughData() {
        val window = TimeWindow(0, HOUR_MS)
        val half = (0 until 6).map { GlucosePoint(it * 5 * MINUTE_MS, 100.0) }
        val result = GlucoseStats.summarize(half, window)
        assertThat(result).isInstanceOf(Metric.NotEnoughData::class.java)
        assertThat((result as Metric.NotEnoughData).coverage).isWithin(1e-9).of(0.5)
    }

    @Test fun fullCoverageReturnsValue() {
        val window = TimeWindow(0, HOUR_MS)
        val full = (0 until 12).map { GlucosePoint(it * 5 * MINUTE_MS, 100.0) }
        val result = GlucoseStats.summarize(full, window)
        assertThat(result).isInstanceOf(Metric.Value::class.java)
        assertThat(result.valueOrNull()!!.mean).isWithin(1e-9).of(100.0)
        assertThat((result as Metric.Value).coverage).isWithin(1e-9).of(1.0)
    }

    @Test fun seventyPercentCoverageIsEnough() {
        val window = TimeWindow(0, 100 * 5 * MINUTE_MS)
        val pts = (0 until 70).map { GlucosePoint(it * 5 * MINUTE_MS, 100.0) }
        assertThat(GlucoseStats.summarize(pts, window)).isInstanceOf(Metric.Value::class.java)
        assertThat(GlucoseStats.summarize(pts.dropLast(1), window)).isInstanceOf(Metric.NotEnoughData::class.java)
    }

    @Test fun duplicateReadingsInOneSlotCountOnce() {
        val window = TimeWindow(0, 10 * MINUTE_MS)
        val pts = listOf(GlucosePoint(0, 100.0), GlucosePoint(MINUTE_MS, 101.0))
        assertThat(GlucoseStats.coverage(pts, window)).isWithin(1e-9).of(0.5)
    }

    @Test fun trendFromSlope() {
        val now = 10 * MINUTE_MS
        val rising = listOf(GlucosePoint(0, 100.0), GlucosePoint(now, 115.0)) // 1.5 mg/dL/min
        assertThat(GlucoseStats.trend(rising, now)).isEqualTo(Trend.Rising)
        val fast = listOf(GlucosePoint(0, 100.0), GlucosePoint(now, 125.0)) // 2.5
        assertThat(GlucoseStats.trend(fast, now)).isEqualTo(Trend.RisingFast)
        val steady = listOf(GlucosePoint(0, 100.0), GlucosePoint(now, 104.0))
        assertThat(GlucoseStats.trend(steady, now)).isEqualTo(Trend.Steady)
        val falling = listOf(GlucosePoint(0, 120.0), GlucosePoint(now, 105.0))
        assertThat(GlucoseStats.trend(falling, now)).isEqualTo(Trend.Falling)
        assertThat(GlucoseStats.trend(listOf(GlucosePoint(now, 100.0)), now)).isNull()
    }

    @Test fun statusBands() {
        assertThat(statusOf(69.0)).isEqualTo(GlucoseStatus.Low)
        assertThat(statusOf(70.0)).isEqualTo(GlucoseStatus.InRange)
        assertThat(statusOf(140.0)).isEqualTo(GlucoseStatus.InRange)
        assertThat(statusOf(141.0)).isEqualTo(GlucoseStatus.High)
        assertThat(statusOf(181.0)).isEqualTo(GlucoseStatus.VeryHigh)
    }

    @Test fun contextSplit() {
        val workout = TimeWindow(6 * HOUR_MS, 7 * HOUR_MS)
        val sleep = TimeWindow(0, 5 * HOUR_MS)
        fun ctx(h: Double) = ContextSplit.classify((h * HOUR_MS).toLong(), listOf(workout), listOf(sleep))
        assertThat(ctx(2.0)).isEqualTo(GlucoseContext.Overnight)
        assertThat(ctx(6.5)).isEqualTo(GlucoseContext.Training)
        assertThat(ctx(7.5)).isEqualTo(GlucoseContext.Training) // within 60 min after
        assertThat(ctx(8.5)).isEqualTo(GlucoseContext.Everyday)
    }
}
