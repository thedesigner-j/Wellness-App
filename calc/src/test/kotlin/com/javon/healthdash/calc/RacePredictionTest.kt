package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

/** Javon's real runs (Nov 2026). */
class RacePredictionTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val nowMs = today.toEpochDay() * DAY_MS + 20 * 3_600_000L
    private val race = LocalDate.of(2027, 1, 10)

    /** Hard 10K: 6.28 mi, elapsed 1:00:23 (moving 59:16), avg HR 172, max 189, 72°F, +207 ft / −193 ft. */
    private val hard10k = EffortRun(nowMs - 3 * DAY_MS, 6.28, 3623, avgHr = 172.0, tempF = 72.0, ascentFt = 207.0, descentFt = 193.0)
    /** Easy run: 3.15 mi, 52:12, avg HR 125, effort 1/10. */
    private val easy = EffortRun(nowMs - 1 * DAY_MS, 3.15, 3132, avgHr = 125.0, rpe = 1)

    @Test fun adjustsForHeatAndHills() {
        // Heat: 12°F over 60 → 2.4% → 3623 × 0.976 = 3536.0; hills: 62.1 − 28.95 = 33.15 s → 3502.9
        assertThat(RacePrediction.heatFraction(72.0)).isWithin(1e-9).of(0.024)
        assertThat(RacePrediction.hillSeconds(207.0, 193.0)).isWithin(1e-9).of(33.15)
        val adj = RacePrediction.adjusted(hard10k)
        assertThat(adj).isEqualTo(3503)
        assertThat(adj / 60.0).isIn(com.google.common.collect.Range.closed(58.0, 59.0))
        assertThat(RacePrediction.heatFraction(55.0)).isEqualTo(0.0)
    }

    @Test fun riegelExponentByEndurance() {
        assertThat(RacePrediction.riegelK(10.6)).isEqualTo(1.08)
        assertThat(RacePrediction.riegelK(13.0)).isEqualTo(1.07)
        assertThat(RacePrediction.riegelK(17.9)).isEqualTo(1.07)
        assertThat(RacePrediction.riegelK(18.0)).isEqualTo(1.06)
    }

    @Test fun onlyHardEffortsQualify() {
        // No LTHR yet: max HR 189 → threshold stand-in 170.1 → hard if avg ≥ 153.
        assertThat(RacePrediction.isHard(hard10k, null, 189)).isTrue()
        assertThat(RacePrediction.isHard(easy, null, 189)).isFalse()
        assertThat(RacePrediction.isHard(easy.copy(rpe = 8), null, 189)).isTrue()
        // The easy run alone → no prediction ("Needs a hard effort").
        assertThat(RacePrediction.bestEffort(listOf(easy), nowMs, MARATHON_MI, 1.08, null, 189)).isNull()
        // Older than 6 weeks doesn't count.
        assertThat(RacePrediction.bestEffort(listOf(hard10k.copy(startMs = nowMs - 43 * DAY_MS)), nowMs, MARATHON_MI, 1.08, null, 189)).isNull()
    }

    @Test fun fitnessTodayFromTheHard10k() {
        val (_, sec) = RacePrediction.bestEffort(listOf(easy, hard10k), nowMs, MARATHON_MI, 1.08, null, 189)!!
        // 3503 × (26.2188 / 6.28)^1.08 ≈ 16,396 s ≈ 4:33
        assertThat(sec / 3600.0).isIn(com.google.common.collect.Range.closed(4.5, 4.5 + 5 / 60.0))
        assertThat(RacePrediction.fmtMinute(sec)).isEqualTo("4:33")
    }

    @Test fun weightedAdherence() {
        val d = today.minusDays(3)
        val items = listOf(AdherenceItem(d, long = true, done = true), AdherenceItem(d, false, true), AdherenceItem(d, false, false), AdherenceItem(d, false, false))
        // (2 + 1) / (2 + 1 + 1 + 1) = 0.6
        assertThat(RacePrediction.adherence(items, today)).isWithin(1e-9).of(0.6)
        // Older than 4 weeks or not yet due → ignored.
        assertThat(RacePrediction.adherence(listOf(AdherenceItem(today.minusDays(40), false, false), AdherenceItem(today, false, false)), today)).isEqualTo(1.0)
    }

    @Test fun buildWeeksFromTheRealDates() {
        // Nov 5 → Jan 10 = 66 days = 9.43 weeks; taper 2 → 7.43 build weeks (not ~5).
        assertThat(RacePrediction.buildWeeksLeft(today, race, 2)).isWithin(0.01).of(7.43)
        assertThat(RacePrediction.gain(7.0, 0.7)).isWithin(1e-9).of(0.0196)
        assertThat(RacePrediction.gain(20.0, 1.0)).isEqualTo(0.04)
    }

    private fun spec(penalty: Boolean) = RacePrediction.forecast(
        listOf(hard10k, easy), nowMs, today, today.plusWeeks(9), MARATHON_MI, 270 * 60,
        longestRecentMi = 10.6, adherence = 0.7, lthr = null, maxHr = 189, firstMarathon = true, taperWeeks = 2,
        c = PredictionConfig(firstMarathonPenalty = penalty),
    )!!

    @Test fun projectionProbabilityAndRange() {
        // Spec example: 9 weeks out, 7 build weeks × 70% adherence.
        val f = spec(penalty = false)
        assertThat(f.gain).isWithin(1e-9).of(0.0196)
        assertThat(f.projectedSec / 3600.0).isIn(com.google.common.collect.Range.closed(4 + 26 / 60.0, 4 + 31 / 60.0))
        // SD% = √(16 + 4 + 2.7²) = 5.224 → SD ≈ 14 min
        assertThat(f.sdPct).isWithin(0.001).of(5.224)
        assertThat(f.sdSec / 60.0).isWithin(0.5).of(14.0)
        assertThat(f.probability!!).isIn(com.google.common.collect.Range.closed(0.45, 0.60))
        assertThat(f.lowSec / 3600.0).isIn(com.google.common.collect.Range.closed(4 + 8 / 60.0, 4 + 12 / 60.0))
        assertThat(f.highSec / 3600.0).isIn(com.google.common.collect.Range.closed(4 + 44 / 60.0, 4 + 48 / 60.0))
        assertThat(f.effort).isEqualTo(hard10k)
    }

    @Test fun firstMarathonPenaltyAddsOnePointFivePercent() {
        val base = spec(false); val pen = spec(true)
        assertThat(pen.projectedSec.toDouble()).isWithin(2.0).of(base.projectedSec * 1.015)
        assertThat(pen.probability!!).isLessThan(base.probability!!)
        assertThat(pen.probability!!).isAtLeast(0.40)
    }

    @Test fun lowMileageMakesFitnessTodayMoreConservative() {
        // 15 mi/week: shortfall (40 − 15) / 40 = 62.5% of the 5% max → +3.125% → 4:33 becomes ~4:42.
        assertThat(RacePrediction.mileageAdjustment(15.0, MARATHON_MI)).isWithin(1e-9).of(0.03125)
        assertThat(RacePrediction.mileageAdjustment(40.0, MARATHON_MI)).isEqualTo(0.0)
        assertThat(RacePrediction.mileageAdjustment(55.0, MARATHON_MI)).isEqualTo(0.0)
        assertThat(RacePrediction.mileageAdjustment(0.0, MARATHON_MI)).isWithin(1e-9).of(0.05)
        // Half the effect for a half marathon, none for a 10K, none without data.
        assertThat(RacePrediction.mileageAdjustment(15.0, HALF_MI)).isWithin(1e-4).of(0.03125 / 2)
        assertThat(RacePrediction.mileageAdjustment(15.0, TEN_K_MI)).isEqualTo(0.0)
        assertThat(RacePrediction.mileageAdjustment(null, MARATHON_MI)).isEqualTo(0.0)

        fun at(miles: Double?) = RacePrediction.forecast(
            listOf(hard10k), nowMs, today, today.plusWeeks(9), MARATHON_MI, 270 * 60, 10.6, 0.7, null, 189,
            firstMarathon = false, taperWeeks = 2, weeklyMiles = miles,
        )!!
        val low = at(15.0)
        assertThat(low.rawFitnessSec / 3600.0).isIn(com.google.common.collect.Range.closed(4.5, 4.5 + 5 / 60.0))  // 4:33
        assertThat(low.fitnessSec / 3600.0).isIn(com.google.common.collect.Range.closed(4 + 40 / 60.0, 4 + 45 / 60.0))  // 4:40–4:45
        // Eases off as mileage builds: at 40 mi/week fitness today is the raw 4:33 again.
        assertThat(at(40.0).fitnessSec).isEqualTo(at(40.0).rawFitnessSec)
        assertThat(at(25.0).fitnessSec).isLessThan(low.fitnessSec)
        assertThat(at(25.0).probability!!).isGreaterThan(low.probability!!)
    }

    @Test fun probabilityDisplayIsClamped() {
        val far = spec(false).copy(probability = 0.999)
        assertThat(far.displayPercent).isEqualTo(95)
        assertThat(far.copy(probability = 0.001).displayPercent).isEqualTo(5)
    }
}
