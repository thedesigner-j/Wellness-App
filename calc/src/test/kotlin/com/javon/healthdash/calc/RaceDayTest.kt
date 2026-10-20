package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RaceDayTest {
    private val goal = 270 * 60 // 4:30:00 marathon

    @Test fun evenSplits() {
        val s = RaceDay.splits(MARATHON_MI, goal, SplitStrategy.Even)
        assertThat(s).hasSize(27)
        // 16200 / 26.2188 = 617.9 → 10:18/mi
        assertThat(s.first().paceSec).isEqualTo(618)
        assertThat(s[12].elapsedSec).isEqualTo((13 * 16200 / MARATHON_MI).toInt().let { it + if ((13 * 16200 / MARATHON_MI) - it >= 0.5) 1 else 0 })
        assertThat(s.last().atMi).isWithin(1e-9).of(MARATHON_MI)
        assertThat(s.last().elapsedSec).isEqualTo(goal)
    }

    @Test fun negativeSplitsFinishOnGoal() {
        val s = RaceDay.splits(MARATHON_MI, goal, SplitStrategy.Negative)
        assertThat(s.first().paceSec).isEqualTo(624)  // 617.9 × 1.01
        assertThat(s[20].paceSec).isEqualTo(612)      // × 0.99
        // Halfway is reached later than even pace, the finish is the same.
        val even = RaceDay.splits(MARATHON_MI, goal, SplitStrategy.Even)
        assertThat(s[12].elapsedSec).isGreaterThan(even[12].elapsedSec)
        assertThat(s.last().elapsedSec).isEqualTo(goal)
    }

    @Test fun fuelSchedule() {
        val s = RaceDay.splits(MARATHON_MI, goal, SplitStrategy.Even)
        val (stops, rate, total) = RaceDay.fuel(goal, s)
        // > 150 min, Moderate: 80–90 g/h → 85 g/h → 25 g every 60/3.4 = 17.6 → 20 min.
        assertThat(rate).isEqualTo(85)
        assertThat(stops.first().atSec).isEqualTo(45 * 60)
        assertThat(stops[1].atSec - stops[0].atSec).isEqualTo(20 * 60)
        // 45, 65, …, 245 min (last at least 15 min before 270).
        assertThat(stops.last().atSec).isEqualTo(245 * 60)
        assertThat(stops).hasSize(11)
        assertThat(total).isEqualTo(275)
        // 45 min at 10:18/mi ≈ 4.4 mi
        assertThat(stops.first().atMi).isWithin(0.05).of(2700 / 617.9)
    }

    @Test fun shortRaceNoFuelNoCarbLoad() {
        val s = RaceDay.splits(FIVE_K_MI, 28 * 60, SplitStrategy.Even)
        assertThat(s).hasSize(4)
        assertThat(RaceDay.fuel(28 * 60, s).first).isEmpty()
        assertThat(RaceDay.carbLoad(28 * 60, 80.0)).isNull()
        val load = RaceDay.carbLoad(goal, 80.0)!!
        assertThat(load.gramsLow).isEqualTo(640)
        assertThat(load.gramsHigh).isEqualTo(800)
    }

    @Test fun rideSplitsEveryTenMiles() {
        val s = RaceDay.splits(100.0, 400 * 60, SplitStrategy.Even, Sport.Ride)
        assertThat(s).hasSize(10)
        assertThat(s.first().elapsedSec).isEqualTo(40 * 60)
    }
}
