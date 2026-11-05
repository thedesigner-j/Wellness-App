package com.javon.healthdash.calc

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

// Max HR, threshold HR (LTHR), zones, test prompts and the easy-run fitness marker. Fixed rules, no AI.

/** One heart-rate reading, [t] seconds from the start of the activity. */
data class HrSample(val t: Double, val bpm: Int)

/** Heart-rate figures derived from one activity's samples (stored with the workout). */
data class HrStats(
    /** Highest reliable value: after 3 min, no >10 bpm/s jumps, held ≥ 10 s. */
    val sustainedMax: Int?,
    /** Average of the last 20 minutes. */
    val avgLast20: Double?,
    /** Average excluding the first 10 minutes. */
    val avgAfter10: Double?,
)

data class MaxHr(val bpm: Int, val date: LocalDate?, val floor: Boolean, val estimate: Boolean = false) {
    /** "189+" when it's only the highest seen so far (not from a marked all-out effort). */
    val label get() = "$bpm${if (floor) "+" else ""}${if (estimate) " (estimate)" else ""}"
}

data class Lthr(val bpm: Int, val date: LocalDate, val source: String, val thresholdPaceSec: Int? = null) {
    fun valid(today: LocalDate) = ChronoUnit.DAYS.between(date, today) <= HeartRate.LTHR_VALID_DAYS
}

data class HrZoneRow(val name: String, val low: Int?, val high: Int?)

/** An activity as heart-rate logic sees it. */
data class HrActivity(
    val date: LocalDate,
    val elapsedSec: Int,
    val maxHr: Int?,
    val stats: HrStats?,
    val avgHr: Double?,
    val tempF: Double?,
    val hard: Boolean,
    val allOut: Boolean,
    val label: String,
    val distanceMi: Double? = null,
)

enum class TestKind { ThresholdTest, MaxHrEffort }

data class TestPrompt(val kind: TestKind, val title: String, val body: String, val replaceSessionDate: LocalDate?)

object HeartRate {
    const val SKIP_SEC = 180.0
    const val MAX_JUMP_PER_SEC = 10.0
    const val HOLD_SEC = 10.0
    const val LTHR_VALID_DAYS = 42L
    const val EASY_HR = 145
    const val EASY_HR_BAND = 5

    /** Sustained max, last-20-min and after-10-min averages from raw samples. */
    fun stats(samples: List<HrSample>): HrStats {
        val s = samples.sortedBy { it.t }
        if (s.isEmpty()) return HrStats(null, null, null)
        // Reject spikes: a sample that jumps > 10 bpm/s from the previous accepted one.
        val ok = mutableListOf<HrSample>()
        for (x in s) {
            val prev = ok.lastOrNull()
            if (prev != null && x.t > prev.t && abs(x.bpm - prev.bpm) / (x.t - prev.t) > MAX_JUMP_PER_SEC) continue
            ok += x
        }
        val usable = ok.filter { it.t >= SKIP_SEC }
        // Highest value held ≥ 10 s: max over windows spanning ≥ 10 s of the window minimum.
        var best: Int? = null
        var j = 0
        for (i in usable.indices) {
            if (j < i) j = i
            while (j < usable.size && usable[j].t - usable[i].t < HOLD_SEC) j++
            if (j >= usable.size) break
            val m = (i..j).minOf { usable[it].bpm }
            if (best == null || m > best) best = m
        }
        val end = s.last().t
        val last20 = ok.filter { it.t >= end - 20 * 60 }.map { it.bpm }
        val after10 = ok.filter { it.t >= 10 * 60 }.map { it.bpm }
        return HrStats(best, last20.takeIf { it.isNotEmpty() }?.average(), after10.takeIf { it.isNotEmpty() }?.average())
    }

    /**
     * Max HR from the last 12 months: the highest reliable reading, decayed by at most 1 bpm per year of age
     * of the reading. A floor ("189+") unless it came from a marked all-out effort. Null → prompt a max-HR effort.
     */
    fun maxHr(acts: List<HrActivity>, today: LocalDate): MaxHr? {
        val year = acts.filter { !it.date.isAfter(today) && it.date.isAfter(today.minusYears(1)) }
        return year.mapNotNull { a ->
            val v = a.stats?.sustainedMax ?: return@mapNotNull null
            val years = ChronoUnit.DAYS.between(a.date, today) / 365.0
            Triple(v - years.toInt(), a.date, a.allOut)
        }.maxByOrNull { it.first }?.let { (bpm, date, allOut) -> MaxHr(bpm, date, floor = !allOut) }
    }

    /**
     * LTHR from a qualifying hard effort: 30–40 min → avg of the last 20 min; 45–75 min → avg excluding the
     * first 10 min. Heat above 70°F: −1 bpm per 5°F. Rejected outside 85–95% of max HR.
     */
    fun lthrFrom(a: HrActivity, maxHr: Int?): Lthr? {
        if (!a.hard) return null
        val min = a.elapsedSec / 60.0
        val raw = when {
            min in 30.0..40.0 -> a.stats?.avgLast20
            min in 45.0..75.0 -> a.stats?.avgAfter10
            else -> null
        } ?: return null
        val heat = if ((a.tempF ?: 0.0) > 70) (a.tempF!! - 70) / 5 else 0.0
        val bpm = (raw - heat).roundToInt()
        if (maxHr != null && (bpm < maxHr * 0.85 || bpm > maxHr * 0.95)) return null
        return Lthr(bpm, a.date, a.label)
    }

    /** Latest valid LTHR from the activities (newest wins). */
    fun latestLthr(acts: List<HrActivity>, maxHr: Int?): Lthr? =
        acts.sortedByDescending { it.date }.firstNotNullOfOrNull { lthrFrom(it, maxHr) }

    /** Z1 < 85%, Z2 85–89%, Z3 90–94%, Z4 95–99%, Z5 ≥ 100% of LTHR — contiguous whole-bpm bands (a zone runs up to just under the next boundary). */
    fun zones(lthr: Int): List<HrZoneRow> {
        fun at(x: Double) = kotlin.math.ceil(lthr * x - 1e-9).toInt()
        val b = listOf(at(0.85), at(0.90), at(0.95), lthr)
        return listOf(
            HrZoneRow("Z1", null, b[0] - 1), HrZoneRow("Z2", b[0], b[1] - 1), HrZoneRow("Z3", b[1], b[2] - 1),
            HrZoneRow("Z4", b[2], b[3] - 1), HrZoneRow("Z5", lthr, null),
        )
    }

    /** Easy-run ceiling = top of Z2 (just under 90% of LTHR). */
    fun easyCeiling(lthr: Int) = kotlin.math.ceil(lthr * 0.90 - 1e-9).toInt() - 1

    /** Easy-run fitness marker: pace normalised to 145 bpm (speed scaled by 145 / avg HR), for runs averaging 140–150. */
    fun paceAt145(distanceMi: Double, elapsedSec: Int, avgHr: Double?): Int? {
        if (avgHr == null || abs(avgHr - EASY_HR) > EASY_HR_BAND || distanceMi <= 0) return null
        val pace = elapsedSec / distanceMi
        // Implausible running paces (GPS errors, mislabelled rides or walks) are skipped.
        if (pace < 390 || pace > 960) return null
        return (pace * avgHr / EASY_HR).roundToInt()
    }

    const val TEST_PROTOCOL = "15 min easy warm-up · 10 min hard · 20 min hard (lap at 10 min) · cool-down. LTHR = average HR of the last 20 min; threshold pace = average pace of the last 20 min."
    const val MAX_PROTOCOL = "15 min easy · 3 × 3 min uphill hard, jog down · last 2 min all-out (rate it 10/10). Only when fresh."

    /**
     * Test prompts: replace the next scheduled quality session (never an extra hard day, never within 10 days of
     * the race) with a 30-min threshold test when LTHR is missing/stale, the easy pace-at-145 has improved ≥ 5%
     * since the last test, or it's week 8 and no test has been done. A max-HR effort is suggested when there's
     * no reliable max HR.
     */
    fun prompts(
        today: LocalDate, raceDate: LocalDate, nextQuality: LocalDate?, lthr: Lthr?, maxHr: MaxHr?,
        paceNow: Int?, paceAtTest: Int?, planWeek: Int, testedEver: Boolean,
    ): List<TestPrompt> {
        val slot = nextQuality?.takeIf { it.isBefore(raceDate.minusDays(10)) } ?: return emptyList()
        val out = mutableListOf<TestPrompt>()
        when {
            lthr == null || !lthr.valid(today) -> out += TestPrompt(TestKind.ThresholdTest, "Time for a threshold test",
                (if (lthr == null) "No threshold HR yet." else "Your threshold HR is from ${lthr.date} — over 6 weeks old.") + " Replace this quality session with a 30-min test: $TEST_PROTOCOL", slot)
            paceNow != null && paceAtTest != null && paceNow <= paceAtTest * 0.95 -> out += TestPrompt(TestKind.ThresholdTest, "You've gotten fitter — retest",
                "Easy pace at 145 bpm is ${PaceMath.fmt(paceNow)}/mi vs ${PaceMath.fmt(paceAtTest)}/mi at your last test. Replace this quality session with a 30-min test: $TEST_PROTOCOL", slot)
            !testedEver && planWeek >= 7 -> out += TestPrompt(TestKind.ThresholdTest, "First threshold test",
                "Week 8 is a good time for your first test. Replace this quality session: $TEST_PROTOCOL", slot)
        }
        if (maxHr == null) out += TestPrompt(TestKind.MaxHrEffort, "Find your max heart rate",
            "There's no reliable max HR reading yet. Use this quality session for a max effort: $MAX_PROTOCOL", slot)
        return out
    }
}
