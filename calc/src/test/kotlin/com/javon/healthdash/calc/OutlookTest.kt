package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutlookTest {
    @Test fun vdotMatchesDanielsTables() {
        // Daniels' tables: VDOT 50 → marathon 3:10:49, 5K 19:57.
        assertThat(GoalLikelihood.vdotTimeSec(50.0, MARATHON_MI).toDouble()).isWithin(60.0).of(3 * 3600 + 10 * 60 + 49.0)
        assertThat(GoalLikelihood.vdotTimeSec(50.0, FIVE_K_MI).toDouble()).isWithin(15.0).of(19 * 60 + 57.0)
    }

    @Test fun phiIsTheNormalCdf() {
        assertThat(GoalLikelihood.phi(0.0)).isWithin(1e-6).of(0.5)
        assertThat(GoalLikelihood.phi(1.0)).isWithin(1e-4).of(0.8413)
        assertThat(GoalLikelihood.phi(-1.645)).isWithin(1e-3).of(0.05)
    }

    @Test fun bestEffortUsesFastestRecentRunOverThreeMiles() {
        val samples = listOf(
            EffortSample(6.2, 3000, 5),   // 50:00 10K-ish
            EffortSample(2.0, 840, 2),    // too short
            EffortSample(5.0, 2700, 50),  // too old
            EffortSample(4.0, 2400, 10),
        )
        val (e, sec) = GoalLikelihood.bestEffort(samples, MARATHON_MI, 1.06)!!
        assertThat(e.distanceMi).isEqualTo(6.2)
        // 3000 × (26.2188 / 6.2)^1.06 = 13,808 s ≈ 3:50:08
        assertThat(sec.toDouble()).isWithin(5.0).of(3000 * Math.pow(MARATHON_MI / 6.2, 1.06))
    }

    @Test fun goalAtProjectionIsATossUp() {
        val effortSec = (3000 * Math.pow(MARATHON_MI / 6.2, 1.06)).toInt()
        val o = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, effortSec, listOf(EffortSample(6.2, 3000, 5)), null, trainingWeeksLeft = 0, weeksLeft = 0, consistency = 1.0)!!
        assertThat(o.percent).isEqualTo(50)
        assertThat(o.outlook).isEqualTo(Outlook.TossUp)
    }

    @Test fun trainingAndConsistencyImproveTheOdds() {
        val samples = listOf(EffortSample(6.2, 3000, 5))
        val goal = 225 * 60 // 3:45 vs current ≈ 3:50
        val now = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, goal, samples, null, 0, 0, 1.0)!!
        val trained = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, goal, samples, null, 9, 12, 1.0)!!
        val skipping = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, goal, samples, null, 9, 12, 0.4)!!
        // 9 weeks × 0.6% = 5.4% gain at full consistency.
        assertThat(trained.gainPct).isWithin(1e-9).of(0.054)
        assertThat(trained.projectedSec).isLessThan(goal)
        assertThat(trained.percent).isGreaterThan(now.percent)
        assertThat(skipping.percent).isLessThan(trained.percent)
        assertThat(skipping.gainPct).isWithin(1e-9).of(0.054 * 0.4)
    }

    @Test fun gainIsCappedAndOddsStayBetween5And95() {
        val o = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, 120 * 60, listOf(EffortSample(6.2, 3000, 5)), null, 30, 34, 1.0)!!
        assertThat(o.gainPct).isEqualTo(0.08)
        assertThat(o.percent).isEqualTo(5)
        val easy = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, 360 * 60, listOf(EffortSample(6.2, 3000, 5)), null, 0, 0, 1.0)!!
        assertThat(easy.percent).isEqualTo(95)
    }

    @Test fun easyRunsUseTypicalPaceInstead() {
        // Only an easy 6.6 mi at 12:03/mi: Riegel gives ~5:43. Typical pace 12:03 → MP 10:48 → 4:43:xx is faster, so use it.
        val o = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, 270 * 60, listOf(EffortSample(6.6, 4773, 1)), null, 0, 0, 1.0, typicalPaceSec = 723)!!
        assertThat(o.fromTypicalPace).isTrue()
        assertThat(o.currentSec.toDouble()).isWithin(2.0).of((723 - 75) * MARATHON_MI)
        // A genuinely fast run beats typical pace and is kept.
        val fast = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, 270 * 60, listOf(EffortSample(6.2, 3000, 1)), null, 0, 0, 1.0, typicalPaceSec = 723)!!
        assertThat(fast.fromTypicalPace).isFalse()
    }

    @Test fun vo2AveragesWithEffortAndNoDataIsNull() {
        val both = GoalLikelihood.estimate(Sport.Run, MARATHON_MI, 225 * 60, listOf(EffortSample(6.2, 3000, 5)), 50.0, 0, 0, 1.0)!!
        assertThat(both.currentSec).isEqualTo((both.effortSec!! + both.vo2Sec!!) / 2)
        assertThat(GoalLikelihood.estimate(Sport.Run, MARATHON_MI, 225 * 60, emptyList(), null, 0, 0, 1.0)).isNull()
        // VO2 max isn't used for rides.
        assertThat(GoalLikelihood.estimate(Sport.Ride, 100.0, 400 * 60, emptyList(), 50.0, 0, 0, 1.0)).isNull()
    }
}
