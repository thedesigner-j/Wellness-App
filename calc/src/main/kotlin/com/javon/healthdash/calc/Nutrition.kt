package com.javon.healthdash.calc

/** Nutrient values per 100 g, as published by USDA FoodData Central. Null = not reported. */
data class NutrientsPer100g(
    val carbsG: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val fiberG: Double?,
    val kcal: Double?,
)

data class Nutrients(
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val fiberG: Double,
    val kcal: Double,
) {
    operator fun plus(o: Nutrients) = Nutrients(carbsG + o.carbsG, proteinG + o.proteinG, fatG + o.fatG, fiberG + o.fiberG, kcal + o.kcal)

    companion object {
        val ZERO = Nutrients(0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/** A household measure from USDA (e.g. "1 cup" = 195 g). */
data class Portion(val amount: Double, val unit: String, val gramWeight: Double)

object NutritionMath {

    /** Nutrients for [grams] of a food. Missing values count as 0 and are reported by [missingFields]. */
    fun forGrams(per100g: NutrientsPer100g, grams: Double): Nutrients {
        val f = grams / 100.0
        return Nutrients(
            carbsG = (per100g.carbsG ?: 0.0) * f,
            proteinG = (per100g.proteinG ?: 0.0) * f,
            fatG = (per100g.fatG ?: 0.0) * f,
            fiberG = (per100g.fiberG ?: 0.0) * f,
            kcal = (per100g.kcal ?: 0.0) * f,
        )
    }

    fun missingFields(per100g: NutrientsPer100g): List<String> = buildList {
        if (per100g.carbsG == null) add("carbs")
        if (per100g.proteinG == null) add("protein")
        if (per100g.fatG == null) add("fat")
        if (per100g.fiberG == null) add("fiber")
        if (per100g.kcal == null) add("calories")
    }

    private val unitAliases = mapOf(
        "g" to "g", "gram" to "g", "grams" to "g",
        "oz" to "oz", "ounce" to "oz", "ounces" to "oz",
        "lb" to "lb", "pound" to "lb",
        "ml" to "ml", "milliliter" to "ml",
        "cup" to "cup", "cups" to "cup",
        "tbsp" to "tbsp", "tablespoon" to "tbsp", "tablespoons" to "tbsp",
        "tsp" to "tsp", "teaspoon" to "tsp", "teaspoons" to "tsp",
        "slice" to "slice", "slices" to "slice",
        "piece" to "piece", "pieces" to "piece", "each" to "piece", "medium" to "piece", "large" to "piece", "small" to "piece",
        "serving" to "serving", "servings" to "serving",
    )

    fun normalizeUnit(unit: String): String {
        val u = unit.trim().lowercase()
        return unitAliases[u] ?: u
    }

    /**
     * Converts an amount in a household unit to grams.
     * Mass units convert exactly; other units use a matching USDA portion. Returns null when no
     * conversion is known, so the user is asked for grams instead of the app guessing.
     */
    fun toGrams(amount: Double, unit: String, portions: List<Portion>): Double? {
        val u = normalizeUnit(unit)
        when (u) {
            "g" -> return amount
            "oz" -> return amount * 28.349523125
            "lb" -> return amount * 453.59237
        }
        val match = portions.firstOrNull { normalizeUnit(it.unit) == u }
            ?: portions.firstOrNull { it.unit.lowercase().contains(u) }
            ?: return null
        if (match.amount <= 0) return null
        return amount * match.gramWeight / match.amount
    }
}
