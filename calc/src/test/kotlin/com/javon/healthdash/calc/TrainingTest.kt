package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.Test

class TrainingTest {

    private val start = LocalDate.of(2026, 10, 19) // Monday
    private val marathon = PlanInputs(
        race = RaceType.Marathon, raceDate = LocalDate.of(2027, 1, 17), startDate = start, goalMin = 225,
        currentWeeklyMin = 300, days = setOf(TUESDAY, WEDNESDAY, THURSDAY, SATURDAY, SUNDAY), longDay = SUNDAY,
        strengthDays = setOf(MONDAY, THURSDAY),
    )

    @Test fun phaseSplit() {
        // 12 weeks, 3 taper: race 1, peak 2, rest 6 → base round(2.4)=2, build 4
        assertThat(PlanBuilder.phases(12, 3)).containsExactly(
            Phase.Base, Phase.Base, Phase.Build, Phase.Build, Phase.Build, Phase.Build,
            Phase.Peak, Phase.Peak, Phase.Taper, Phase.Taper, Phase.Taper, Phase.Race,
        ).inOrder()
    }

    @Test fun handCheckedVolumes() {
        val v = PlanBuilder.volumes(PlanBuilder.phases(12, 3), 300, RaceType.Marathon, TrainingRules())
        // 300 → 330 → 363 → lighter 272 → 399 → 439 → capped at 480 → lighter 360 → taper 360, 288, 216 → race 168
        assertThat(v.map { it.first }).containsExactly(300, 330, 363, 272, 399, 439, 480, 360, 360, 288, 216, 168).inOrder()
        assertThat(v.map { it.second }).containsExactly(false, false, false, true, false, false, false, true, false, false, false, false).inOrder()
    }

    @Test fun fullWeeksNeverGrowMoreThanTenPercent() {
        val v = PlanBuilder.volumes(PlanBuilder.phases(16, 3), 200, RaceType.Bike80, TrainingRules()).filter { !it.second }.map { it.first }
        v.zipWithNext().forEach { (a, b) -> assertThat(b.toDouble()).isAtMost(a * 1.10 + 1) }
    }

    @Test fun hardDaysAreSeparated() {
        val all = DayOfWeek.entries.toSet()
        assertThat(PlanBuilder.hardDays(all - SATURDAY, SATURDAY, 2)).containsExactly(TUESDAY, THURSDAY).inOrder()
        // Long on Sunday: Monday and Saturday touch it, Wednesday touches Tuesday.
        assertThat(PlanBuilder.hardDays(setOf(MONDAY, TUESDAY, WEDNESDAY, SATURDAY), SUNDAY, 2)).containsExactly(TUESDAY)
    }

    @Test fun marathonPlanFollowsRules() {
        val weeks = (PlanBuilder.generate(marathon) as PlanResult.Ok).weeks
        assertThat(weeks).hasSize(13)
        assertThat(weeks.last().phase).isEqualTo(Phase.Race)
        assertThat(weeks.last().sessions.single { it.kind == WorkoutKind.Race }.date).isEqualTo(marathon.raceDate)
        val sessions = weeks.flatMap { it.sessions }
        // Hard and long days never touch each other.
        val keyDays = sessions.filter { it.intensity == Intensity.Hard && it.kind != WorkoutKind.Race || it.kind == WorkoutKind.Long }.map { it.date }.sorted()
        keyDays.zipWithNext().forEach { (a, b) -> assertThat(ChronoUnit.DAYS.between(a, b)).isAtLeast(2L) }
        // Long sessions on Sunday and growing by at most 20 min between full weeks.
        val longs = weeks.filter { !it.lighter && it.phase !in setOf(Phase.Taper, Phase.Race) }.map { w -> w.sessions.single { it.kind == WorkoutKind.Long } }
        longs.forEach { assertThat(it.date.dayOfWeek).isEqualTo(SUNDAY) }
        longs.zipWithNext().forEach { (a, b) -> assertThat(b.durationMin - a.durationMin).isAtMost(20) }
        // Strength twice a week, but not in the last taper week or race week.
        assertThat(weeks[0].sessions.count { it.sport == Sport.Strength }).isEqualTo(2)
        assertThat(weeks[weeks.size - 2].sessions.none { it.sport == Sport.Strength }).isTrue()
        // Sessions only on chosen days (race day excepted).
        sessions.filter { it.kind != WorkoutKind.Race }.forEach { assertThat(it.date.dayOfWeek).isIn(marathon.days + marathon.strengthDays) }
    }

    @Test fun relayPeakHasBackToBackAndDoubleDay() {
        val relay = marathon.copy(race = RaceType.Relay200, raceDate = LocalDate.of(2027, 1, 17), longDay = SATURDAY)
        val weeks = (PlanBuilder.generate(relay) as PlanResult.Ok).weeks
        val peak = weeks.filter { it.phase == Phase.Peak }.flatMap { it.sessions }
        assertThat(peak.map { it.kind }).containsAtLeast(WorkoutKind.BackToBack, WorkoutKind.DoubleDay)
        assertThat(weeks.count { it.phase == Phase.Taper }).isEqualTo(2)
    }

    @Test fun invalidPlans() {
        assertThat(PlanBuilder.generate(marathon.copy(raceDate = start.plusWeeks(3)))).isInstanceOf(PlanResult.Invalid::class.java)
        assertThat(PlanBuilder.generate(marathon.copy(days = setOf(SUNDAY, SATURDAY)))).isInstanceOf(PlanResult.Invalid::class.java)
    }

    @Test fun chainedStartAddsRecoveryWeeks() {
        // Race Sun Jan 17 → week of Jan 11; +1 week +2 recovery → Mon Feb 1
        assertThat(PlanBuilder.nextStart(LocalDate.of(2027, 1, 17))).isEqualTo(LocalDate.of(2027, 2, 1))
    }

    @Test fun readinessSwap() {
        val tempo = PlannedSession(start, Sport.Run, WorkoutKind.Tempo, 60)
        val a = Adjustments.readiness(tempo, sleepMin = 480, hrv = 41.0, hrvBaseline = 52.0)!!
        assertThat(a.kind).isEqualTo(AdjustmentKind.SwapToEasy)
        assertThat(a.to!!.kind).isEqualTo(WorkoutKind.Easy)
        assertThat(a.to!!.durationMin).isEqualTo(45)
        assertThat(a.reason).contains("HRV 41 ms is below your 52 ms baseline")
        assertThat(Adjustments.readiness(tempo, sleepMin = 350, hrv = null, hrvBaseline = null)!!.reason).isEqualTo("Sleep was 5 h 50 m")
        assertThat(Adjustments.readiness(tempo, 480, 55.0, 52.0)).isNull()
        assertThat(Adjustments.readiness(tempo.copy(kind = WorkoutKind.Easy), 300, 30.0, 52.0)).isNull()
    }

    @Test fun missedSessions() {
        val tue = start.plusDays(1)
        fun s(d: Long, k: WorkoutKind) = PlannedSession(tue.plusDays(d), Sport.Run, k, 45)
        assertThat(Adjustments.missed(s(0, WorkoutKind.Easy), emptyList()).kind).isEqualTo(AdjustmentKind.Dropped)
        // Another hard session on Thursday → drop.
        assertThat(Adjustments.missed(s(0, WorkoutKind.Tempo), listOf(s(1, WorkoutKind.Easy), s(2, WorkoutKind.Intervals))).kind).isEqualTo(AdjustmentKind.Dropped)
        // Free easy Wednesday → move there.
        val moved = Adjustments.missed(s(0, WorkoutKind.Tempo), listOf(s(1, WorkoutKind.Easy), s(3, WorkoutKind.Easy), s(5, WorkoutKind.Long)))
        assertThat(moved.kind).isEqualTo(AdjustmentKind.Moved)
        assertThat(moved.to!!.date).isEqualTo(tue.plusDays(1))
        // Long: next easy day has a hard session next to it → drop.
        assertThat(Adjustments.missed(s(0, WorkoutKind.Long), listOf(s(1, WorkoutKind.Easy), s(2, WorkoutKind.Tempo))).kind).isEqualTo(AdjustmentKind.Dropped)
    }

    @Test fun fuelTable() {
        val t = FuelTable.DEFAULT
        assertThat(t.note(45, Intensity.Easy)).isEqualTo("Under 60 min: water only.")
        assertThat(t.note(140, Intensity.Moderate)).isEqualTo("Fuel 60–80 g carbs/h after the first 45 min.")
        // 144 min: (144 − 45) / 60 = 1.65 h × 60–80 g = 99–132 g
        assertThat(t.total(144, Intensity.Moderate)).isEqualTo(FuelRange(99, 132))
        assertThat(t.total(50, Intensity.Hard)).isEqualTo(FuelRange(25, 25))
    }

    @Test fun fuelReview() {
        val w = TimeWindow(0, 144 * MINUTE_MS)
        val pts = (0 until 29).map { GlucosePoint(it * 5 * MINUTE_MS, if (it == 10) 68.0 else 110.0) }
        val r = Fueling.review(w, Intensity.Moderate, 150.0, pts)
        assertThat(r.status).isEqualTo(FuelStatus.Within) // 150 ≤ 132 × 1.15
        assertThat(r.lowest).isEqualTo(68.0)
        assertThat(r.lowsBelow70).isEqualTo(1)
        assertThat(Fueling.review(w, Intensity.Moderate, 90.0, pts).status).isEqualTo(FuelStatus.Below)
    }

    @Test fun heartRateZones() {
        // Reserve 140: Z2 = 50 + 0.6×140 … 50 + 0.7×140 = 134–148
        val z = Zones.heartRate(50.0, 190.0)
        assertThat(z[1]).isEqualTo(HrZone("Z2", 134, 148))
        assertThat(Zones.forKind(WorkoutKind.Long, z)).isEqualTo("Z2 · HR 134–148")
    }

    @Test fun completionMatching() {
        val zone = ZoneOffset.UTC
        val day = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val planned = PlannedSession(start, Sport.Run, WorkoutKind.Easy, 50)
        val done = listOf(DoneWorkout(TimeWindow(day + 7 * HOUR_MS, day + 7 * HOUR_MS + 30 * MINUTE_MS), Sport.Run))
        assertThat(Completion.matches(planned, done, zone)).isNotNull()
        assertThat(Completion.matches(planned.copy(durationMin = 90), done, zone)).isNull()
        assertThat(Completion.matches(planned.copy(sport = Sport.Ride), done, zone)).isNull()
        assertThat(Completion.weeklyMinutes(done, zone)[start]).isEqualTo(30)
    }
}
