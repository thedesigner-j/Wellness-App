package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EasyRunTest {
    private val ceiling = HeartRate.easyCeiling(174) // 155

    @Test fun javonsEasyRunIsNotTooHardAndSaysHrWasLow() {
        // Planned 3.0 mi @ 11:31–12:01/mi (≈ 35 min); ran 3.15 mi in 52:12 (16:34/mi), avg HR 125, effort 1/10.
        val r = Feedback.review(WorkoutKind.Easy, Sport.Run, 35, 3.0, 52, 3.15, 125.0, emptyList(), easyCeiling = ceiling)
        assertThat(r.hrHigh).isFalse()
        assertThat(r.headline).isNotEqualTo("Too hard for an easy day")
        assertThat(r.notes.joinToString()).contains("Your HR was low")
        assertThat(r.notes.joinToString()).contains("closer to planned pace")
        // Never "fine" just because it was slower.
        assertThat(r.notes.joinToString()).doesNotContain("fine")
    }

    @Test fun easyRunJudgedByHrNotPace() {
        // Fast but HR under the ceiling → good easy run, not flagged.
        val quick = Feedback.review(WorkoutKind.Easy, Sport.Run, 35, 3.0, 30, 3.0, 148.0, emptyList(), easyCeiling = ceiling)
        assertThat(quick.hrHigh).isFalse()
        assertThat(quick.headline).isEqualTo("Nailed it — HR stayed easy")
        // HR above the ceiling → flagged whatever the pace.
        val hard = Feedback.review(WorkoutKind.Easy, Sport.Run, 35, 3.0, 40, 3.0, 160.0, emptyList(), easyCeiling = ceiling)
        assertThat(hard.hrHigh).isTrue()
        assertThat(hard.headline).isEqualTo("Too hard for an easy day")
        // Slow with HR near the ceiling → no "go quicker" nudge.
        val slowWorking = Feedback.review(WorkoutKind.Easy, Sport.Run, 35, 3.0, 45, 3.0, 150.0, emptyList(), easyCeiling = ceiling)
        assertThat(slowWorking.notes.joinToString()).doesNotContain("HR was low")
    }
}
