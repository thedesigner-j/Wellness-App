package com.javon.healthdash.data

import com.javon.healthdash.calc.NutrientsPer100g
import com.javon.healthdash.calc.Portion
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class UsdaFood(
    val fdcId: Long,
    val description: String,
    val dataType: String,
    val per100g: NutrientsPer100g,
)

class UsdaException(message: String) : IOException(message)

/**
 * USDA FoodData Central. All nutrition values in the app come from here (SPEC rule 3).
 * Foundation, SR Legacy and FNDDS foods report nutrients per 100 g.
 */
class UsdaClient(private val keys: SecureKeys) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://api.nal.usda.gov/fdc/v1"
    private val portionCache = mutableMapOf<Long, List<Portion>>()

    private fun key() = keys.get(SecureKeys.USDA) ?: throw UsdaException("Add a USDA FoodData Central key in Settings")

    private suspend fun get(url: okhttp3.HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw UsdaException(if (resp.code == 403) "USDA key was rejected" else "USDA lookup failed (${resp.code})")
            json.parseToJsonElement(resp.body.string()).jsonObject
        }
    }

    suspend fun search(query: String, pageSize: Int = 8): List<UsdaFood> {
        val url = "$base/foods/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("dataType", "Foundation,SR Legacy,Survey (FNDDS)")
            .addQueryParameter("pageSize", pageSize.toString())
            .addQueryParameter("api_key", key())
            .build()
        val foods = get(url)["foods"] as? JsonArray ?: return emptyList()
        return foods.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["fdcId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            UsdaFood(
                fdcId = id,
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                dataType = o["dataType"]?.jsonPrimitive?.contentOrNull ?: "",
                per100g = nutrients(o["foodNutrients"] as? JsonArray),
            )
        }
    }

    /** Household portions ("1 cup" = 195 g) used to convert amounts to grams. */
    suspend fun portions(fdcId: Long): List<Portion> {
        portionCache[fdcId]?.let { return it }
        val url = "$base/food/$fdcId".toHttpUrl().newBuilder().addQueryParameter("api_key", key()).build()
        val arr = get(url)["foodPortions"] as? JsonArray ?: JsonArray(emptyList())
        val list = arr.mapNotNull { el ->
            val o = el.jsonObject
            val grams = o["gramWeight"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val amount = o["amount"]?.jsonPrimitive?.doubleOrNull ?: 1.0
            val unitName = o["measureUnit"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            val modifier = o["modifier"]?.jsonPrimitive?.contentOrNull
            val desc = o["portionDescription"]?.jsonPrimitive?.contentOrNull
            val unit = listOfNotNull(unitName, modifier, desc)
                .map { it.replace(Regex("^[0-9./ ]+"), "").trim() }
                .firstOrNull { it.isNotEmpty() && !it.equals("undetermined", ignoreCase = true) && !it.equals("Quantity not specified", ignoreCase = true) }
                ?: return@mapNotNull null
            Portion(amount, unit, grams)
        }
        portionCache[fdcId] = list
        return list
    }

    private fun nutrients(arr: JsonArray?): NutrientsPer100g {
        val byNumber = mutableMapOf<String, Double>()
        arr?.forEach { el ->
            val o = el.jsonObject
            val num = o["nutrientNumber"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val unit = o["unitName"]?.jsonPrimitive?.contentOrNull ?: ""
            val v = o["value"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            if (num in setOf("208", "957", "958") && !unit.equals("KCAL", ignoreCase = true)) return@forEach
            byNumber.putIfAbsent(num, v)
        }
        return NutrientsPer100g(
            carbsG = byNumber["205"],
            proteinG = byNumber["203"],
            fatG = byNumber["204"],
            fiberG = byNumber["291"],
            kcal = byNumber["208"] ?: byNumber["958"] ?: byNumber["957"],
        )
    }
}
