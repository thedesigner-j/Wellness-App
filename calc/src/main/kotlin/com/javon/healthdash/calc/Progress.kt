package com.javon.healthdash.calc

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Where you are in a race plan and whether you're on track. Fixed rules, no AI.

/** A planned session as stored, with its plan week and phase. */
data class ProgressSession(
    val id: Long,
    val date: LocalDate,
    val sport: Sport,
    val kind: WorkoutKind,
    val durationMin: Int,
    val distanceMi: Double?,
    val week: Int,
    val phase: Phase,
    /** Planned, Dropped, Moved, Swapped, Missed. */
    val status: String = "Planned",
)

/** A finished workout from Garmin / Health Connect. */
data class DoneActivity(val date: LocalDate, val sport: Sport?, val durationMin: Int, val distanceMi: Double?)

enum class DayMark { Done, Missed, Today, Upcoming, Rest }

enum class Verdict(val label: String) { EarlyDays("Just started"), OnTrack("On track"), SlightlyBehind("Slightly behind"), OffTrack("Off track"), Finished("Plan complete") }

data class WeekDay(val date: LocalDate, val mark: DayMark, val sessions: List<ProgressSession>)

data class WeekTotals(val week: Int, val monday: LocalDate, val phase: Phase, val plannedMi: Double, val doneMi: Double, val plannedMin: Int, val doneMin: Int, val rideCreditMi: Double = 0.0)

data class PlanStatus(
    val weekIndex: Int,
    val totalWeeks: Int,
    val phase: Phase,
    val daysToRace: Int,
    /** Share of the plan's days already behind you, 0–1. */
    val elapsed: Double,
    /** Endurance sessions due before today, and how many of them were done. */
    val dueSoFar: Int,
    val doneSoFar: Int,
    /** The same over the last 14 days — drives the verdict. */
    val due14: Int,
    val done14: Int,
    val keyMissed14: Int,
    val verdict: Verdict,
    val reason: String,
    val thisWeek: List<WeekDay>,
    val weekPlannedMi: Double,
    val weekDoneMi: Double,
    /** Run-equivalent miles from rides this week (run plans only). */
    val weekRideCreditMi: Double,
    val weekSessions: Int,
    val weekSessionsDone: Int,
    val weeks: List<WeekTotals>,
    val nextKey: ProgressSession?,
    val lastLong: Pair<ProgressSession, DoneActivity>?,
    val sport: Sport,
)

object PlanProgress {
    val KEY = setOf(WorkoutKind.Long, WorkoutKind.Tempo, WorkoutKind.Intervals, WorkoutKind.BackToBack, WorkoutKind.Race)

    private fun endurance(s: ProgressSession) = s.sport != Sport.Strength && s.status != "Dropped"

    /** A workout that day that completes the session per [CrossTraining.counts] (rides can stand in for easy runs). */
    fun match(s: ProgressSession, done: List<DoneActivity>): DoneActivity? =
        done.firstOrNull { d -> d.date == s.date && CrossTraining.counts(s.sport, s.kind, s.durationMin, d.sport, d.durationMin) }

    /** Run-equivalent miles from rides: ride minutes × 0.4 ÷ easy pace, capped at max(run miles, 10) — as [Fitness.fromActivities]. */
    fun rideCredit(rideMin: Int, runMi: Double, easyPaceSec: Int): Double =
        min(rideMin * Fitness.RIDE_TO_RUN_TIME * 60 / easyPaceSec, max(runMi, 10.0))

    fun compute(
        sessions: List<ProgressSession>, done: List<DoneActivity>, today: LocalDate, startDate: LocalDate, raceDate: LocalDate,
        easyPaceSec: Int = Fitness.DEFAULT_EASY_PACE_SEC,
    ): PlanStatus {
        val sport = sessions.filter { it.sport != Sport.Strength }.groupingBy { it.sport }.eachCount().maxByOrNull { it.value }?.key ?: Sport.Run
        val firstMonday = PlanBuilder.monday(startDate)
        val totalWeeks = (sessions.maxOfOrNull { it.week } ?: 0) + 1
        val weekIndex = ChronoUnit.WEEKS.between(firstMonday, PlanBuilder.monday(today)).toInt().coerceIn(0, totalWeeks - 1)
        val phase = sessions.firstOrNull { it.week == weekIndex }?.phase ?: Phase.Base
        val totalDays = ChronoUnit.DAYS.between(startDate, raceDate).coerceAtLeast(1)
        val elapsed = (ChronoUnit.DAYS.between(startDate, today).toDouble() / totalDays).coerceIn(0.0, 1.0)

        val endur = sessions.filter(::endurance)
        val due = endur.filter { it.date.isBefore(today) && !it.date.isBefore(startDate) }
        val doneSet = due.filter { match(it, done) != null }
        val since = today.minusDays(14)
        val due14 = due.filter { !it.date.isBefore(since) }
        val done14 = due14.filter { match(it, done) != null }
        val keyMissed14 = due14.count { it.kind in KEY && match(it, done) == null }

        val finished = today.isAfter(raceDate)
        val verdict = when {
            finished -> Verdict.Finished
            due14.size < 3 -> Verdict.EarlyDays
            done14.size >= due14.size * 0.8 && keyMissed14 <= 1 -> Verdict.OnTrack
            done14.size >= due14.size * 0.6 && keyMissed14 <= 2 -> Verdict.SlightlyBehind
            else -> Verdict.OffTrack
        }
        val reason = when (verdict) {
            Verdict.Finished -> "Race day has passed. ${doneSet.size} of ${due.size} sessions done over the plan."
            Verdict.EarlyDays -> if (due.isEmpty()) "No sessions due yet — the first one is ${endur.firstOrNull { !it.date.isBefore(today) }?.date?.let { "on ${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}" } ?: "coming up"}."
                else "${doneSet.size} of ${due.size} sessions done so far. A verdict needs about a week of sessions."
            else -> buildString {
                append("${done14.size} of ${due14.size} sessions done in the last 2 weeks (${(done14.size * 100.0 / due14.size).roundToInt()}%)")
                if (keyMissed14 > 0) append(", $keyMissed14 key ${if (keyMissed14 == 1) "workout" else "workouts"} missed")
                append(".")
                when (verdict) {
                    Verdict.OnTrack -> append(" Keep it up.")
                    Verdict.SlightlyBehind -> append(" Prioritise the long and quality sessions this week; skip, don't stack, missed easy runs.")
                    else -> append(" Don't try to make up missed miles — rejoin the plan this week and re-check your goal time.")
                }
            }
        }

        val monday = PlanBuilder.monday(today)
        val thisWeek = (0..6).map { i ->
            val d = monday.plusDays(i.toLong())
            val list = sessions.filter { it.date == d && it.status != "Dropped" }
            val e = list.filter(::endurance)
            val mark = when {
                list.isEmpty() -> DayMark.Rest
                e.isNotEmpty() && e.all { match(it, done) != null } -> DayMark.Done
                e.isEmpty() && d.isBefore(today) -> DayMark.Done
                d == today -> DayMark.Today
                d.isBefore(today) -> DayMark.Missed
                else -> DayMark.Upcoming
            }
            WeekDay(d, mark, list)
        }
        fun miles(from: LocalDate, to: LocalDate) = done.filter { it.sport == sport && !it.date.isBefore(maxOf(from, startDate)) && !it.date.isAfter(to) }.sumOf { it.distanceMi ?: 0.0 }
        fun minutes(from: LocalDate, to: LocalDate) = done.filter { it.sport == sport && !it.date.isBefore(maxOf(from, startDate)) && !it.date.isAfter(to) }.sumOf { it.durationMin }
        fun credit(from: LocalDate, to: LocalDate): Double {
            if (sport != Sport.Run) return 0.0
            val rideMin = done.filter { it.sport == Sport.Ride && !it.date.isBefore(maxOf(from, startDate)) && !it.date.isAfter(to) }.sumOf { it.durationMin }
            return rideCredit(rideMin, miles(from, to), easyPaceSec)
        }
        val weekEndur = endur.filter { PlanBuilder.monday(it.date) == monday && it.kind != WorkoutKind.Race }
        val weeks = endur.groupBy { it.week }.toSortedMap().map { (w, l) ->
            val m = firstMonday.plusWeeks(w.toLong())
            val past = !m.isAfter(today)
            val plain = l.filter { it.kind != WorkoutKind.Race && it.sport == sport }
            WeekTotals(w, m, l.first().phase, plain.sumOf { it.distanceMi ?: 0.0 }, if (past) miles(m, m.plusDays(6)) else 0.0, plain.sumOf { it.durationMin }, if (past) minutes(m, m.plusDays(6)) else 0, if (past) credit(m, m.plusDays(6)) else 0.0)
        }
        val lastLong = due.filter { it.kind == WorkoutKind.Long }.sortedByDescending { it.date }.firstNotNullOfOrNull { s -> match(s, done)?.let { s to it } }
        return PlanStatus(
            weekIndex, totalWeeks, phase, ChronoUnit.DAYS.between(today, raceDate).toInt().coerceAtLeast(0), elapsed,
            due.size, doneSet.size, due14.size, done14.size, keyMissed14, verdict, reason,
            thisWeek, weekEndur.filter { it.sport == sport }.sumOf { it.distanceMi ?: 0.0 }, miles(monday, monday.plusDays(6)), credit(monday, monday.plusDays(6)),
            weekEndur.size, weekEndur.count { match(it, done) != null },
            weeks, endur.filter { !it.date.isBefore(today) && it.kind in KEY && match(it, done) == null }.minByOrNull { it.date }, lastLong, sport,
        )
    }
}
