package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class HeartRateTest {
    private val today = LocalDate.of(2026, 11, 5)

    /** Synthetic 1 Hz stream shaped like the hard 10K: ramps to ~172–176, peaks 189 for 15 s near the end. */
    private fun tenKSamples(): List<HrSample> = (0..3623).map { t ->
        val bpm = when {
            t < 600 -> 140 + t * 30 / 600          // warm-up ramp to 170
            t in 3500..3514 -> 189                 // finishing kick, held 15 s
            else -> 174
        }
        HrSample(t.toDouble(), bpm)
    }

    @Test fun sustainedMaxIgnoresSpikesAndShortPeaks() {
        val base = (0..600).map { HrSample(it.toDouble(), 150) }.toMutableList()
        base[300] = HrSample(300.0, 199)                                   // 1-s spike: jump > 10 bpm/s → rejected
        (400..405).forEach { base[it] = HrSample(it.toDouble(), 160) }      // only 6 s at 160 (jump also rejected) → not held
        (100..120).forEach { base[it] = HrSample(it.toDouble(), 190) }      // first 3 min ignored
        val s = HeartRate.stats(base)
        assertThat(s.sustainedMax).isEqualTo(150)
        assertThat(HeartRate.stats(tenKSamples()).sustainedMax).isEqualTo(189)
    }

    private fun act(stats: HrStats, hard: Boolean = true, allOut: Boolean = false, date: LocalDate = today.minusDays(3), sec: Int = 3623, temp: Double? = 72.0) =
        HrActivity(date, sec, 189, stats, 172.0, temp, hard, allOut, "10K")

    @Test fun maxHrIsAFloorUnlessAllOut() {
        val s = HeartRate.stats(tenKSamples())
        val m = HeartRate.maxHr(listOf(act(s)), today)!!
        assertThat(m.bpm).isEqualTo(189)
        assertThat(m.label).isEqualTo("189+")
        assertThat(HeartRate.maxHr(listOf(act(s, allOut = true)), today)!!.label).isEqualTo("189")
        // Raise immediately on a higher reading; nothing reliable → null (prompt a max effort).
        val higher = HrStats(193, null, null)
        assertThat(HeartRate.maxHr(listOf(act(s), act(higher, date = today.minusDays(1))), today)!!.bpm).isEqualTo(193)
        assertThat(HeartRate.maxHr(listOf(act(HrStats(null, null, null))), today)).isNull()
        // Older than 12 months is ignored.
        assertThat(HeartRate.maxHr(listOf(act(s, date = today.minusDays(400))), today)).isNull()
    }

    @Test fun lthrFromTheHard10k() {
        val s = HeartRate.stats(tenKSamples())
        // 60-min effort → avg after the first 10 min (~174.2), heat 72°F → −0.4 → 174
        val l = HeartRate.lthrFrom(act(s), 189)!!
        assertThat(l.bpm).isIn(com.google.common.collect.Range.closed(172, 176))
        // Easy ceiling ≈ 150–155 (top of Z2 = 89% LTHR)
        assertThat(HeartRate.easyCeiling(l.bpm)).isIn(com.google.common.collect.Range.closed(150, 157))
        // Not a hard effort / wrong duration / outside 85–95% of max → rejected.
        assertThat(HeartRate.lthrFrom(act(s, hard = false), 189)).isNull()
        assertThat(HeartRate.lthrFrom(act(s, sec = 42 * 60), 189)).isNull()
        assertThat(HeartRate.lthrFrom(act(HrStats(189, null, 185.0)), 189)).isNull()
        // 30–40 min test → last 20 min average.
        assertThat(HeartRate.lthrFrom(act(HrStats(185, 168.0, 160.0), sec = 35 * 60, temp = 60.0), 189)!!.bpm).isEqualTo(168)
        assertThat(l.valid(today.plusDays(39))).isTrue()
        assertThat(l.valid(today.plusDays(40))).isFalse()
    }

    @Test fun zonesFromLthr() {
        val z = HeartRate.zones(174)
        assertThat(z.map { it.name }).containsExactly("Z1", "Z2", "Z3", "Z4", "Z5").inOrder()
        assertThat(z[1].low).isEqualTo(148)   // 85%
        assertThat(z[1].high).isEqualTo(155)  // 89%
        assertThat(z[4].low).isEqualTo(174)
        assertThat(HeartRate.easyCeiling(174)).isEqualTo(155)
    }

    @Test fun paceAt145() {
        // 5 mi in 60 min at avg 150 → 12:00/mi × 150/145 = 12:24/mi equivalent... speed scaled → pace × 150/145
        assertThat(HeartRate.paceAt145(5.0, 3600, 150.0)).isEqualTo(745)
        assertThat(HeartRate.paceAt145(3.15, 3132, 125.0)).isNull() // outside 140–150
    }

    @Test fun testPrompts() {
        val race = LocalDate.of(2027, 1, 10)
        val nextQ = today.plusDays(5)
        val fresh = Lthr(174, today.minusDays(3), "10K")
        val max = MaxHr(189, today, floor = true)
        assertThat(HeartRate.prompts(today, race, nextQ, fresh, max, null, null, 4, false)).isEmpty()
        // Stale LTHR → replace the next quality session.
        val stale = HeartRate.prompts(today, race, nextQ, fresh.copy(date = today.minusDays(50)), max, null, null, 4, false)
        assertThat(stale.single().kind).isEqualTo(TestKind.ThresholdTest)
        assertThat(stale.single().replaceSessionDate).isEqualTo(nextQ)
        // Pace at 145 improved ≥ 5% → retest.
        assertThat(HeartRate.prompts(today, race, nextQ, fresh, max, 680, 720, 4, true).single().title).contains("retest")
        // Week 8 first test.
        assertThat(HeartRate.prompts(today, race, nextQ, fresh, max, null, null, 7, false).single().title).isEqualTo("First threshold test")
        // No max HR → max effort prompt.
        assertThat(HeartRate.prompts(today, race, nextQ, fresh, null, null, null, 4, false).single().kind).isEqualTo(TestKind.MaxHrEffort)
        // Never within 10 days of the race, never without a quality session to replace.
        assertThat(HeartRate.prompts(today, race, race.minusDays(9), null, null, null, null, 4, false)).isEmpty()
        assertThat(HeartRate.prompts(today, race, null, null, null, null, null, 4, false)).isEmpty()
    }
}
