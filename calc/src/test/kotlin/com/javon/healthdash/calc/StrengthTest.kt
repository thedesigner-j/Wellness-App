package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StrengthTest {
    @Test fun routinesAlternateAndDoseByPhase() {
        val a = StrengthPlan.workout(Phase.Base, second = false)
        val b = StrengthPlan.workout(Phase.Base, second = true)
        assertThat(a.sets.map { it.exercise }).containsExactlyElementsIn(StrengthPlan.A).inOrder()
        assertThat(b.sets.map { it.exercise }).containsExactlyElementsIn(StrengthPlan.B).inOrder()
        assertThat(a.sets.first().sets).isEqualTo(2)
        assertThat(a.sets.first().reps).isEqualTo("12")
        assertThat(a.sets.single { it.exercise == Exercise.Plank }.reps).isEqualTo("30 s")
        assertThat(a.sets.single { it.exercise == Exercise.ReverseLunge }.reps).isEqualTo("12 / side")

        val build = StrengthPlan.workout(Phase.Build, false)
        assertThat(build.sets.first().sets).isEqualTo(3)
        assertThat(build.sets.first().reps).isEqualTo("8")
        assertThat(build.minutes).isGreaterThan(a.minutes)

        val taper = StrengthPlan.workout(Phase.Taper, true)
        assertThat(taper.sets.all { it.sets == 1 }).isTrue()
        assertThat(taper.minutes).isLessThan(a.minutes)
    }

    @Test fun everyExerciseHasCues() {
        Exercise.entries.forEach { assertThat(it.cues.size).isAtLeast(3) }
    }
}
