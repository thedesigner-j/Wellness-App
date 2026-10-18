package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import org.junit.Test

class ProgressTest {
    private val start = LocalDate.of(2026, 10, 19) // Monday
    private val race = LocalDate.of(2027, 2, 7)
    private val inputs = PlanInputs(
        race = RaceType.Marathon, raceDate = race, startDate = start, goalMin = 225, currentWeeklyMin = 0,
        days = setOf(TUESDAY, WEDNESDAY, THURSDAY, SATURDAY, SUNDAY), longDay = SUNDAY, strengthDays = setOf(MONDAY),
    )
    private val sessions = ((RunPlanBuilder.generate(inputs, RunFitness(20.0, 8.0, 600, 12, 30)).first as PlanResult.Ok).weeks).flatMap { w ->
        w.sessions.mapIndexed { i, s -> ProgressSession(w.index * 100L + i, s.date, s.sport, s.kind, s.durationMin, s.distanceMi, w.index, w.phase) }
    }
    private fun doneFor(list: List<ProgressSession>) = list.filter { it.sport != Sport.Strength }.map { DoneActivity(it.date, it.sport, it.durationMin, it.distanceMi) }

    // Wednesday of week 3 (index 2).
    private val today = LocalDate.of(2026, 11, 4)

    @Test fun whereYouAreInThePlan() {
        val p = PlanProgress.compute(sessions, emptyList(), today, start, race)
        assertThat(p.weekIndex).isEqualTo(2)
        assertThat(p.totalWeeks).isEqualTo(16)
        assertThat(p.phase).isEqualTo(Phase.Base)
        assertThat(p.daysToRace).isEqualTo(95)
        assertThat(p.elapsed).isWithin(1e-9).of(16.0 / 111)
        assertThat(p.thisWeek.map { it.date.dayOfWeek }.first()).isEqualTo(MONDAY)
    }

    @Test fun allDoneIsOnTrack() {
        val past = sessions.filter { it.date.isBefore(today) }
        val p = PlanProgress.compute(sessions, doneFor(past), today, start, race)
        assertThat(p.verdict).isEqualTo(Verdict.OnTrack)
        assertThat(p.doneSoFar).isEqualTo(p.dueSoFar)
        // Tuesday of this week done; Wednesday is today; the rest upcoming.
        assertThat(p.thisWeek[1].mark).isEqualTo(DayMark.Done)
        assertThat(p.thisWeek[2].mark).isEqualTo(DayMark.Today)
        assertThat(p.thisWeek[6].mark).isEqualTo(DayMark.Upcoming)
        assertThat(p.lastLong!!.first.kind).isEqualTo(WorkoutKind.Long)
        assertThat(p.nextKey!!.date).isAtLeast(today)
        assertThat(p.reason).contains("Keep it up")
    }

    @Test fun missingSessionsAndLongRunsIsOffTrack() {
        val past = sessions.filter { it.date.isBefore(today) && it.kind == WorkoutKind.Easy }
        val p = PlanProgress.compute(sessions, doneFor(past), today, start, race)
        assertThat(p.verdict).isEqualTo(Verdict.OffTrack)
        assertThat(p.keyMissed14).isAtLeast(2)
        assertThat(p.thisWeek[1].mark).isAnyOf(DayMark.Missed, DayMark.Done)
        assertThat(p.reason).contains("key workouts missed")
    }

    @Test fun firstDayIsEarlyDays() {
        val p = PlanProgress.compute(sessions, emptyList(), start, start, race)
        assertThat(p.verdict).isEqualTo(Verdict.EarlyDays)
        assertThat(p.weekIndex).isEqualTo(0)
    }

    @Test fun ridesStandInForEasyRunsOnly() {
        assertThat(CrossTraining.counts(Sport.Run, WorkoutKind.Easy, 50, Sport.Ride, 50)).isTrue()
        assertThat(CrossTraining.counts(Sport.Run, WorkoutKind.Recovery, 30, Sport.Ride, 45)).isTrue()
        // A ride needs the full planned time; a run only half.
        assertThat(CrossTraining.counts(Sport.Run, WorkoutKind.Easy, 50, Sport.Ride, 40)).isFalse()
        assertThat(CrossTraining.counts(Sport.Run, WorkoutKind.Easy, 50, Sport.Run, 25)).isTrue()
        // Long runs and quality sessions stay run-only.
        assertThat(CrossTraining.counts(Sport.Run, WorkoutKind.Long, 90, Sport.Ride, 240)).isFalse()
        assertThat(CrossTraining.counts(Sport.Run, WorkoutKind.Tempo, 50, Sport.Ride, 90)).isFalse()
        assertThat(CrossTraining.counts(Sport.Run, WorkoutKind.Intervals, 50, Sport.Ride, 90)).isFalse()
        // Ride plans aren't completed by runs.
        assertThat(CrossTraining.counts(Sport.Ride, WorkoutKind.Easy, 60, Sport.Run, 60)).isFalse()
    }

    @Test fun rideOnAnEasyDayKeepsYouOnTrackAndAddsMiles() {
        val past = sessions.filter { it.date.isBefore(today) && it.sport != Sport.Strength }
        // Every easy run in the last 2 weeks replaced by a ride of the same length.
        val done = past.map { s -> if (s.kind == WorkoutKind.Easy) DoneActivity(s.date, Sport.Ride, s.durationMin, s.durationMin / 60.0 * 15) else DoneActivity(s.date, s.sport, s.durationMin, s.distanceMi) }
        val p = PlanProgress.compute(sessions, done, today, start, race, easyPaceSec = 600)
        assertThat(p.verdict).isEqualTo(Verdict.OnTrack)
        assertThat(p.doneSoFar).isEqualTo(p.dueSoFar)
        // Week 1 miles: rides add 40% of ride time at 10:00/mi.
        val w0 = p.weeks.first()
        val rideMin = past.filter { it.week == 0 && it.kind == WorkoutKind.Easy }.sumOf { it.durationMin }
        assertThat(w0.rideCreditMi).isWithin(1e-9).of(minOf(rideMin * 0.4 / 10, maxOf(w0.doneMi, 10.0)))
        assertThat(w0.rideCreditMi).isGreaterThan(0.0)
    }

    @Test fun rideInsteadOfLongRunIsMissed() {
        val past = sessions.filter { it.date.isBefore(today) && it.sport != Sport.Strength }
        val done = past.map { s -> if (s.kind == WorkoutKind.Long) DoneActivity(s.date, Sport.Ride, 240, 60.0) else DoneActivity(s.date, s.sport, s.durationMin, s.distanceMi) }
        val p = PlanProgress.compute(sessions, done, today, start, race)
        assertThat(p.keyMissed14).isAtLeast(1)
        assertThat(p.lastLong).isNull()
    }

    @Test fun runsBeforeThePlanStartDontCount() {
        // Plan created on a Sunday: Mon–Sat runs that week happened before the plan.
        val sunday = LocalDate.of(2026, 10, 18)
        val before = listOf(DoneActivity(sunday.minusDays(3), Sport.Run, 60, 6.6))
        val p = PlanProgress.compute(sessions, before, sunday, sunday, race)
        assertThat(p.weekDoneMi).isEqualTo(0.0)
    }

    @Test fun weeklyMilesPlannedVsDone() {
        val past = sessions.filter { it.date.isBefore(today) }
        val p = PlanProgress.compute(sessions, doneFor(past), today, start, race)
        val w0 = p.weeks.first()
        assertThat(w0.doneMi).isWithin(1e-6).of(w0.plannedMi)
        assertThat(p.weeks[5].doneMi).isEqualTo(0.0)
        assertThat(p.weeks).hasSize(16)
    }
}
