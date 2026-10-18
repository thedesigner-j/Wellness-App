package com.javon.healthdash.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One food the model saw. Names and portions only — never nutrition numbers (SPEC rule 2). */
@Serializable
data class RecognizedFood(
    val food_name: String,
    val portion_estimate: Double,
    val portion_unit: String,
    val confidence: String,
)

@Serializable
data class RecognitionPayload(val foods: List<RecognizedFood>)

/**
 * Strict validation of the model's JSON (SPEC rule 6). Anything outside the schema — extra fields,
 * nutrition numbers, out-of-range portions, unknown units — fails, and the caller falls back.
 */
object RecognitionValidator {
    const val PROMPT_VERSION = "food-v1"
    val UNITS = listOf("g", "oz", "cup", "tbsp", "tsp", "piece", "slice", "serving", "ml")
    val CONFIDENCE = listOf("high", "medium", "low")

    private val strictJson = Json { ignoreUnknownKeys = false; isLenient = false }

    /** The JSON schema sent as the structured-output format. */
    val schema: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("foods"),
        "properties" to mapOf(
            "foods" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("food_name", "portion_estimate", "portion_unit", "confidence"),
                    "properties" to mapOf(
                        "food_name" to mapOf("type" to "string", "description" to "Plain food name a nutrition database would recognize, e.g. \"brown rice, cooked\""),
                        "portion_estimate" to mapOf("type" to "number", "description" to "Estimated amount in portion_unit"),
                        "portion_unit" to mapOf("type" to "string", "enum" to UNITS),
                        "confidence" to mapOf("type" to "string", "enum" to CONFIDENCE),
                    ),
                ),
            ),
        ),
    )

    sealed interface Result {
        data class Valid(val foods: List<RecognizedFood>) : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(text: String): Result {
        val payload = runCatching { strictJson.decodeFromString(RecognitionPayload.serializer(), text.trim()) }
            .getOrElse { return Result.Invalid("Response was not valid JSON for the schema") }
        if (payload.foods.size > 12) return Result.Invalid("Too many foods")
        for (f in payload.foods) {
            val name = f.food_name.trim()
            if (name.isEmpty() || name.length > 80) return Result.Invalid("Bad food name")
            if (name.any { it.isDigit() } && Regex("\\d+\\s*(g|kcal|cal|carb)", RegexOption.IGNORE_CASE).containsMatchIn(name)) return Result.Invalid("Nutrition data in food name")
            if (!(f.portion_estimate > 0 && f.portion_estimate < 5000)) return Result.Invalid("Portion out of range")
            if (f.portion_unit !in UNITS) return Result.Invalid("Unknown unit")
            if (f.confidence !in CONFIDENCE) return Result.Invalid("Unknown confidence")
        }
        return Result.Valid(payload.foods.map { it.copy(food_name = it.food_name.trim()) })
    }

    const val SYSTEM_PROMPT = """You identify foods in a meal photo for a personal glucose log.
Return only the foods you can see, each with a portion estimate in one of the allowed units.
Do not include nutrition values, calories, carbs, or any other numbers besides the portion estimate.
Use plain, generic food names that a nutrition database would recognize (for example "brown rice, cooked", "grilled chicken breast", "blueberries").
List mixed dishes by their main components when they are visible. If the photo shows no food, return an empty list.
Use confidence "low" when the food or portion is hard to see."""

    const val USER_PROMPT = "List the foods in this photo with portion estimates."
}
