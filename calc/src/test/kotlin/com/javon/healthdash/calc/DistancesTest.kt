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

class DistancesTest {
    private val start = LocalDate.of(2026, 10, 19)
    private val base = PlanInputs(
        race = RaceType.Half, raceDate = LocalDate.of(2027, 1, 17), startDate = start, goalMin = 110, currentWeeklyMin = 0,
        days = setOf(TUESDAY, WEDNESDAY, THURSDAY, SATURDAY, SUNDAY), longDay = SUNDAY, strengthDays = setOf(MONDAY),
    )
    private val runner = RunFitness(20.0, 8.0, 600, 12, 30)

    @Test fun taperByDistance() {
        val r = TrainingRules()
        assertThat(r.taperWeeks(RaceType.FiveK)).isEqualTo(1)
        assertThat(r.taperWeeks(RaceType.TenMile)).isEqualTo(1)
        assertThat(r.taperWeeks(RaceType.Half)).isEqualTo(2)
        assertThat(r.taperWeeks(RaceType.Marathon)).isEqualTo(3)
        assertThat(r.taperWeeks(RaceType.FiftyK)).isEqualTo(3)
        assertThat(r.taperWeeks(RaceType.Ride50)).isEqualTo(1)
        assertThat(r.taperWeeks(RaceType.Century)).isEqualTo(2)
        assertThat(r.taperWeeks(RaceType.CustomRun, 8.0)).isEqualTo(1)
    }

    @Test fun peakLongByDistance() {
        assertThat(peakLongFor(FIVE_K_MI, false)).isEqualTo(7.0)
        assertThat(peakLongFor(HALF_MI, false)).isEqualTo(14.0)
        assertThat(peakLongFor(MARATHON_MI, false)).isEqualTo(20.0)
        assertThat(peakLongFor(50.0, false)).isEqualTo(26.0)
        assertThat(RidePlanBuilder.peakLongFor(25.0)).isEqualTo(25.0)
        assertThat(RidePlanBuilder.peakLongFor(100.0)).isEqualTo(80.0)
    }

    @Test fun halfMarathonPlan() {
        val (r, s) = RunPlanBuilder.generate(base, runner)
        val weeks = (r as PlanResult.Ok).weeks
        assertThat(s!!.peakLongMi).isEqualTo(14.0)
        // 1:50 half: 6600 s / 13.1094 mi = 503 s → 8:23/mi
        assertThat(s.goalPaces.race).isEqualTo(503)
        // Current fitness (easy 10:00 → ~1:50:00) is a hair slower than the goal, so week 1 starts there.
        assertThat(s.paces.race).isAtLeast(503)
        val race = weeks.last().sessions.single { it.kind == WorkoutKind.Race }
        assertThat(race.distanceMi).isWithin(1e-6).of(HALF_MI)
        assertThat(race.detail).startsWith("Half marathon · goal 1:50:00")
        assertThat(weeks.count { it.phase == Phase.Taper }).isEqualTo(2)
    }

    @Test fun fiveKAndCustomDistance() {
        val (r5k, s5k) = RunPlanBuilder.generate(base.copy(race = RaceType.FiveK, goalMin = null), runner)
        assertThat(r5k).isInstanceOf(PlanResult.Ok::class.java)
        assertThat(s5k!!.peakLongMi).isAtMost(7.0)
        // No race-pace long-run finishes for short races.
        assertThat((r5k as PlanResult.Ok).weeks.flatMap { it.sessions }.none { it.detail?.contains("goal pace") == true }).isTrue()
        val (rc, _) = RunPlanBuilder.generate(base.copy(race = RaceType.CustomRun, distanceMi = 8.0, goalMin = 70), runner)
        val race = (rc as PlanResult.Ok).weeks.last().sessions.single { it.kind == WorkoutKind.Race }
        assertThat(race.distanceMi).isEqualTo(8.0)
        assertThat(race.detail).startsWith("8.0-mile race")
    }

    @Test fun ultraHasBackToBacks() {
        val (r, s) = RunPlanBuilder.generate(base.copy(race = RaceType.FiftyK, raceDate = LocalDate.of(2027, 3, 14), goalMin = 330), runner)
        assertThat(s!!.peakLongMi).isEqualTo(22.0)
        assertThat((r as PlanResult.Ok).weeks.flatMap { it.sessions }.any { it.kind == WorkoutKind.BackToBack }).isTrue()
    }

    @Test fun centuryRidePlan() {
        val rider = RideFitness(weeklyMiles = 60.0, longestRideMi = 35.0, speedMph = 15.0, rides = 8, days = 30)
        val inputs = base.copy(race = RaceType.Century, raceDate = LocalDate.of(2027, 3, 14), goalMin = 400)
        val (r, s) = RidePlanBuilder.generate(inputs, rider)
        val weeks = (r as PlanResult.Ok).weeks
        // 100 mi in 6:40 → 15.0 mph; endurance 12.75–13.8 mph
        assertThat(s!!.speeds.race).isWithin(1e-9).of(15.0)
        assertThat(s.peakLongMi).isEqualTo(80.0)
        val longs = weeks.dropLast(1).map { w -> w.sessions.single { it.kind == WorkoutKind.Long } }
        var best = longs.first().distanceMi!!
        longs.drop(1).forEach { assertThat(it.distanceMi!! - best).isAtMost(10.0); best = maxOf(best, it.distanceMi!!) }
        // Duration = distance ÷ speed: an 80 mi long ride at ~13.3 mph ≈ 6 h.
        val peak = longs.first { it.distanceMi == 80.0 }
        assertThat(peak.durationMin.toDouble()).isWithin(5.0).of(80 / ((12.75 + 13.8) / 2) * 60)
        assertThat(weeks.last().sessions.single { it.kind == WorkoutKind.Race }.distanceMi).isEqualTo(100.0)
        assertThat(weeks.count { it.phase == Phase.Taper }).isEqualTo(2)
    }

    @Test fun rideFitnessFromRides() {
        val now = 30 * DAY_MS
        val rides = listOf(RideSample(now - DAY_MS, 7200, 30.0), RideSample(now - 3 * DAY_MS, 3600, 16.0), RideSample(now - 5 * DAY_MS, 5400, 20.0))
        val f = RideFitnessCalc.fromRides(rides, now, historyDays = 7)!!
        assertThat(f.weeklyMiles).isWithin(1e-9).of(66.0)
        assertThat(f.longestRideMi).isEqualTo(30.0)
        // Speeds 15, 16, 13.33 → median 15
        assertThat(f.speedMph).isWithin(1e-9).of(15.0)
    }
}
