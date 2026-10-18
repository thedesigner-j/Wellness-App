package com.javon.healthdash.calc

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// "How likely am I to hit my goal?" — a rule-based estimate, not a guarantee. Fixed rules, no AI.
//
// 1. Current fitness → today's predicted race time:
//    - best recent effort: the fastest run (or, for runs, typical easy pace − 1:15/mi as marathon pace if that is faster) (≥ 3 mi, last 42 days) scaled to race distance with Riegel (exponent 1.06; rides 1.05);
//    - VO2 max (runs only): Jack Daniels' VDOT race-time equation;
//    - both available → their average.
// 2. Expected gain by race day: 0.6% per remaining training week (taper excluded), max 8%, scaled by
//    consistency (sessions done in the last 14 days ÷ due).
// 3. Likelihood = P(race time ≤ goal) assuming the projection is normally distributed with a spread of
//    3% of the projected time plus 0.25% per week left (less certain further out).

data class EffortSample(val distanceMi: Double, val durationSec: Int, val daysAgo: Int)

enum class Outlook(val label: String) { VeryLikely("Very likely"), Likely("Likely"), TossUp("Toss-up"), Stretch("Stretch"), Unlikely("Unlikely") }

data class GoalOutlook(
    /** 0–100, rounded to 5. */
    val percent: Int,
    val outlook: Outlook,
    val currentSec: Int,
    val projectedSec: Int,
    val goalSec: Int,
    val gainPct: Double,
    val consistency: Double,
    val bestEffort: EffortSample?,
    val effortSec: Int?,
    val vo2Sec: Int?,
    /** True when fitness came from typical easy pace rather than the fastest run. */
    val fromTypicalPace: Boolean = false,
)

object GoalLikelihood {
    const val MIN_EFFORT_MI = 3.0
    const val WINDOW_DAYS = 42
    const val GAIN_PER_WEEK = 0.006
    const val MAX_GAIN = 0.08

    /** Daniels: VO2 cost of running at v m/min. */
    private fun vo2Cost(v: Double) = -4.60 + 0.182258 * v + 0.000104 * v * v

    /** Daniels: share of VO2 max sustainable for t minutes. */
    private fun sustainable(t: Double) = 0.8 + 0.1894393 * exp(-0.012778 * t) + 0.2989558 * exp(-0.1932605 * t)

    /** Race time (s) for [distanceMi] at a given VDOT / VO2 max, solved by bisection. */
    fun vdotTimeSec(vo2Max: Double, distanceMi: Double): Int {
        val meters = distanceMi * METERS_PER_MI
        var lo = 5.0; var hi = 1500.0
        repeat(60) {
            val t = (lo + hi) / 2
            if (vo2Cost(meters / t) / sustainable(t) > vo2Max) lo = t else hi = t
        }
        return ((lo + hi) / 2 * 60).roundToInt()
    }

    /** Fastest recent effort scaled to the race distance. */
    fun bestEffort(samples: List<EffortSample>, raceMi: Double, exponent: Double): Pair<EffortSample, Int>? =
        samples.filter { it.distanceMi >= MIN_EFFORT_MI && it.daysAgo in 0..WINDOW_DAYS && it.durationSec > 0 }
            .map { it to (it.durationSec * exp(exponent * ln(raceMi / it.distanceMi))).roundToInt() }
            .minByOrNull { it.second }

    /** Standard normal CDF (Abramowitz–Stegun 7.1.26). */
    fun phi(z: Double): Double {
        val x = abs(z) / sqrt(2.0)
        val t = 1 / (1 + 0.3275911 * x)
        val erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (z >= 0) (1 + erf) / 2 else (1 - erf) / 2
    }

    fun outlookFor(percent: Int) = when {
        percent >= 80 -> Outlook.VeryLikely
        percent >= 60 -> Outlook.Likely
        percent >= 40 -> Outlook.TossUp
        percent >= 20 -> Outlook.Stretch
        else -> Outlook.Unlikely
    }

    /**
     * @param trainingWeeksLeft weeks of training before the taper starts (0 in taper / race week)
     * @param weeksLeft weeks until race day
     * @param consistency sessions done ÷ due over the last 14 days (1.0 when nothing is due yet)
     * @return null when there's no recent effort and no VO2 max to base it on
     */
    fun estimate(
        sport: Sport, raceMi: Double, goalSec: Int, samples: List<EffortSample>, vo2Max: Double?,
        trainingWeeksLeft: Int, weeksLeft: Int, consistency: Double, typicalPaceSec: Int? = null,
    ): GoalOutlook? {
        val exponent = if (sport == Sport.Ride) 1.05 else 1.06
        val best = bestEffort(samples, raceMi, exponent)
        // Most recent runs are easy, so the fastest one undersells fitness. Runs: also try typical easy pace − 1:15/mi
        // as marathon pace (as [RunPlanBuilder]), Riegel-scaled to the race distance; use whichever is faster.
        val fromPace = if (sport == Sport.Run && typicalPaceSec != null) PaceMath.riegel((typicalPaceSec - 75.0) * MARATHON_MI, MARATHON_MI, raceMi).roundToInt() else null
        val effort = when {
            best != null && fromPace != null && fromPace < best.second -> best.first to fromPace
            else -> best
        }
        val usedPace = effort != null && effort.second == fromPace && fromPace != best?.second
        val vo2Sec = if (sport == Sport.Run && vo2Max != null && vo2Max in 25.0..90.0) vdotTimeSec(vo2Max, raceMi) else null
        val current = when {
            effort != null && vo2Sec != null -> (effort.second + vo2Sec) / 2
            effort != null -> effort.second
            vo2Sec != null -> vo2Sec
            else -> return null
        }
        val c = consistency.coerceIn(0.0, 1.0)
        val gain = min(MAX_GAIN, max(0, trainingWeeksLeft) * GAIN_PER_WEEK) * c
        val projected = (current * (1 - gain)).roundToInt()
        val sigma = projected * (0.03 + 0.0025 * max(0, weeksLeft))
        val p = phi((goalSec - projected) / sigma)
        val percent = ((p * 100 / 5).roundToInt() * 5).coerceIn(5, 95)
        return GoalOutlook(percent, outlookFor(percent), current, projected, goalSec, gain, c, effort?.first, effort?.second, vo2Sec, usedPace)
    }
}
