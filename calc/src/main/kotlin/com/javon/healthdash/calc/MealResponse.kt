package com.javon.healthdash.calc

import kotlin.math.roundToInt

/** Glucose response to one meal (SPEC §5, Meals). */
data class MealResponse(
    /** Mean of readings in the 15 minutes before the meal. */
    val baseline: Double,
    val peak: Double,
    val peakRise: Double,
    val timeToPeakMin: Int,
    /** Minutes until glucose first returns to baseline after the peak; null if it did not within the window. */
    val backToBaselineMin: Int?,
    /** Area above baseline over the response window, mg/dL·min (trapezoidal). */
    val auc: Double,
    /** Fraction of the 2-hour window covered by readings. */
    val coverage: Double,
)

/** Whether the user walked (or trained) right after the meal. */
data class WalkEffect(val steps: Long, val workout: Boolean, val walked: Boolean)

sealed interface ResponseResult {
    data class Ready(val response: MealResponse) : ResponseResult
    /** No baseline readings before the meal. */
    data object NoBaseline : ResponseResult
    /** Readings after the meal cover less than the required share (data may still arrive). */
    data class Incomplete(val coverage: Double) : ResponseResult
}

object MealResponses {

    fun compute(meal: MealEvent, points: List<GlucosePoint>, targets: Targets = Targets()): ResponseResult {
        val t0 = meal.timeMs
        val base = points.filter { it.timeMs >= t0 - targets.baselineMin * MINUTE_MS && it.timeMs <= t0 }
        if (base.size < 2) return ResponseResult.NoBaseline
        val baseline = base.sumOf { it.mgdl } / base.size

        val window = TimeWindow(t0 + 1, t0 + targets.responseWindowMin * MINUTE_MS + 1)
        val after = points.filter { it.timeMs in window }.sortedBy { it.timeMs }
        val cov = GlucoseStats.coverage(after, window, targets.cgmIntervalMin)
        if (after.isEmpty() || cov < targets.minCoverage) return ResponseResult.Incomplete(cov)

        val peakPoint = after.maxBy { it.mgdl }
        val back = after.firstOrNull { it.timeMs > peakPoint.timeMs && it.mgdl <= baseline }

        // Trapezoids from the meal time (at baseline) through each reading, counting only area above baseline.
        var auc = 0.0
        var prevT = t0
        var prevExcess = 0.0
        for (p in after) {
            val excess = (p.mgdl - baseline).coerceAtLeast(0.0)
            val dtMin = (p.timeMs - prevT) / MINUTE_MS.toDouble()
            auc += (prevExcess + excess) / 2.0 * dtMin
            prevT = p.timeMs
            prevExcess = excess
        }

        return ResponseResult.Ready(
            MealResponse(
                baseline = baseline,
                peak = peakPoint.mgdl,
                peakRise = peakPoint.mgdl - baseline,
                timeToPeakMin = ((peakPoint.timeMs - t0) / MINUTE_MS).toInt(),
                backToBaselineMin = back?.let { ((it.timeMs - t0) / MINUTE_MS).toInt() },
                auc = auc,
                coverage = cov,
            ),
        )
    }

    /**
     * Steps in the 0–45 min after the meal (step intervals are prorated by overlap).
     * Walked = steps ≥ threshold or a workout overlapping that window.
     */
    fun walkEffect(
        meal: MealEvent,
        steps: List<StepInterval>,
        workouts: List<TimeWindow>,
        targets: Targets = Targets(),
    ): WalkEffect {
        val start = meal.timeMs
        val end = meal.timeMs + targets.walkWindowMin * MINUTE_MS
        var total = 0.0
        for (s in steps) {
            val overlap = minOf(end, s.endMs) - maxOf(start, s.startMs)
            if (overlap <= 0) continue
            val dur = (s.endMs - s.startMs).coerceAtLeast(1)
            total += s.count * overlap.toDouble() / dur
        }
        val workout = workouts.any { it.startMs < end && it.endMs > start }
        val stepsRounded = total.roundToInt().toLong()
        return WalkEffect(stepsRounded, workout, stepsRounded >= targets.walkSteps || workout)
    }

    /**
     * Meal response score, 0–100 (higher is steadier). Formula:
     *
     *   riseScore     = clamp(1 − (peakRise − 20) / 60)   → 1 at ≤ +20 mg/dL, 0 at ≥ +80
     *   areaScore     = clamp(1 − auc / 6000)             → 0 at ≥ 6000 mg/dL·min above baseline
     *   recoveryScore = clamp(1 − (backMin − 60) / 60)    → 1 if back by 60 min, 0 at 120 or never
     *
     *   score = round(100 × (0.5·riseScore + 0.3·areaScore + 0.2·recoveryScore))
     */
    fun score(r: MealResponse): Int {
        fun clamp(x: Double) = x.coerceIn(0.0, 1.0)
        val rise = clamp(1 - (r.peakRise - 20.0) / 60.0)
        val area = clamp(1 - r.auc / 6000.0)
        val recovery = r.backToBaselineMin?.let { clamp(1 - (it - 60.0) / 60.0) } ?: 0.0
        return (100 * (0.5 * rise + 0.3 * area + 0.2 * recovery)).roundToInt()
    }

    enum class Grade { Good, Fair, Poor }

    fun grade(score: Int): Grade = when {
        score >= 75 -> Grade.Good
        score >= 50 -> Grade.Fair
        else -> Grade.Poor
    }
}
