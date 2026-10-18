package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MealResponseTest {

    private val t0 = 60 * MINUTE_MS
    private val meal = MealEvent(1, t0, "brown rice + chicken")

    /** Baseline 100; peak 150 at +30 min; back to baseline at +55; then 95. */
    private fun curve(): List<GlucosePoint> {
        val before = listOf(45, 50, 55, 60).map { GlucosePoint(it * MINUTE_MS, 100.0) }
        val shape = mapOf(5 to 110, 10 to 120, 15 to 130, 20 to 140, 25 to 145, 30 to 150, 35 to 140, 40 to 130, 45 to 120, 50 to 110, 55 to 100)
        val after = (5..120 step 5).map { off -> GlucosePoint(t0 + off * MINUTE_MS, (shape[off] ?: 95).toDouble()) }
        return before + after
    }

    @Test fun handCheckedResponse() {
        val r = (MealResponses.compute(meal, curve()) as ResponseResult.Ready).response
        assertThat(r.baseline).isWithin(1e-9).of(100.0)
        assertThat(r.peak).isWithin(1e-9).of(150.0)
        assertThat(r.peakRise).isWithin(1e-9).of(50.0)
        assertThat(r.timeToPeakMin).isEqualTo(30)
        assertThat(r.backToBaselineMin).isEqualTo(55)
        // Trapezoids of excess (0,10,20,30,40,45,50,40,30,20,10,0) × 5 min = 1475
        assertThat(r.auc).isWithin(1e-9).of(1475.0)
        assertThat(r.coverage).isWithin(1e-9).of(1.0)
    }

    @Test fun handCheckedScore() {
        val r = (MealResponses.compute(meal, curve()) as ResponseResult.Ready).response
        // rise 1 − 30/60 = 0.5; area 1 − 1475/6000 = 0.754167; recovery (back by 55) = 1
        // 100 × (0.25 + 0.22625 + 0.2) = 67.6 → 68
        assertThat(MealResponses.score(r)).isEqualTo(68)
        assertThat(MealResponses.grade(68)).isEqualTo(MealResponses.Grade.Fair)
    }

    @Test fun scoreBounds() {
        val flat = MealResponse(100.0, 110.0, 10.0, 20, 40, 0.0, 1.0)
        assertThat(MealResponses.score(flat)).isEqualTo(100)
        val bad = MealResponse(100.0, 200.0, 100.0, 60, null, 9000.0, 1.0)
        assertThat(MealResponses.score(bad)).isEqualTo(0)
    }

    @Test fun noBaseline() {
        val onlyAfter = curve().filter { it.timeMs > t0 }
        assertThat(MealResponses.compute(meal, onlyAfter)).isEqualTo(ResponseResult.NoBaseline)
    }

    @Test fun lateDataIsIncomplete() {
        val partial = curve().filter { it.timeMs <= t0 + 30 * MINUTE_MS }
        val result = MealResponses.compute(meal, partial)
        assertThat(result).isInstanceOf(ResponseResult.Incomplete::class.java)
        assertThat((result as ResponseResult.Incomplete).coverage).isWithin(1e-9).of(0.25)
    }

    @Test fun neverBackToBaseline() {
        val pts = curve().map { if (it.timeMs > t0 + 30 * MINUTE_MS) it.copy(mgdl = 120.0) else it }
        val r = (MealResponses.compute(meal, pts) as ResponseResult.Ready).response
        assertThat(r.backToBaselineMin).isNull()
    }

    @Test fun walkEffectProratesSteps() {
        val inside = StepInterval(t0 + 10 * MINUTE_MS, t0 + 35 * MINUTE_MS, 1300)
        assertThat(MealResponses.walkEffect(meal, listOf(inside), emptyList()))
            .isEqualTo(WalkEffect(1300, workout = false, walked = true))
        // Half of this interval falls inside the 45-min window: 600 steps, below 1,000.
        val half = StepInterval(t0 + 30 * MINUTE_MS, t0 + 60 * MINUTE_MS, 1200)
        assertThat(MealResponses.walkEffect(meal, listOf(half), emptyList()))
            .isEqualTo(WalkEffect(600, workout = false, walked = false))
        val workout = TimeWindow(t0 + 20 * MINUTE_MS, t0 + 80 * MINUTE_MS)
        assertThat(MealResponses.walkEffect(meal, emptyList(), listOf(workout)).walked).isTrue()
    }
}
