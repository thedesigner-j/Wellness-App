package com.javon.healthdash.calc

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

// Post-session feedback, effort-based plan easing and rescheduling checks. Fixed rules, no AI.

enum class PaceCheck(val label: String) { OnTarget("On target"), TooFast("Faster than planned"), TooSlow("Slower than planned"), NoData("No pace data") }

enum class EffortBand(val range: IntRange, val label: String) {
    Easy(1..3, "Easy"), Moderate(4..6, "Moderate"), Hard(7..8, "Hard"), AllOut(9..10, "All out");

    companion object { fun of(rpe: Int) = entries.first { rpe in it.range } }
}

data class SessionReview(
    /** Done ÷ planned distance (or time when there's no distance). */
    val completion: Double?,
    /** Runs: sec/mi. Rides: mph × 10 (one decimal, as an Int). */
    val plannedPace: Int?,
    val actualPace: Int?,
    val pace: PaceCheck,
    /** 1–5, from average HR. */
    val hrZone: Int?,
    val hrHigh: Boolean,
    val headline: String,
    val notes: List<String>,
)

object Feedback {
    private val EASY = setOf(WorkoutKind.Easy, WorkoutKind.Recovery, WorkoutKind.ClubEasy, WorkoutKind.DoubleDay)
    private val STEADY = setOf(WorkoutKind.Long, WorkoutKind.BackToBack, WorkoutKind.ClubSteady)
    private val QUALITY = setOf(WorkoutKind.Tempo, WorkoutKind.Intervals, WorkoutKind.Race, WorkoutKind.ClubHard)

    /** Highest average-HR zone that fits the session (averages include warm-ups and recoveries). */
    fun maxZone(kind: WorkoutKind) = when (kind) { in EASY -> 2; in STEADY -> 3; WorkoutKind.Tempo -> 4; else -> 5 }

    /** Effort (RPE 1–10) a session is meant to feel like at most. */
    fun expectedRpe(kind: WorkoutKind) = when (kind) { in EASY -> 4; in STEADY -> 6; WorkoutKind.Race -> 10; else -> 8 }

    fun zoneOf(avgHr: Double, zones: List<HrZone>): Int? {
        if (zones.size < 5) return null
        if (avgHr < zones[0].low) return 1
        return (zones.indexOfLast { avgHr >= it.low } + 1).coerceIn(1, 5)
    }

    fun paceCheck(kind: WorkoutKind, sport: Sport, plannedMin: Int, plannedMi: Double?, doneMin: Int, doneMi: Double?): Triple<Int?, Int?, PaceCheck> {
        if (plannedMi == null || plannedMi <= 0 || doneMi == null || doneMi <= 0.2 || doneMin <= 0) return Triple(null, null, PaceCheck.NoData)
        return if (sport == Sport.Ride) {
            val planned = plannedMi / (plannedMin / 60.0); val actual = doneMi / (doneMin / 60.0)
            val check = when {
                actual > planned * 1.08 && kind !in QUALITY -> PaceCheck.TooFast
                actual < planned * 0.92 -> PaceCheck.TooSlow
                else -> PaceCheck.OnTarget
            }
            Triple((planned * 10).roundToInt(), (actual * 10).roundToInt(), check)
        } else {
            val planned = (plannedMin * 60 / plannedMi).roundToInt(); val actual = (doneMin * 60 / doneMi).roundToInt()
            val check = if (kind in QUALITY) when {
                actual < planned * 0.96 -> PaceCheck.TooFast
                actual > planned * 1.04 -> PaceCheck.TooSlow
                else -> PaceCheck.OnTarget
            } else when {
                // Easy days: running slower is fine; much faster defeats the purpose.
                actual < planned - 20 -> PaceCheck.TooFast
                actual > planned + 45 -> PaceCheck.TooSlow
                else -> PaceCheck.OnTarget
            }
            Triple(planned, actual, check)
        }
    }

    fun review(kind: WorkoutKind, sport: Sport, plannedMin: Int, plannedMi: Double?, doneMin: Int, doneMi: Double?, avgHr: Double?, zones: List<HrZone>): SessionReview {
        val completion = when {
            plannedMi != null && plannedMi > 0 && doneMi != null -> doneMi / plannedMi
            plannedMin > 0 -> doneMin.toDouble() / plannedMin
            else -> null
        }
        val (pp, ap, pace) = paceCheck(kind, sport, plannedMin, plannedMi, doneMin, doneMi)
        val zone = avgHr?.let { zoneOf(it, zones) }
        val hrHigh = zone != null && zone > maxZone(kind)
        val notes = mutableListOf<String>()
        if (completion != null && completion < 0.85) notes += "Cut short at ${(completion * 100).roundToInt()}% of the plan — fine once; if it keeps happening the plan may be too much."
        if (completion != null && completion > 1.2 && kind != WorkoutKind.Race) notes += "Went ${(completion * 100 - 100).roundToInt()}% longer than planned — extra volume adds fatigue; stick to the plan."
        when (pace) {
            PaceCheck.TooFast -> notes += if (kind in QUALITY) "Faster than target — great if it felt controlled, but it can cost you the next session." else "Too quick for an ${if (kind == WorkoutKind.Long) "long" else "easy"} day. Slow down: easy miles build endurance without the fatigue."
            PaceCheck.TooSlow -> notes += if (kind in QUALITY) "Slower than target. Heat, hills, sleep or fatigue? If it happens twice, ease off." else "Slower than planned — fine for an easy day."
            else -> {}
        }
        if (hrHigh) notes += "Average heart rate was Z$zone — high for this session (aim for Z${maxZone(kind)} or below)."
        val headline = when {
            completion != null && completion < 0.85 -> "Partly done"
            pace == PaceCheck.TooFast && kind !in QUALITY -> "Done — but too fast"
            hrHigh -> "Done — effort ran high"
            pace == PaceCheck.TooSlow && kind in QUALITY -> "Done — slower than target"
            pace == PaceCheck.OnTarget -> "Nailed it"
            else -> "Done"
        }
        return SessionReview(completion, pp, ap, pace, zone, hrHigh, headline, notes)
    }
}

/** A completed session with the effort the runner reported. */
data class RatedSession(val date: LocalDate, val kind: WorkoutKind, val rpe: Int, val pace: PaceCheck = PaceCheck.NoData)

enum class SuggestionKind { Ease, GoalCheck }

data class PlanSuggestion(val kind: SuggestionKind, val factor: Double, val title: String, val reason: String)

object Adaptation {
    const val EASE_FACTOR = 0.9

    /**
     * Last 3 rated sessions (within 14 days):
     * - 2+ felt 2 or more points harder than intended → ease next week's runs by 10%;
     * - 3 quality sessions in a row felt ≤ 5 and weren't slow → suggest a faster goal (no automatic change).
     */
    fun suggest(rated: List<RatedSession>, today: LocalDate): PlanSuggestion? {
        val recent = rated.filter { !it.date.isBefore(today.minusDays(14)) && !it.date.isAfter(today) }.sortedBy { it.date }
        val last3 = recent.takeLast(3)
        if (last3.size == 3) {
            val hard = last3.count { it.rpe >= Feedback.expectedRpe(it.kind) + 2 }
            if (hard >= 2) return PlanSuggestion(
                SuggestionKind.Ease, EASE_FACTOR, "Sessions are feeling too hard",
                "$hard of your last 3 sessions felt harder than they should (" + last3.joinToString { "${it.kind.label.lowercase()} ${it.rpe}/10" } + "). Easing the next 7 days by 10% lets you absorb the training.",
            )
        }
        val quality = recent.filter { it.kind == WorkoutKind.Tempo || it.kind == WorkoutKind.Intervals }.takeLast(3)
        if (quality.size == 3 && quality.all { it.rpe <= 5 && it.pace != PaceCheck.TooSlow }) return PlanSuggestion(
            SuggestionKind.GoalCheck, 1.0, "Workouts are feeling easy",
            "Your last 3 quality sessions felt ${quality.joinToString("/") { "${it.rpe}" }} out of 10. You may be ready for a faster goal — check the goal outlook before changing it.",
        )
        return null
    }

    /** Eased distance, rounded to 0.5 mi (min 1 mi). */
    fun ease(mi: Double, factor: Double = EASE_FACTOR) = maxOf(1.0, (mi * factor * 2).roundToInt() / 2.0)
}

object Reschedule {
    private fun hard(s: PlannedSession) = s.kind.intensity == Intensity.Hard || s.kind == WorkoutKind.Long || s.kind == WorkoutKind.Race

    /** Rule warnings for moving [s] to [to] given the other sessions; empty = fine. */
    fun warnings(s: PlannedSession, to: LocalDate, others: List<PlannedSession>): List<String> {
        val rest = others.filter { it !== s && !(it.date == s.date && it.kind == s.kind && it.sport == s.sport) }
        val out = mutableListOf<String>()
        fun name(d: LocalDate) = d.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)
        if (s.sport != Sport.Strength && rest.any { it.date == to && it.sport != Sport.Strength }) out += "There's already a session on ${name(to)} — two in one day."
        if (hard(s)) rest.filter { hard(it) && abs(it.date.toEpochDay() - to.toEpochDay()) == 1L }.forEach {
            out += "${name(to)} would be next to ${name(it.date)}'s ${it.kind.label.lowercase()} — hard days back to back raise injury risk."
        }
        return out
    }
}
