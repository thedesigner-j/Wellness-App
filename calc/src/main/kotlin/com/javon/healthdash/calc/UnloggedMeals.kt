package com.javon.healthdash.calc

/** A glucose rise with no meal logged near it (SPEC §5). */
data class UnloggedRise(val riseStartMs: Long, val peakMs: Long, val rise: Double)

object UnloggedMeals {

    /**
     * Finds rises of at least [Targets.unloggedRiseMgdl] within [Targets.unloggedRiseWindowMin] minutes
     * where no meal was logged from 30 min before the rise start to the peak.
     * Rises inside a training window are ignored. Detections closer than 90 min are merged.
     */
    fun detect(
        points: List<GlucosePoint>,
        meals: List<MealEvent>,
        workouts: List<TimeWindow>,
        targets: Targets = Targets(),
    ): List<UnloggedRise> {
        val sorted = points.sortedBy { it.timeMs }
        val windowMs = targets.unloggedRiseWindowMin * MINUTE_MS
        val lookbackMs = targets.unloggedMealLookbackMin * MINUTE_MS
        val afterTraining = targets.trainingAfterMin * MINUTE_MS
        val found = mutableListOf<UnloggedRise>()
        var lo = 0
        for (i in sorted.indices) {
            val p = sorted[i]
            while (sorted[lo].timeMs < p.timeMs - windowMs) lo++
            var minIdx = lo
            for (j in lo..i) if (sorted[j].mgdl < sorted[minIdx].mgdl) minIdx = j
            val start = sorted[minIdx]
            val rise = p.mgdl - start.mgdl
            if (rise < targets.unloggedRiseMgdl) continue
            if (workouts.any { start.timeMs >= it.startMs && start.timeMs < it.endMs + afterTraining }) continue
            val logged = meals.any { it.timeMs >= start.timeMs - lookbackMs && it.timeMs <= p.timeMs }
            if (logged) continue
            val last = found.lastOrNull()
            if (last != null && start.timeMs - last.riseStartMs < 90 * MINUTE_MS) {
                if (rise > last.rise) found[found.lastIndex] = last.copy(peakMs = p.timeMs, rise = rise)
                continue
            }
            found += UnloggedRise(start.timeMs, p.timeMs, rise)
        }
        return found
    }
}
