package com.javon.healthdash.calc

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Race-day plan: splits, fueling schedule and carb loading. Fixed rules, no AI.

enum class SplitStrategy(val label: String) { Even("Even"), Negative("Negative split") }

/** One split: distance at the end of the segment, its pace (sec/mi) and the clock time there. */
data class Split(val atMi: Double, val paceSec: Int, val elapsedSec: Int)

data class FuelStop(val atSec: Int, val atMi: Double, val grams: Int)

data class CarbLoad(val days: Int, val gPerKgLow: Int, val gPerKgHigh: Int, val gramsLow: Int?, val gramsHigh: Int?)

data class RaceDayPlan(
    val splits: List<Split>,
    val fuel: List<FuelStop>,
    val carbsPerHour: Int,
    val totalCarbs: Int,
    val carbLoad: CarbLoad?,
)

object RaceDay {
    /** Grams of carbs per gel / chew serving used for the schedule. */
    const val SERVING_G = 25
    const val FIRST_FUEL_SEC = 45 * 60
    /** Negative split: first half 1% slower than goal pace, second half 1% faster — same finish time. */
    const val NEGATIVE = 0.01

    /** Splits every mile (rides: every 10 miles); the last one is the partial remainder and lands exactly on the goal. */
    fun splits(raceMi: Double, goalSec: Int, strategy: SplitStrategy, sport: Sport = Sport.Run): List<Split> {
        val step = if (sport == Sport.Ride) 10.0 else 1.0
        val pace = goalSec / raceMi
        val half = raceMi / 2
        fun paceAt(midMi: Double) = if (strategy == SplitStrategy.Negative) pace * (if (midMi < half) 1 + NEGATIVE else 1 - NEGATIVE) else pace
        val out = mutableListOf<Split>()
        var at = 0.0
        var elapsed = 0.0
        while (at < raceMi - 1e-9) {
            val end = min(raceMi, at + step)
            // Split segments that straddle halfway so the total stays exact.
            var seg = at
            while (seg < end - 1e-9) {
                val stop = if (strategy == SplitStrategy.Negative && seg < half && end > half) min(end, half) else end
                elapsed += (stop - seg) * paceAt((seg + stop) / 2)
                seg = stop
            }
            val p = elapsed - (out.lastOrNull()?.elapsedSec ?: 0)
            out += Split(end, (p / (end - at)).roundToInt(), if (end >= raceMi - 1e-9) goalSec else elapsed.roundToInt())
            at = end
        }
        return out
    }

    /** Clock time → distance along the splits (linear within a split). */
    fun distanceAt(sec: Int, splits: List<Split>): Double {
        var prevMi = 0.0; var prevSec = 0
        for (s in splits) {
            if (sec <= s.elapsedSec) return prevMi + (s.atMi - prevMi) * (sec - prevSec).toDouble() / (s.elapsedSec - prevSec)
            prevMi = s.atMi; prevSec = s.elapsedSec
        }
        return prevMi
    }

    /**
     * Fuel from the fueling table (race = moderate-to-hard, the table's Moderate row for the race length):
     * first serving at 45 min, then one 25 g serving every 60 / (rate ÷ 25) min (rounded to 5), none in the last 15 min.
     */
    fun fuel(goalSec: Int, splits: List<Split>, table: FuelTable = FuelTable.DEFAULT): Triple<List<FuelStop>, Int, Int> {
        val goalMin = goalSec / 60
        val r = table.range(goalMin, Intensity.Moderate)
        val rate = (r.lo + r.hi) / 2
        if (goalMin < 75 || rate == 0) return Triple(emptyList(), rate, 0)
        val every = max(15, (60.0 / (rate.toDouble() / SERVING_G) / 5).roundToInt() * 5) * 60
        val stops = generateSequence(FIRST_FUEL_SEC) { it + every }.takeWhile { it <= goalSec - 15 * 60 }
            .map { FuelStop(it, (distanceAt(it, splits) * 10).roundToInt() / 10.0, SERVING_G) }.toList()
        return Triple(stops, rate, stops.sumOf { it.grams })
    }

    /** 2 days of 8–10 g carbs per kg body weight for races of 90 min or more. */
    fun carbLoad(goalSec: Int, weightKg: Double?): CarbLoad? {
        if (goalSec < 90 * 60) return null
        return CarbLoad(2, 8, 10, weightKg?.let { (it * 8).roundToInt() }, weightKg?.let { (it * 10).roundToInt() })
    }

    fun plan(raceMi: Double, goalSec: Int, strategy: SplitStrategy, sport: Sport, weightKg: Double?, table: FuelTable = FuelTable.DEFAULT): RaceDayPlan {
        val s = splits(raceMi, goalSec, strategy, sport)
        val (stops, rate, total) = fuel(goalSec, s, table)
        return RaceDayPlan(s, stops, rate, total, carbLoad(goalSec, weightKg))
    }

    /** Number of full splits (for display). */
    fun count(raceMi: Double, sport: Sport) = ceil(raceMi / if (sport == Sport.Ride) 10.0 else 1.0).toInt()
}
