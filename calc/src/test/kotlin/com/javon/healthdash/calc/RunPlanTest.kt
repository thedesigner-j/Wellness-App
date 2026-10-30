package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.DayOfWeek.MONDAY
import java.time.LocalDate
import kotlin.math.max
import org.junit.Test

class RunPlanTest {

    private val start = LocalDate.of(2026, 10, 19) // Monday
    private val race = LocalDate.of(2027, 2, 7)    // Sunday, 16 weeks incl. race week
    private val fitness = RunFitness(weeklyMiles = 20.0, longestRunMi = 8.0, easyPaceSec = 600, runs = 12, days = 30)
    private val inputs = PlanInputs(
        race = RaceType.Marathon, raceDate = race, startDate = start, goalMin = 225, currentWeeklyMin = 0,
        days = setOf(TUESDAY, WEDNESDAY, THURSDAY, SATURDAY, SUNDAY), longDay = SUNDAY, strengthDays = setOf(MONDAY),
    )

    private fun build(i: PlanInputs = inputs, f: RunFitness = fitness) = RunPlanBuilder.generate(i, f)

    @Test fun pacesFromGoalTime() {
        val p = PaceMath.forGoal(MARATHON_MI, 225 * 60)
        // 13500 s / 26.2188 mi = 514.9 → 8:35/mi
        assertThat(p.race).isEqualTo(515)
        // Half equivalent: 13500 × 0.5^1.06 = 6475 s / 13.1094 = 494 → 8:14/mi
        assertThat(p.tempo).isWithin(1).of(494)
        // 5K equivalent: 13500 × (3.10686/26.2188)^1.06 = 1407.6 s / 3.10686 = 453 → 7:33/mi
        assertThat(p.interval).isWithin(1).of(453)
        assertThat(p.easy).isEqualTo(575..605)
        assertThat(PaceMath.fmt(p.race)).isEqualTo("8:35")
    }

    @Test fun fitnessFromRuns() {
        val now = 30 * DAY_MS
        val runs = listOf(RunSample(now - DAY_MS, 5.0, 3000), RunSample(now - 3 * DAY_MS, 8.0, 5100), RunSample(now - 5 * DAY_MS, 6.0, 3600))
        val f = Fitness.fromRuns(runs, now, historyDays = 7)!!
        assertThat(f.weeklyMiles).isWithin(1e-9).of(19.0)
        assertThat(f.longestRunMi).isEqualTo(8.0)
        assertThat(f.easyPaceSec).isEqualTo(600) // median of 600, 600, 637.5
        assertThat(Fitness.fromRuns(emptyList(), now)).isNull()
    }

    @Test fun longRunBuildsToTwentyThreeWeeksOut() {
        val (r, s) = build()
        val weeks = (r as PlanResult.Ok).weeks
        assertThat(weeks).hasSize(16)
        val longs = weeks.dropLast(1).map { w -> w.sessions.single { it.kind == WorkoutKind.Long } }
        assertThat(longs.first().distanceMi).isEqualTo(8.0)
        assertThat(s!!.peakLongMi).isEqualTo(20.0)
        // Peak 20 mi in the last peak week (index 11): 3 taper weeks then race week follow.
        assertThat(longs[11].distanceMi).isEqualTo(20.0)
        assertThat(weeks[12].phase).isEqualTo(Phase.Taper)
        // Never more than +2 mi over the longest run so far (cutback weeks step back, then resume).
        var best = longs.first().distanceMi!!
        longs.drop(1).forEach { assertThat(it.distanceMi!! - best).isAtMost(2.0); best = max(best, it.distanceMi!!) }
        // Taper long runs step down.
        assertThat(longs[12].distanceMi!!).isLessThan(20.0)
        assertThat(longs[14].distanceMi!!).isLessThan(longs[13].distanceMi!!)
        // Every long run states its distance and pace.
        longs.forEach { assertThat(it.detail).contains("mi") }
    }

    @Test fun weeklyMileageGrowsSafely() {
        val weeks = (build().first as PlanResult.Ok).weeks
        val full = weeks.filter { !it.lighter && it.phase in setOf(Phase.Base, Phase.Build, Phase.Peak) }.map { RunPlanBuilder.weekMiles(it) }
        full.zipWithNext().forEach { (a, b) -> assertThat(b).isAtMost(a * 1.10 + 2.5) } // + rounding of 0.5 mi per run
        assertThat(full.max()).isAtLeast(40.0)
    }

    @Test fun workoutsHaveStructureAndGoalPaces() {
        val sessions = (build().first as PlanResult.Ok).weeks.flatMap { it.sessions }
        // Fitness (easy 10:00 → ~3:49) is behind the 3:45 goal: quality paces start there and reach goal pace by the peak.
        val tempo = sessions.filter { it.kind == WorkoutKind.Tempo }
        assertThat(tempo.first().detail).doesNotContain("@ 8:14/mi")
        assertThat(tempo.last().detail).contains("@ 8:14/mi (tempo)")
        val intervals = sessions.filter { it.kind == WorkoutKind.Intervals }
        assertThat(intervals.last().detail).contains("× 800 m @ 7:33/mi")
        val mpLong = sessions.last { it.kind == WorkoutKind.Long && it.detail!!.contains("goal pace") }
        assertThat(mpLong.detail).contains("@ 8:35/mi")
        // Easy runs stay at current-fitness pace (MP 8:45 + 60–90 s → ~10:00/mi): 5 mi ≈ 50 min.
        val easy = sessions.first { it.kind == WorkoutKind.Easy }
        assertThat(easy.durationMin.toDouble()).isWithin(2.0).of(easy.distanceMi!! * 600 / 60)
        val raceDay = sessions.single { it.kind == WorkoutKind.Race }
        assertThat(raceDay.date).isEqualTo(race)
        assertThat(raceDay.durationMin).isEqualTo(225)
    }

    @Test fun warningsForShortPlansAndAmbitiousGoals() {
        val short = build(inputs.copy(raceDate = start.plusWeeks(8).plusDays(6)), fitness.copy(longestRunMi = 6.0)).second!!
        assertThat(short.warnings.joinToString()).contains("can only safely reach")
        val fast = build(inputs.copy(goalMin = 180)).second!!
        // Easy 10:00 → predicted (600 − 75) × 26.2188 = 13765 s ≈ 3:49:25
        assertThat(fast.warnings.joinToString()).contains("Ambitious goal")
        assertThat(build().second!!.warnings).isEmpty()
    }

    @Test fun planStartingMidWeekDoesNotCrash() {
        // Created on a Sunday: the rest of that week is already past.
        val sunday = LocalDate.of(2026, 10, 18)
        val (r, s) = build(inputs.copy(startDate = sunday, raceDate = LocalDate.of(2027, 2, 7)))
        val weeks = (r as PlanResult.Ok).weeks
        assertThat(weeks.first().sessions.all { !it.date.isBefore(sunday) }).isTrue()
        assertThat(s!!.peakLongMi).isEqualTo(20.0)
    }

    @Test fun ridesAddRunEquivalentMiles() {
        val now = 30 * DAY_MS
        val runs = listOf(RunSample(now - DAY_MS, 5.0, 3000), RunSample(now - 3 * DAY_MS, 5.0, 3000))
        val rides = listOf(RideSample(now - 2 * DAY_MS, 90 * 60, 22.0), RideSample(now - 4 * DAY_MS, 90 * 60, 21.0))
        val f = Fitness.fromActivities(runs, rides, now, historyDays = 7)!!
        // Runs: 10 mi/week. Rides: 180 min/week × 0.4 = 72 run-min ÷ 10:00/mi = 7.2 mi.
        assertThat(f.runMilesPerWeek).isWithin(1e-9).of(10.0)
        assertThat(f.rideCreditMi).isWithin(1e-9).of(7.2)
        assertThat(f.weeklyMiles).isWithin(1e-9).of(17.2)
        assertThat(f.longestRunMi).isEqualTo(5.0) // rides don't count toward the long run
        assertThat(f.rides).isEqualTo(2)
    }

    @Test fun rideCreditIsCapped() {
        val now = 30 * DAY_MS
        val runs = listOf(RunSample(now - DAY_MS, 4.0, 2400))
        val rides = (1..6).map { RideSample(now - it * DAY_MS / 2, 120 * 60, null) }
        val f = Fitness.fromActivities(runs, rides, now, historyDays = 7)!!
        // 720 min × 0.4 = 288 run-min ÷ 10:00 = 28.8 mi, capped at max(4, 10) = 10.
        assertThat(f.rideCreditMi).isWithin(1e-9).of(10.0)
        // Rides only: easy pace defaults to 11:00/mi, still capped at 10.
        val ridesOnly = Fitness.fromActivities(emptyList(), rides, now, historyDays = 7)!!
        assertThat(ridesOnly.runs).isEqualTo(0)
        assertThat(ridesOnly.weeklyMiles).isWithin(1e-9).of(10.0)
        assertThat(Fitness.fromRuns(emptyList(), now)).isNull()
    }

    @Test fun steadyRunClubIsTheTempo() {
        // Javon's setup: steady run club Tuesday, long run Saturday, runs Wed/Thu/Sun.
        val club = FixedSession(TUESDAY, Sport.Run, "Run club", 6.3, 70, Intensity.Moderate)
        val i = inputs.copy(fixed = listOf(club), days = setOf(WEDNESDAY, THURSDAY, SATURDAY, SUNDAY), longDay = SATURDAY)
        val weeks = (build(i).first as PlanResult.Ok).weeks
        // No plan tempo while the club is a steady run (in the taper the club goes easy and a short tempo returns).
        assertThat(weeks.filter { it.phase != Phase.Taper }.flatMap { it.sessions }.none { it.kind == WorkoutKind.Tempo }).isTrue()
        weeks.filter { it.phase == Phase.Base }.forEach { w -> assertThat(w.sessions.none { it.kind == WorkoutKind.Intervals }).isTrue() }
        // Full build/peak weeks: exactly one interval session, never the day after the club.
        weeks.filter { (it.phase == Phase.Build || it.phase == Phase.Peak) && !it.lighter }.forEach { w ->
            val iv = w.sessions.filter { it.kind == WorkoutKind.Intervals }
            assertThat(iv).hasSize(1)
            assertThat(iv.single().date.dayOfWeek).isNotEqualTo(WEDNESDAY)
        }
    }

    @Test fun pacesStartAtFitnessAndReachGoalAtPeak() {
        val (r, s) = build(inputs.copy(goalMin = 215)) // 3:35 goal vs ~3:49 fitness
        val weeks = (r as PlanResult.Ok).weeks
        val fit = PaceMath.forGoal(MARATHON_MI, PaceMath.predictedMarathonSec(fitness))
        // Easy runs never faster than current fitness supports.
        assertThat(s!!.paces.easy).isEqualTo(fit.easy)
        assertThat(s.paces.tempo).isGreaterThan(s.goalPaces.tempo)
        fun tempoAt(w: Int) = weeks[w].sessions.firstOrNull { it.kind == WorkoutKind.Tempo }?.detail
        assertThat(tempoAt(s.peakWeek)).contains("@ ${PaceMath.fmt(s.goalPaces.tempo)}/mi (tempo)")
        assertThat(tempoAt(0)).contains("@ ${PaceMath.fmt(fit.tempo)}/mi (tempo)")
        // A runner already faster than the goal trains at goal paces from week 1.
        val fast = build(inputs.copy(goalMin = 260)).second!!
        assertThat(fast.paces).isEqualTo(fast.goalPaces)
    }

    @Test fun runClubCountsInMileage() {
        val club = FixedSession(WEDNESDAY, Sport.Run, "Run club", 6.0, 55, Intensity.Hard)
        val weeks = (build(inputs.copy(fixed = listOf(club))).first as PlanResult.Ok).weeks
        weeks.dropLast(1).forEach { w ->
            val c = w.sessions.single { it.kind.recurring }
            assertThat(c.distanceMi).isEqualTo(6.0)
            if (c.kind == WorkoutKind.ClubHard) w.sessions.filter { it.intensity == Intensity.Hard && !it.kind.recurring }.forEach {
                assertThat(it.date.dayOfWeek).isNoneOf(TUESDAY, THURSDAY)
            }
        }
    }
}
