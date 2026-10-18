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
        val tempo = sessions.first { it.kind == WorkoutKind.Tempo }
        assertThat(tempo.detail).contains("@ 8:14/mi (tempo)")
        val intervals = sessions.first { it.kind == WorkoutKind.Intervals }
        assertThat(intervals.detail).contains("× 800 m @ 7:33/mi")
        val mpLong = sessions.first { it.kind == WorkoutKind.Long && it.detail!!.contains("goal pace") }
        assertThat(mpLong.detail).contains("@ 8:35/mi")
        // Duration comes from distance × pace: an easy 5 mi at ~9:50/mi ≈ 49 min.
        val easy = sessions.first { it.kind == WorkoutKind.Easy }
        assertThat(easy.durationMin.toDouble()).isWithin(2.0).of(easy.distanceMi!! * 590 / 60)
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
