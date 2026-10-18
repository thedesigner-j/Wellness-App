package com.javon.healthdash.calc

enum class GlucoseContext { Training, Overnight, Everyday }

/**
 * Splits readings three ways (SPEC §5):
 * Training = during a workout or up to [Targets.trainingAfterMin] after it;
 * Overnight = inside a sleep window;
 * Everyday = everything else. Training wins over Overnight if both apply.
 */
object ContextSplit {
    fun classify(
        timeMs: Long,
        workouts: List<TimeWindow>,
        sleep: List<TimeWindow>,
        targets: Targets = Targets(),
    ): GlucoseContext {
        val after = targets.trainingAfterMin * MINUTE_MS
        if (workouts.any { timeMs >= it.startMs && timeMs < it.endMs + after }) return GlucoseContext.Training
        if (sleep.any { timeMs in it }) return GlucoseContext.Overnight
        return GlucoseContext.Everyday
    }

    fun split(
        points: List<GlucosePoint>,
        workouts: List<TimeWindow>,
        sleep: List<TimeWindow>,
        targets: Targets = Targets(),
    ): Map<GlucoseContext, List<GlucosePoint>> =
        GlucoseContext.entries.associateWith { ctx ->
            points.filter { classify(it.timeMs, workouts, sleep, targets) == ctx }
        }
}
