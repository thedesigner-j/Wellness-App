package com.javon.healthdash.calc

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS

/** One CGM reading. */
data class GlucosePoint(val timeMs: Long, val mgdl: Double)

/** A step count spread evenly over [startMs, endMs). */
data class StepInterval(val startMs: Long, val endMs: Long, val count: Long)

/** A workout (or sleep) window. */
data class TimeWindow(val startMs: Long, val endMs: Long) {
    operator fun contains(t: Long) = t in startMs until endMs
    val durationMs get() = endMs - startMs
}

/** A logged meal as the calculations see it. [foodKey] groups "the same meal" across days. */
data class MealEvent(
    val id: Long,
    val timeMs: Long,
    val foodKey: String,
    val carbsG: Double? = null,
)

/**
 * Every threshold the calculations use. Values come from the editable targets table in the app;
 * these defaults match SPEC.md.
 */
data class Targets(
    val tightLow: Double = 70.0,
    val tightHigh: Double = 140.0,
    val veryHigh: Double = 180.0,
    val minCoverage: Double = 0.70,
    val cgmIntervalMin: Int = 5,
    val trainingAfterMin: Int = 60,
    val baselineMin: Int = 15,
    val responseWindowMin: Int = 120,
    val walkWindowMin: Int = 45,
    val walkSteps: Long = 1_000,
    val unloggedRiseMgdl: Double = 30.0,
    val unloggedRiseWindowMin: Int = 30,
    val unloggedMealLookbackMin: Int = 30,
    val minRankInstances: Int = 2,
    val minForecastInstances: Int = 2,
)

/** A value that is only shown when enough data backs it (SPEC §5, §12). */
sealed interface Metric<out T> {
    data class Value<T>(val value: T, val coverage: Double) : Metric<T>
    data class NotEnoughData(val coverage: Double) : Metric<Nothing>

    fun valueOrNull(): T? = (this as? Value<T>)?.value
}
