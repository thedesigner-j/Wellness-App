package com.javon.healthdash.calc

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Sample data so screens can be built and tested before real data arrives (SPEC §12). */
data class FakeFood(val name: String, val grams: Double, val per100g: NutrientsPer100g)

data class FakeMeal(val timeMs: Long, val foods: List<FakeFood>, val walked: Boolean)

data class FakeWorkout(val window: TimeWindow, val title: String, val distanceKm: Double)

data class FakeDataset(
    val glucose: List<GlucosePoint>,
    val steps: List<StepInterval>,
    val workouts: List<FakeWorkout>,
    val meals: List<FakeMeal>,
    val weightsLb: List<Pair<Long, Double>>,
    val bodyFatPct: List<Pair<Long, Double>>,
)

object FakeData {

    private val oats = FakeFood("Oatmeal", 240.0, NutrientsPer100g(12.0, 2.5, 1.5, 1.7, 71.0))
    private val berries = FakeFood("Blueberries", 110.0, NutrientsPer100g(14.5, 0.7, 0.3, 2.4, 57.0))
    private val chicken = FakeFood("Grilled chicken breast", 140.0, NutrientsPer100g(0.0, 31.0, 3.6, 0.0, 165.0))
    private val rice = FakeFood("Brown rice", 195.0, NutrientsPer100g(23.0, 2.6, 0.9, 1.8, 112.0))
    private val broccoli = FakeFood("Broccoli", 156.0, NutrientsPer100g(7.2, 2.4, 0.4, 3.3, 35.0))
    private val salmon = FakeFood("Salmon", 150.0, NutrientsPer100g(0.0, 22.0, 12.0, 0.0, 206.0))
    private val sweetPotato = FakeFood("Sweet potato", 150.0, NutrientsPer100g(20.7, 2.0, 0.2, 3.3, 90.0))
    private val yogurt = FakeFood("Greek yogurt", 200.0, NutrientsPer100g(3.6, 10.0, 0.4, 0.0, 59.0))
    private val granola = FakeFood("Granola", 45.0, NutrientsPer100g(64.0, 10.0, 20.0, 7.0, 471.0))
    private val bagel = FakeFood("Bagel", 105.0, NutrientsPer100g(50.0, 10.0, 1.7, 2.1, 257.0))
    private val pancakes = FakeFood("Pancakes", 150.0, NutrientsPer100g(43.0, 6.0, 9.0, 1.4, 227.0))

    private val breakfasts = listOf(listOf(oats, berries), listOf(yogurt, granola), listOf(pancakes))
    private val lunches = listOf(listOf(chicken, rice, broccoli), listOf(chicken, rice, broccoli))
    private val dinners = listOf(listOf(salmon, sweetPotato, broccoli), listOf(chicken, sweetPotato))

    fun generate(nowMs: Long, days: Int = 21, zone: ZoneId = ZoneId.systemDefault(), seed: Int = 7): FakeDataset {
        val rnd = Random(seed)
        val start = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(days.toLong() - 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val meals = mutableListOf<FakeMeal>()
        val workouts = mutableListOf<FakeWorkout>()
        val steps = mutableListOf<StepInterval>()
        val weights = mutableListOf<Pair<Long, Double>>()
        val fats = mutableListOf<Pair<Long, Double>>()

        for (d in 0 until days) {
            val day = start + d * DAY_MS
            fun at(h: Double) = day + (h * HOUR_MS).toLong()
            if (d % 2 == 0) {
                val w = TimeWindow(at(6.0), at(6.0 + if (d % 6 == 0) 2.2 else 0.9))
                workouts += FakeWorkout(w, if (d % 6 == 0) "Long run" else "Easy run", if (d % 6 == 0) 25.7 else 9.6)
            }
            val plan = listOf(
                at(7.5 + rnd.nextDouble(-0.4, 0.4)) to breakfasts[rnd.nextInt(breakfasts.size)],
                at(12.6 + rnd.nextDouble(-0.4, 0.4)) to lunches[rnd.nextInt(lunches.size)],
                at(19.0 + rnd.nextDouble(-0.5, 0.5)) to dinners[rnd.nextInt(dinners.size)],
            )
            for ((t, foods) in plan) meals += FakeMeal(t, foods, walked = rnd.nextDouble() < 0.6)
            weights += at(6.6) to (174.2 - d * 0.08 + rnd.nextDouble(-0.4, 0.4))
            fats += at(6.6) to (18.9 - d * 0.03 + rnd.nextDouble(-0.3, 0.3))
            for (h in 7 until 22) steps += StepInterval(at(h.toDouble()), at(h + 1.0), (250 + rnd.nextInt(250)).toLong())
        }
        for (m in meals.filter { it.walked }) steps += StepInterval(m.timeMs + 10 * MINUTE_MS, m.timeMs + 35 * MINUTE_MS, 1300L + rnd.nextInt(800))

        val glucose = mutableListOf<GlucosePoint>()
        var t = start
        while (t <= nowMs) {
            val hour = Instant.ofEpochMilli(t).atZone(zone).let { it.hour + it.minute / 60.0 }
            var g = 92.0 + 4 * sin((hour - 4) / 24.0 * 2 * PI)
            for (m in meals) {
                if (t < m.timeMs || t > m.timeMs + 4 * HOUR_MS) continue
                val carbs = m.foods.sumOf { (it.per100g.carbsG ?: 0.0) * it.grams / 100 }
                val amp = carbs * 0.62 * (if (m.walked) 0.6 else 1.0)
                val x = (t - m.timeMs) / (40.0 * MINUTE_MS)
                g += amp * x.pow(2) * exp(2 * (1 - x)) * 0.9
            }
            for (w in workouts) if (t in w.window) g -= 8.0
            g += rnd.nextDouble(-2.5, 2.5)
            // Sensor gap on one day to exercise the "not enough data" paths.
            val dayIndex = ((t - start) / DAY_MS).toInt()
            val gap = dayIndex == days - 5 && hour in 9.0..21.0
            if (!gap) glucose += GlucosePoint(t, g)
            t += 5 * MINUTE_MS
        }
        return FakeDataset(glucose, steps, workouts, meals, weights, fats)
    }
}
