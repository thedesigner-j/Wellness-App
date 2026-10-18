package com.javon.healthdash.domain

import com.javon.healthdash.calc.ContextSplit
import com.javon.healthdash.calc.FoodRank
import com.javon.healthdash.calc.GlucoseContext
import com.javon.healthdash.calc.GlucosePoint
import com.javon.healthdash.calc.GlucoseStats
import com.javon.healthdash.calc.GlucoseSummary
import com.javon.healthdash.calc.HOUR_MS
import com.javon.healthdash.calc.MINUTE_MS
import com.javon.healthdash.calc.MealAnalytics
import com.javon.healthdash.calc.MealEvent
import com.javon.healthdash.calc.MealResponses
import com.javon.healthdash.calc.Metric
import com.javon.healthdash.calc.Nutrients
import com.javon.healthdash.calc.NutrientsPer100g
import com.javon.healthdash.calc.NutritionMath
import com.javon.healthdash.calc.ResponseResult
import com.javon.healthdash.calc.ScoredMeal
import com.javon.healthdash.calc.StepInterval
import com.javon.healthdash.calc.Targets
import com.javon.healthdash.calc.TimeWindow
import com.javon.healthdash.calc.Trend
import com.javon.healthdash.calc.UnloggedMeals
import com.javon.healthdash.calc.UnloggedRise
import com.javon.healthdash.calc.WalkComparison
import com.javon.healthdash.calc.WalkEffect
import com.javon.healthdash.calc.foodKeyOf
import com.javon.healthdash.calc.Reading
import com.javon.healthdash.calc.SleepSession
import com.javon.healthdash.data.db.BodyEntity
import com.javon.healthdash.data.db.LabEntity
import com.javon.healthdash.data.db.MealItemEntity
import com.javon.healthdash.data.db.MealWithItems
import com.javon.healthdash.data.db.SyncStateEntity
import com.javon.healthdash.data.db.WorkoutEntity
import java.time.Instant
import java.time.ZoneId

/** A meal joined with everything computed about it. All numbers come from :calc. */
data class MealView(
    val data: MealWithItems,
    val event: MealEvent,
    val title: String,
    val nutrients: Nutrients,
    /** Items whose nutrition is incomplete (no grams or no USDA match). */
    val incompleteItems: Int,
    val result: ResponseResult,
    val score: Int?,
    val walk: WalkEffect,
    val inTraining: Boolean,
) {
    val id get() = data.meal.id
    val response get() = (result as? ResponseResult.Ready)?.response
}

data class DashboardState(
    val loading: Boolean = true,
    val nowMs: Long = System.currentTimeMillis(),
    val targets: Targets = Targets(),
    val targetValues: Map<String, Double> = emptyMap(),
    val glucose: List<GlucosePoint> = emptyList(),
    val steps: List<StepInterval> = emptyList(),
    val workouts: List<WorkoutEntity> = emptyList(),
    val meals: List<MealView> = emptyList(),
    val scored: List<ScoredMeal> = emptyList(),
    val ranks: List<FoodRank> = emptyList(),
    val walkComparisons: List<WalkComparison> = emptyList(),
    val latest: GlucosePoint? = null,
    val trend: Trend? = null,
    val today: Metric<GlucoseSummary> = Metric.NotEnoughData(0.0),
    val gmi14: Metric<GlucoseSummary> = Metric.NotEnoughData(0.0),
    val stepsToday: Long = 0,
    val weightAvg7: Double? = null,
    val weightDelta7: Double? = null,
    val bodyFatAvg7: Double? = null,
    val bodyFatSource: String? = null,
    val unlogged: UnloggedRise? = null,
    val syncStates: List<SyncStateEntity> = emptyList(),
    val sleep: List<SleepSession> = emptyList(),
    val hrv: List<Reading> = emptyList(),
    val restingHr: List<Reading> = emptyList(),
    val vo2: List<Reading> = emptyList(),
    val labs: List<LabEntity> = emptyList(),
) {
    val workoutWindows get() = workouts.map { TimeWindow(it.startMs, it.endMs) }
    fun meal(id: Long) = meals.firstOrNull { it.id == id }
}

object Analysis {

    fun itemNutrients(item: MealItemEntity): Nutrients? {
        val g = item.grams ?: return null
        if (item.fdcId == null && item.carbs100 == null) return null
        return NutritionMath.forGrams(NutrientsPer100g(item.carbs100, item.protein100, item.fat100, item.fiber100, item.kcal100), g)
    }

    fun mealTitle(items: List<MealItemEntity>): String =
        items.map { it.name }.ifEmpty { listOf("Meal") }.joinToString(", ") { it.replaceFirstChar(Char::uppercase) }

    fun foodKey(items: List<MealItemEntity>) = foodKeyOf(items.map { it.name })

    /** Readings inside [from, to], using binary search on the sorted list. */
    fun slice(points: List<GlucosePoint>, from: Long, to: Long): List<GlucosePoint> {
        if (points.isEmpty()) return points
        var lo = points.binarySearchBy(from) { it.timeMs }.let { if (it < 0) -it - 1 else it }
        while (lo > 0 && points[lo - 1].timeMs >= from) lo--
        var hi = points.binarySearchBy(to) { it.timeMs }.let { if (it < 0) -it - 2 else it }
        while (hi + 1 < points.size && points[hi + 1].timeMs <= to) hi++
        return if (lo > hi) emptyList() else points.subList(lo, hi + 1)
    }

    fun build(
        nowMs: Long,
        zone: ZoneId,
        targets: Targets,
        targetValues: Map<String, Double>,
        glucose: List<GlucosePoint>,
        steps: List<StepInterval>,
        workouts: List<WorkoutEntity>,
        meals: List<MealWithItems>,
        weights: List<BodyEntity>,
        bodyFat: List<BodyEntity>,
        syncStates: List<SyncStateEntity>,
    ): DashboardState {
        val sorted = glucose.sortedBy { it.timeMs }
        val windows = workouts.map { TimeWindow(it.startMs, it.endMs) }

        val mealViews = meals.map { m ->
            val event = MealEvent(m.meal.id, m.meal.timeMs, foodKey(m.items))
            val near = slice(sorted, m.meal.timeMs - 30 * MINUTE_MS, m.meal.timeMs + 3 * HOUR_MS)
            val result = MealResponses.compute(event, near, targets)
            val ready = result as? ResponseResult.Ready
            val parts = m.items.map { itemNutrients(it) }
            MealView(
                data = m,
                event = event,
                title = mealTitle(m.items),
                nutrients = parts.filterNotNull().fold(Nutrients.ZERO) { a, b -> a + b },
                incompleteItems = parts.count { it == null },
                result = result,
                score = ready?.let { MealResponses.score(it.response) },
                walk = MealResponses.walkEffect(event, steps, windows, targets),
                inTraining = ContextSplit.classify(m.meal.timeMs, windows, emptyList(), targets) == GlucoseContext.Training,
            )
        }
        val scored = mealViews.mapNotNull { v ->
            val r = v.response ?: return@mapNotNull null
            ScoredMeal(v.event, r, v.score!!, v.walk, v.inTraining)
        }

        val midnight = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val today = if (nowMs - midnight < HOUR_MS) Metric.NotEnoughData(0.0)
        else GlucoseStats.summarize(slice(sorted, midnight, nowMs), TimeWindow(midnight, nowMs), targets)
        val gmi14 = GlucoseStats.summarize(slice(sorted, nowMs - 14 * 24 * HOUR_MS, nowMs), TimeWindow(nowMs - 14 * 24 * HOUR_MS, nowMs), targets)

        val stepsToday = steps.sumOf { s ->
            val overlap = minOf(nowMs, s.endMs) - maxOf(midnight, s.startMs)
            if (overlap <= 0) 0.0 else s.count * overlap.toDouble() / (s.endMs - s.startMs).coerceAtLeast(1)
        }.toLong()

        val weekAgo = nowMs - 7 * 24 * HOUR_MS
        val w7 = weights.filter { it.timeMs >= weekAgo }
        val wPrev = weights.filter { it.timeMs in (weekAgo - 7 * 24 * HOUR_MS) until weekAgo }
        val bf7 = bodyFat.filter { it.timeMs >= weekAgo }
        val w7avg = w7.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
        val wPrevAvg = wPrev.takeIf { it.isNotEmpty() }?.map { it.value }?.average()

        val recentMeals = mealViews.map { it.event }
        val unlogged = UnloggedMeals.detect(slice(sorted, nowMs - 6 * HOUR_MS, nowMs), recentMeals, windows, targets).lastOrNull()

        val latest = GlucoseStats.latest(slice(sorted, nowMs - 6 * HOUR_MS, nowMs), nowMs)

        return DashboardState(
            loading = false,
            nowMs = nowMs,
            targets = targets,
            targetValues = targetValues,
            glucose = sorted,
            steps = steps,
            workouts = workouts,
            meals = mealViews.sortedByDescending { it.event.timeMs },
            scored = scored,
            ranks = MealAnalytics.rank(scored, targets),
            walkComparisons = MealAnalytics.walkComparisons(scored),
            latest = latest,
            trend = latest?.let { GlucoseStats.trend(slice(sorted, nowMs - 20 * MINUTE_MS, nowMs), it.timeMs) },
            today = today,
            gmi14 = gmi14,
            stepsToday = stepsToday,
            weightAvg7 = w7avg,
            weightDelta7 = if (w7avg != null && wPrevAvg != null) w7avg - wPrevAvg else null,
            bodyFatAvg7 = bf7.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
            bodyFatSource = bf7.firstOrNull()?.source,
            unlogged = unlogged,
            syncStates = syncStates,
        )
    }
}
