package com.javon.healthdash.calc

import kotlin.math.sqrt

data class GlucoseSummary(
    val mean: Double,
    val sd: Double,
    /** Coefficient of variation, percent. */
    val cv: Double,
    /** Glucose Management Indicator (estimated A1c), percent. */
    val gmi: Double,
    /** Percent of readings in 70–140 (tight range). */
    val pctTight: Double,
    val pctBelowLow: Double,
    val pctAboveTight: Double,
    val pctAboveVeryHigh: Double,
    val count: Int,
)

object GlucoseStats {
    const val GMI_INTERCEPT = 3.31
    const val GMI_SLOPE = 0.02392

    /** GMI (%) = 3.31 + 0.02392 × mean glucose (mg/dL). */
    fun gmi(meanMgdl: Double): Double = GMI_INTERCEPT + GMI_SLOPE * meanMgdl

    /**
     * Fraction of expected CGM slots in [window] that hold at least one reading.
     * A slot is one CGM interval (5 min by default).
     */
    fun coverage(points: List<GlucosePoint>, window: TimeWindow, intervalMin: Int = 5): Double {
        val slotMs = intervalMin * MINUTE_MS
        val expected = (window.durationMs / slotMs).toInt()
        if (expected <= 0) return 0.0
        val filled = points.asSequence()
            .filter { it.timeMs in window }
            .map { ((it.timeMs - window.startMs) / slotMs).toInt() }
            .toSet().size
        return (filled.toDouble() / expected).coerceAtMost(1.0)
    }

    /**
     * Summary statistics for the readings inside [window].
     * Returns [Metric.NotEnoughData] when coverage is below [Targets.minCoverage].
     * Percentages are per reading; CGM readings are evenly spaced so this equals time share.
     */
    fun summarize(points: List<GlucosePoint>, window: TimeWindow, targets: Targets = Targets()): Metric<GlucoseSummary> {
        val inWindow = points.filter { it.timeMs in window }
        val cov = coverage(inWindow, window, targets.cgmIntervalMin)
        if (inWindow.isEmpty() || cov < targets.minCoverage) return Metric.NotEnoughData(cov)
        return Metric.Value(summarizeReadings(inWindow.map { it.mgdl }, targets), cov)
    }

    /** Summary of a set of readings with no coverage check (callers check coverage). */
    fun summarizeReadings(values: List<Double>, targets: Targets = Targets()): GlucoseSummary {
        require(values.isNotEmpty())
        val n = values.size
        val mean = values.sum() / n
        // Sample standard deviation (n − 1), the convention CGM reports use.
        val sd = if (n > 1) sqrt(values.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
        fun pct(pred: (Double) -> Boolean) = 100.0 * values.count(pred) / n
        return GlucoseSummary(
            mean = mean,
            sd = sd,
            cv = if (mean > 0) 100.0 * sd / mean else 0.0,
            gmi = gmi(mean),
            pctTight = pct { it >= targets.tightLow && it <= targets.tightHigh },
            pctBelowLow = pct { it < targets.tightLow },
            pctAboveTight = pct { it > targets.tightHigh },
            pctAboveVeryHigh = pct { it > targets.veryHigh },
            count = n,
        )
    }

    /** Latest reading at or before [nowMs], or null. */
    fun latest(points: List<GlucosePoint>, nowMs: Long): GlucosePoint? =
        points.filter { it.timeMs <= nowMs }.maxByOrNull { it.timeMs }

    /**
     * Trend from the slope of the last 15 minutes, in mg/dL per minute.
     * Thresholds follow the usual CGM arrow convention (1 and 2 mg/dL/min).
     */
    fun trend(points: List<GlucosePoint>, nowMs: Long): Trend? {
        val recent = points.filter { it.timeMs in (nowMs - 15 * MINUTE_MS)..nowMs }.sortedBy { it.timeMs }
        if (recent.size < 2) return null
        val first = recent.first()
        val last = recent.last()
        val minutes = (last.timeMs - first.timeMs) / MINUTE_MS.toDouble()
        if (minutes < 5) return null
        val slope = (last.mgdl - first.mgdl) / minutes
        return when {
            slope >= 2 -> Trend.RisingFast
            slope >= 1 -> Trend.Rising
            slope <= -2 -> Trend.FallingFast
            slope <= -1 -> Trend.Falling
            else -> Trend.Steady
        }
    }
}

enum class Trend { RisingFast, Rising, Steady, Falling, FallingFast }

/** Glucose status band for a single value. */
enum class GlucoseStatus { Low, InRange, High, VeryHigh }

fun statusOf(mgdl: Double, targets: Targets = Targets()): GlucoseStatus = when {
    mgdl < targets.tightLow -> GlucoseStatus.Low
    mgdl <= targets.tightHigh -> GlucoseStatus.InRange
    mgdl <= targets.veryHigh -> GlucoseStatus.High
    else -> GlucoseStatus.VeryHigh
}
