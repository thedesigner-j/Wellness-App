package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class MealAnalyticsTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun scored(id: Long, key: String, rise: Double, score: Int, walked: Boolean, hour: Int = 12, training: Boolean = false) =
        ScoredMeal(
            meal = MealEvent(id, id * DAY_MS + hour * HOUR_MS, key),
            response = MealResponse(100.0, 100 + rise, rise, 40, 90, rise * 60, 1.0),
            score = score,
            walk = WalkEffect(if (walked) 1500 else 200, false, walked),
            inTrainingWindow = training,
        )

    @Test fun rankNeedsTwoMealsAndSortsByScore() {
        val meals = listOf(
            scored(1, "salmon", 18.0, 88, true), scored(2, "salmon", 20.0, 86, false),
            scored(3, "pancakes", 70.0, 28, false), scored(4, "pancakes", 72.0, 30, false),
            scored(5, "bagel", 40.0, 60, false), // only one → not ranked
        )
        val ranks = MealAnalytics.rank(meals)
        assertThat(ranks.map { it.foodKey }).containsExactly("salmon", "pancakes").inOrder()
        assertThat(ranks[0].avgScore).isWithin(1e-9).of(87.0)
        assertThat(ranks[0].avgRise).isWithin(1e-9).of(19.0)
        assertThat(ranks[1].count).isEqualTo(2)
    }

    @Test fun rankExcludesTrainingMeals() {
        val meals = listOf(scored(1, "gel", 50.0, 40, false, training = true), scored(2, "gel", 50.0, 40, false, training = true))
        assertThat(MealAnalytics.rank(meals)).isEmpty()
    }

    @Test fun walkComparison() {
        val meals = listOf(
            scored(1, "rice", 20.0, 80, true), scored(2, "rice", 24.0, 76, true),
            scored(3, "rice", 40.0, 60, false),
        )
        val c = MealAnalytics.walkComparisons(meals).single()
        assertThat(c.walk.count).isEqualTo(2)
        assertThat(c.walk.avgRise).isWithin(1e-9).of(22.0)
        assertThat(c.noWalk.avgRise).isWithin(1e-9).of(40.0)
    }

    @Test fun forecastNeedsTwoPastMealsPerBranch() {
        val history = listOf(
            scored(1, "rice", 20.0, 80, true), scored(2, "rice", 24.0, 76, true),
            scored(3, "rice", 40.0, 60, false),
            scored(4, "rice", 10.0, 90, true, hour = 8), // different part of day
        )
        val f = MealAnalytics.forecast("rice", 10 * DAY_MS + 13 * HOUR_MS, history, utc)
        assertThat(f.dayPart).isEqualTo(DayPart.Midday)
        assertThat(f.walk).isEqualTo(ForecastBranch(2, 22.0))
        assertThat(f.noWalk).isEqualTo(ForecastBranch(1, null))
        assertThat(f.hasAny).isTrue()
        assertThat(MealAnalytics.forecast("salad", 13 * HOUR_MS, history, utc).hasAny).isFalse()
    }

    @Test fun dayParts() {
        assertThat(MealAnalytics.dayPart(7 * HOUR_MS, utc)).isEqualTo(DayPart.Morning)
        assertThat(MealAnalytics.dayPart(11 * HOUR_MS, utc)).isEqualTo(DayPart.Midday)
        assertThat(MealAnalytics.dayPart(16 * HOUR_MS, utc)).isEqualTo(DayPart.Evening)
    }

    @Test fun foodKeyIsOrderAndCaseInsensitive() {
        assertThat(foodKeyOf(listOf("Brown Rice", " chicken  breast "))).isEqualTo("brown rice + chicken breast")
        assertThat(foodKeyOf(listOf("chicken breast", "brown rice"))).isEqualTo("brown rice + chicken breast")
    }

    @Test fun unloggedRiseDetected() {
        val pts = (0..36).map { i ->
            val t = i * 5 * MINUTE_MS
            val g = if (i < 12) 100.0 else (100.0 + (i - 12) * 7.0).coerceAtMost(142.0)
            GlucosePoint(t, g)
        }
        val found = UnloggedMeals.detect(pts, emptyList(), emptyList())
        assertThat(found).hasSize(1)
        assertThat(found[0].rise).isAtLeast(30.0)
        assertThat(found[0].riseStartMs).isEqualTo(55 * MINUTE_MS)

        val logged = listOf(MealEvent(1, 50 * MINUTE_MS, "x"))
        assertThat(UnloggedMeals.detect(pts, logged, emptyList())).isEmpty()
        val workout = listOf(TimeWindow(30 * MINUTE_MS, 50 * MINUTE_MS))
        assertThat(UnloggedMeals.detect(pts, emptyList(), workout)).isEmpty()
    }

    @Test fun nutritionMath() {
        val rice = NutrientsPer100g(23.0, 2.6, 0.9, 1.8, 112.0)
        val n = NutritionMath.forGrams(rice, 195.0)
        assertThat(n.carbsG).isWithin(1e-9).of(44.85)
        assertThat(n.kcal).isWithin(1e-9).of(218.4)
        assertThat(NutritionMath.missingFields(NutrientsPer100g(1.0, null, 1.0, null, 1.0))).containsExactly("protein", "fiber")
    }

    @Test fun portionConversion() {
        assertThat(NutritionMath.toGrams(5.0, "oz", emptyList())).isWithin(1e-6).of(141.747616)
        assertThat(NutritionMath.toGrams(150.0, "grams", emptyList())).isWithin(1e-9).of(150.0)
        val portions = listOf(Portion(1.0, "cup", 195.0), Portion(2.0, "tbsp", 30.0))
        assertThat(NutritionMath.toGrams(1.5, "cups", portions)).isWithin(1e-9).of(292.5)
        assertThat(NutritionMath.toGrams(1.0, "tablespoon", portions)).isWithin(1e-9).of(15.0)
        assertThat(NutritionMath.toGrams(2.0, "slice", portions)).isNull()
    }

    @Test fun fakeDataIsDeterministicAndUsable() {
        val now = 30 * DAY_MS
        val a = FakeData.generate(now, days = 14, zone = utc)
        val b = FakeData.generate(now, days = 14, zone = utc)
        assertThat(a.glucose).isEqualTo(b.glucose)
        assertThat(a.meals).hasSize(42)
        val scoredCount = a.meals.count { m ->
            MealResponses.compute(MealEvent(0, m.timeMs, "k"), a.glucose) is ResponseResult.Ready
        }
        assertThat(scoredCount).isAtLeast(30)
    }
}
