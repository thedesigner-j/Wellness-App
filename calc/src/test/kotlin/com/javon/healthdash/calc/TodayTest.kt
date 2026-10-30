package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TodayTest {
    private val strength = TodayItem(2, Sport.Strength, WorkoutKind.Strength, 40, null, null, done = false)
    private val tempo = TodayItem(1, Sport.Run, WorkoutKind.Tempo, 45, 4.5, null, done = false)

    @Test fun twoSessionsOneDone() {
        val s = TodaySummary.of(listOf(strength, tempo.copy(done = true)))
        // Strength sorts last.
        assertThat(s.items.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(s.headline).isEqualTo("2 sessions · 1 to go")
        assertThat(s.morningText()).isEqualTo("Tempo 4.5 mi + Strength 40 min (≈ 1 h 25 min)")
        assertThat(s.checkInText()).isEqualTo("Still to do today: Strength 40 min (≈ 40 min)")
        assertThat(s.lateWarning(20)).isEqualTo("Strength 40 min still open — about 40 min. Do it now or move it.")
        assertThat(s.lateWarning(12)).isNull()
    }

    @Test fun restDayAndAllDone() {
        val rest = TodaySummary.of(emptyList())
        assertThat(rest.headline).isEqualTo("Rest day")
        assertThat(rest.morningText()).isNull()
        assertThat(rest.checkInText()).isNull()
        val done = TodaySummary.of(listOf(tempo.copy(done = true), strength.copy(done = true)))
        assertThat(done.headline).isEqualTo("All done today ✓")
        assertThat(done.checkInText()).isNull()
        assertThat(done.lateWarning(21)).isNull()
    }

    @Test fun labels() {
        assertThat(TodayItem(3, Sport.Run, WorkoutKind.ClubHard, 55, 6.0, "Run club", false).short).isEqualTo("Run club 6 mi")
        assertThat(TodayItem(4, Sport.Ride, WorkoutKind.Long, 180, 40.0, null, false).short).isEqualTo("Long ride 40 mi")
        assertThat(TodaySummary.duration(60)).isEqualTo("1 h")
        assertThat(TodaySummary.duration(26)).isEqualTo("26 min")
        // A single open session late in the day isn't a "planning" problem.
        assertThat(TodaySummary.of(listOf(tempo)).lateWarning(20)).isNull()
    }
}
