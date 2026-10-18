package com.javon.healthdash.calc

import java.time.Instant
import java.time.ZoneId

/** A meal with its computed response and walk effect. */
data class ScoredMeal(
    val meal: MealEvent,
    val response: MealResponse,
    val score: Int,
    val walk: WalkEffect,
    val inTrainingWindow: Boolean,
)

data class FoodRank(
    val foodKey: String,
    val count: Int,
    val avgScore: Double,
    val avgRise: Double,
)

data class GroupAverage(val count: Int, val avgRise: Double?, val avgScore: Double?)

data class WalkComparison(val foodKey: String, val walk: GroupAverage, val noWalk: GroupAverage)

enum class DayPart { Morning, Midday, Evening }

/** Forecast for one walk/no-walk branch; [avgRise] is null when fewer than the minimum past meals exist. */
data class ForecastBranch(val count: Int, val avgRise: Double?)

data class Forecast(val foodKey: String, val dayPart: DayPart, val walk: ForecastBranch, val noWalk: ForecastBranch) {
    val hasAny get() = walk.avgRise != null || noWalk.avgRise != null
}

object MealAnalytics {

    fun dayPart(timeMs: Long, zone: ZoneId): DayPart {
        val hour = Instant.ofEpochMilli(timeMs).atZone(zone).hour
        return when {
            hour < 11 -> DayPart.Morning
            hour < 16 -> DayPart.Midday
            else -> DayPart.Evening
        }
    }

    /**
     * Best-to-worst ranking by average score. Foods need at least [Targets.minRankInstances] scored meals.
     * Meals inside a training window are excluded (carbs around training are expected).
     */
    fun rank(meals: List<ScoredMeal>, targets: Targets = Targets()): List<FoodRank> =
        meals.filter { !it.inTrainingWindow }
            .groupBy { it.meal.foodKey }
            .filterValues { it.size >= targets.minRankInstances }
            .map { (key, list) ->
                FoodRank(
                    foodKey = key,
                    count = list.size,
                    avgScore = list.map { it.score.toDouble() }.average(),
                    avgRise = list.map { it.response.peakRise }.average(),
                )
            }
            .sortedWith(compareByDescending<FoodRank> { it.avgScore }.thenBy { it.avgRise })

    private fun average(list: List<ScoredMeal>) = GroupAverage(
        count = list.size,
        avgRise = list.takeIf { it.isNotEmpty() }?.map { it.response.peakRise }?.average(),
        avgScore = list.takeIf { it.isNotEmpty() }?.map { it.score.toDouble() }?.average(),
    )

    /** Walk vs no-walk for each food that has at least one meal in each group. */
    fun walkComparisons(meals: List<ScoredMeal>): List<WalkComparison> =
        meals.filter { !it.inTrainingWindow }
            .groupBy { it.meal.foodKey }
            .map { (key, list) ->
                WalkComparison(key, average(list.filter { it.walk.walked }), average(list.filter { !it.walk.walked }))
            }
            .filter { it.walk.count > 0 && it.noWalk.count > 0 }
            .sortedByDescending { it.walk.count + it.noWalk.count }

    /**
     * Pre-meal forecast from the user's own history: same food, same part of day, split by walk.
     * A branch shows no number with fewer than [Targets.minForecastInstances] past meals.
     */
    fun forecast(
        foodKey: String,
        atMs: Long,
        history: List<ScoredMeal>,
        zone: ZoneId,
        targets: Targets = Targets(),
    ): Forecast {
        val part = dayPart(atMs, zone)
        val same = history.filter { it.meal.foodKey == foodKey && !it.inTrainingWindow && dayPart(it.meal.timeMs, zone) == part }
        fun branch(list: List<ScoredMeal>) = ForecastBranch(
            count = list.size,
            avgRise = if (list.size >= targets.minForecastInstances) list.map { it.response.peakRise }.average() else null,
        )
        return Forecast(foodKey, part, branch(same.filter { it.walk.walked }), branch(same.filter { !it.walk.walked }))
    }

    /** Average rise curve (excess over baseline at each [stepMin] offset) for a group of meals. */
    fun averageCurve(
        meals: List<MealEvent>,
        points: List<GlucosePoint>,
        targets: Targets = Targets(),
        stepMin: Int = 15,
    ): List<Pair<Int, Double>>? {
        val curves = meals.mapNotNull { meal ->
            val r = MealResponses.compute(meal, points, targets) as? ResponseResult.Ready ?: return@mapNotNull null
            val after = points.filter { it.timeMs >= meal.timeMs && it.timeMs <= meal.timeMs + targets.responseWindowMin * MINUTE_MS }
            (0..targets.responseWindowMin step stepMin).map { off ->
                val t = meal.timeMs + off * MINUTE_MS
                val nearest = after.minByOrNull { kotlin.math.abs(it.timeMs - t) }
                off to ((nearest?.mgdl ?: r.response.baseline) - r.response.baseline)
            }
        }
        if (curves.isEmpty()) return null
        return curves.first().indices.map { i -> curves[0][i].first to curves.map { it[i].second }.average() }
    }
}

/** Normalized grouping key for "the same meal": lower-cased food names, sorted, joined. */
fun foodKeyOf(names: List<String>): String =
    names.map { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() }
        .sorted()
        .joinToString(" + ")
