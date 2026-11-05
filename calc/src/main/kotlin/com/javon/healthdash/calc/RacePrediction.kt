package com.javon.healthdash.calc

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Race prediction, expected improvement and goal probability. Fixed rules, no AI.
//
//   Fitness today = best qualifying hard effort (≥ 3 mi, last 6 weeks), adjusted for heat and hills,
//                   scaled to the race with Riegel (k from recent endurance).
//   Projected     = Fitness today × (1 − gain) [+ first-marathon penalty]
//   P(goal)       = Φ((goal − projected) / SD),  SD% = √(4² + 2² + (0.3 × weeks out)²)

/** A finished run with what the prediction needs. Elapsed time, not moving time. */
data class EffortRun(
    val startMs: Long,
    val distanceMi: Double,
    val elapsedSec: Int,
    val avgHr: Double? = null,
    /** Perceived effort 1–10, if rated. */
    val rpe: Int? = null,
    /** Average air temperature during the run. */
    val tempF: Double? = null,
    val ascentFt: Double? = null,
    val descentFt: Double? = null,
    val label: String? = null,
)

data class PredictionConfig(
    /** Estimated improvement per remaining build week at full adherence — to be tuned from the pace-at-145 trend. */
    val gainPerBuildWeek: Double = GAIN_PER_BUILD_WEEK,
    val maxGain: Double = 0.04,
    /** Standard deviation components, % of projected time. */
    val predictionErrorPct: Double = 4.0,
    val raceDayPct: Double = 2.0,
    val perWeekOutPct: Double = 0.3,
    /** Adds [firstMarathonPct] to the projection for a first marathon. */
    val firstMarathonPenalty: Boolean = true,
    val firstMarathonPct: Double = 1.5,
    val defaultTaperWeeks: Int = 2,
) {
    companion object { const val GAIN_PER_BUILD_WEEK = 0.004 }
}

/** One planned session for weighted adherence: a long run counts 2 points, everything else 1. */
data class AdherenceItem(val date: LocalDate, val long: Boolean, val done: Boolean)

data class RaceForecast(
    val effort: EffortRun,
    /** Effort time after removing heat and hill cost. */
    val adjustedSec: Int,
    val heatPct: Double,
    val hillSec: Int,
    val k: Double,
    val fitnessSec: Int,
    val buildWeeksLeft: Double,
    val adherence: Double,
    val gain: Double,
    val penaltyPct: Double,
    val projectedSec: Int,
    val weeksToRace: Double,
    val sdPct: Double,
    val sdSec: Int,
    val lowSec: Int,
    val highSec: Int,
    /** Raw probability; display is clamped to 5–95%. */
    val probability: Double?,
    val goalSec: Int?,
) {
    val displayPercent: Int? get() = probability?.let { (it * 100).roundToInt().coerceIn(5, 95) }
}

object RacePrediction {
    const val MIN_EFFORT_MI = 3.0
    const val WINDOW_DAYS = 42L
    const val HARD_SHARE_OF_THRESHOLD = 0.90
    const val HARD_RPE = 8
    /** 80% interval: ±1.2816 SD. */
    const val Z80 = 1.2816

    /**
     * A hard effort: avg HR ≥ 90% of threshold HR, or rated ≥ 8/10. Without a threshold yet, 90% of
     * (0.9 × max HR) stands in — LTHR typically sits near 90% of max.
     */
    fun isHard(r: EffortRun, lthr: Int?, maxHr: Int?): Boolean {
        if ((r.rpe ?: 0) >= HARD_RPE) return true
        val threshold = lthr?.toDouble() ?: maxHr?.let { it * 0.9 } ?: return false
        return (r.avgHr ?: return false) >= threshold * HARD_SHARE_OF_THRESHOLD
    }

    /** Heat: 1% of time per 5°F above 60°F. Returns the fraction removed. */
    fun heatFraction(tempF: Double?) = if (tempF == null || tempF <= 60) 0.0 else (tempF - 60) / 5 * 0.01

    /** Hills: 30 s per 100 ft of ascent removed, 15 s per 100 ft of descent added back. Returns seconds removed. */
    fun hillSeconds(ascentFt: Double?, descentFt: Double?) = (ascentFt ?: 0.0) / 100 * 30 - (descentFt ?: 0.0) / 100 * 15

    fun adjusted(r: EffortRun): Int = (r.elapsedSec * (1 - heatFraction(r.tempF)) - hillSeconds(r.ascentFt, r.descentFt)).roundToInt()

    /** Riegel exponent from the longest run of the last 4 weeks: < 13 mi 1.08, 13–17.9 mi 1.07, ≥ 18 mi 1.06. */
    fun riegelK(longestMi: Double) = when { longestMi >= 18.0 -> 1.06; longestMi >= 13.0 -> 1.07; else -> 1.08 }

    fun riegel(sec: Double, fromMi: Double, toMi: Double, k: Double) = sec * exp(k * ln(toMi / fromMi))

    /** Best qualifying effort in the last 6 weeks (fastest after scaling), or null → "Needs a hard effort". */
    fun bestEffort(runs: List<EffortRun>, nowMs: Long, raceMi: Double, k: Double, lthr: Int?, maxHr: Int?): Pair<EffortRun, Int>? =
        runs.filter { it.distanceMi >= MIN_EFFORT_MI && it.elapsedSec > 0 && it.startMs >= nowMs - WINDOW_DAYS * DAY_MS && it.startMs <= nowMs && isHard(it, lthr, maxHr) }
            .map { it to riegel(adjusted(it).toDouble(), it.distanceMi, raceMi, k).roundToInt() }
            .minByOrNull { it.second }

    /** Weighted completion over the last 4 weeks (long run 2 points, others 1). 1.0 when nothing was due. */
    fun adherence(items: List<AdherenceItem>, today: LocalDate): Double {
        val due = items.filter { it.date.isBefore(today) && !it.date.isBefore(today.minusDays(28)) }
        val planned = due.sumOf { if (it.long) 2 else 1 }
        if (planned == 0) return 1.0
        return due.filter { it.done }.sumOf { if (it.long) 2 else 1 }.toDouble() / planned
    }

    fun weeksBetween(from: LocalDate, to: LocalDate) = ChronoUnit.DAYS.between(from, to).coerceAtLeast(0) / 7.0

    /** Build weeks left: weeks to race (from the actual dates) minus the taper. */
    fun buildWeeksLeft(today: LocalDate, raceDate: LocalDate, taperWeeks: Int) = (weeksBetween(today, raceDate) - taperWeeks).coerceAtLeast(0.0)

    fun gain(buildWeeks: Double, adherence: Double, c: PredictionConfig = PredictionConfig()) =
        min(c.gainPerBuildWeek * buildWeeks * adherence.coerceIn(0.0, 1.0), c.maxGain)

    fun sdPct(weeksToRace: Double, c: PredictionConfig = PredictionConfig()) =
        sqrt(c.predictionErrorPct * c.predictionErrorPct + c.raceDayPct * c.raceDayPct + (c.perWeekOutPct * weeksToRace).let { it * it })

    fun forecast(
        runs: List<EffortRun>, nowMs: Long, today: LocalDate, raceDate: LocalDate, raceMi: Double, goalSec: Int?,
        longestRecentMi: Double, adherence: Double, lthr: Int?, maxHr: Int?, firstMarathon: Boolean,
        taperWeeks: Int? = null, c: PredictionConfig = PredictionConfig(),
    ): RaceForecast? {
        val k = riegelK(longestRecentMi)
        val (effort, fitness) = bestEffort(runs, nowMs, raceMi, k, lthr, maxHr) ?: return null
        val build = buildWeeksLeft(today, raceDate, taperWeeks ?: c.defaultTaperWeeks)
        val g = gain(build, adherence, c)
        val penalty = if (c.firstMarathonPenalty && firstMarathon && raceMi > 26.0) c.firstMarathonPct else 0.0
        val projected = (fitness * (1 - g) * (1 + penalty / 100)).roundToInt()
        val weeksOut = weeksBetween(today, raceDate)
        val sdPct = sdPct(weeksOut, c)
        val sd = projected * sdPct / 100
        return RaceForecast(
            effort, adjusted(effort), heatFraction(effort.tempF), hillSeconds(effort.ascentFt, effort.descentFt).roundToInt(), k, fitness,
            build, adherence, g, penalty, projected, weeksOut, sdPct, sd.roundToInt(),
            (projected - Z80 * sd).roundToInt(), (projected + Z80 * sd).roundToInt(),
            goalSec?.let { GoalLikelihood.phi((it - projected) / sd) }, goalSec,
        )
    }

    /** "4:28" — projections are rounded to the minute. */
    fun fmtMinute(sec: Int): String { val m = (sec / 60.0).roundToInt(); return "${m / 60}:${"%02d".format(m % 60)}" }
}
