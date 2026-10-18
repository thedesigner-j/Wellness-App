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

    @Test fun weeklyMilesPlannedVsDone() {
        val past = sessions.filter { it.date.isBefore(today) }
        val p = PlanProgress.compute(sessions, doneFor(past), today, start, race)
        val w0 = p.weeks.first()
        assertThat(w0.doneMi).isWithin(1e-6).of(w0.plannedMi)
        assertThat(p.weeks[5].doneMi).isEqualTo(0.0)
        assertThat(p.weeks).hasSize(16)
    }
}
