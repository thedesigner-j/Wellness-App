package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class CoachingTest {
    private val zones = Zones.heartRate(55.0, 185.0) // Z1 120–133, Z2 133–146, Z3 146–159, Z4 159–172, Z5 172–185

    @Test fun easyRunTooFast() {
        // Planned 5 mi in 50 min (10:00/mi); ran 5 mi in 45 min (9:00/mi).
        val r = Feedback.review(WorkoutKind.Easy, Sport.Run, 50, 5.0, 45, 5.0, 150.0, zones)
        assertThat(r.plannedPace).isEqualTo(600)
        assertThat(r.actualPace).isEqualTo(540)
        assertThat(r.pace).isEqualTo(PaceCheck.TooFast)
        assertThat(r.hrZone).isEqualTo(3)
        assertThat(r.hrHigh).isTrue()
        assertThat(r.headline).isEqualTo("Done — but too fast")
    }

    @Test fun easySlowerIsFineAndTempoOnTarget() {
        val easy = Feedback.review(WorkoutKind.Easy, Sport.Run, 50, 5.0, 52, 5.0, 140.0, zones)
        assertThat(easy.pace).isEqualTo(PaceCheck.OnTarget)
        assertThat(easy.headline).isEqualTo("Nailed it")
        // Tempo 4.5 mi in 45:00 (10:00) vs 44:00 done: 586 s = −2.3% → on target.
        val tempo = Feedback.review(WorkoutKind.Tempo, Sport.Run, 45, 4.5, 44, 4.5, 160.0, zones)
        assertThat(tempo.pace).isEqualTo(PaceCheck.OnTarget)
        assertThat(tempo.hrHigh).isFalse()
    }

    @Test fun cutShortAndNoDistance() {
        val r = Feedback.review(WorkoutKind.Long, Sport.Run, 120, 12.0, 80, 8.0, null, zones)
        assertThat(r.completion).isWithin(1e-9).of(8.0 / 12)
        assertThat(r.headline).isEqualTo("Partly done")
        val manual = Feedback.review(WorkoutKind.Easy, Sport.Run, 50, 5.0, 50, null, null, emptyList())
        assertThat(manual.pace).isEqualTo(PaceCheck.NoData)
        assertThat(manual.completion).isWithin(1e-9).of(1.0)
    }

    @Test fun ridePaceIsSpeed() {
        // Planned 30 mi in 2 h = 15 mph; rode 30 mi in 2:30 = 12 mph → too slow.
        val r = Feedback.review(WorkoutKind.Long, Sport.Ride, 120, 30.0, 150, 30.0, null, zones)
        assertThat(r.plannedPace).isEqualTo(150)
        assertThat(r.actualPace).isEqualTo(120)
        assertThat(r.pace).isEqualTo(PaceCheck.TooSlow)
    }

    private val d = LocalDate.of(2026, 11, 10)

    @Test fun tooHardEasesTheWeek() {
        val rated = listOf(
            RatedSession(d.minusDays(5), WorkoutKind.Easy, 7),
            RatedSession(d.minusDays(3), WorkoutKind.Tempo, 8),
            RatedSession(d.minusDays(1), WorkoutKind.Long, 9),
        )
        val s = Adaptation.suggest(rated, d)!!
        assertThat(s.kind).isEqualTo(SuggestionKind.Ease)
        assertThat(s.reason).startsWith("2 of your last 3")
        assertThat(Adaptation.ease(8.5)).isEqualTo(7.5)  // 7.65 → 7.5
        assertThat(Adaptation.ease(1.0)).isEqualTo(1.0)
    }

    @Test fun easyQualityTriggersGoalCheckAndNormalIsQuiet() {
        val easyQ = listOf(
            RatedSession(d.minusDays(12), WorkoutKind.Tempo, 5), RatedSession(d.minusDays(8), WorkoutKind.Intervals, 4),
            RatedSession(d.minusDays(4), WorkoutKind.Tempo, 5),
        )
        assertThat(Adaptation.suggest(easyQ, d)!!.kind).isEqualTo(SuggestionKind.GoalCheck)
        val normal = listOf(RatedSession(d.minusDays(3), WorkoutKind.Easy, 3), RatedSession(d.minusDays(2), WorkoutKind.Tempo, 7), RatedSession(d.minusDays(1), WorkoutKind.Easy, 4))
        assertThat(Adaptation.suggest(normal, d)).isNull()
        // Old ratings are ignored.
        assertThat(Adaptation.suggest(easyQ.map { it.copy(date = it.date.minusDays(30)) }, d)).isNull()
    }

    @Test fun moveWarnings() {
        val mon = LocalDate.of(2026, 11, 9)
        fun s(day: Int, kind: WorkoutKind, sport: Sport = Sport.Run) = PlannedSession(mon.plusDays(day.toLong()), sport, kind, 50, "")
        val tempo = s(1, WorkoutKind.Tempo)
        val week = listOf(tempo, s(2, WorkoutKind.Easy), s(3, WorkoutKind.Intervals), s(6, WorkoutKind.Long), s(0, WorkoutKind.Strength, Sport.Strength))
        assertThat(Reschedule.warnings(tempo, mon, week)).isEmpty()                              // Monday: only strength, no hard neighbour
        assertThat(Reschedule.warnings(tempo, mon.plusDays(4), week).joinToString()).contains("Thursday's intervals")
        assertThat(Reschedule.warnings(tempo, mon.plusDays(2), week).joinToString()).contains("two in one day")
        assertThat(Reschedule.warnings(tempo, mon.plusDays(5), week).joinToString()).contains("Sunday's long")
    }
}
