package com.javon.healthdash.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.javon.healthdash.AppContainer
import com.javon.healthdash.HealthApp
import com.javon.healthdash.calc.Nutrients
import com.javon.healthdash.calc.NutrientsPer100g
import com.javon.healthdash.calc.NutritionMath
import com.javon.healthdash.calc.Portion
import com.javon.healthdash.data.UsdaFood
import com.javon.healthdash.data.ai.RecognitionOutcome
import com.javon.healthdash.data.db.MealEntity
import com.javon.healthdash.data.db.MealItemEntity
import com.javon.healthdash.data.db.MealWithItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogStep(val route: String) {
    Capture("log/capture"), Analyzing("log/analyzing"), Confirm("log/confirm"), Match("log/match"),
    Details("log/details"), Saved("log/saved"), Manual("log/manual"), Usual("log/usual"),
}

/** A food in the meal being logged. Grams come from the unit (mass units or a USDA portion) or the user. */
data class DraftItem(
    val name: String,
    val amount: Double,
    val unit: String,
    val confidence: String? = null,
    val ai: Boolean = false,
    val match: UsdaFood? = null,
    val portions: List<Portion> = emptyList(),
    val gramsOverride: Double? = null,
) {
    val grams: Double? get() = gramsOverride ?: NutritionMath.toGrams(amount, unit, portions)
    val nutrients: Nutrients? get() { val g = grams ?: return null; val m = match ?: return null; return NutritionMath.forGrams(m.per100g, g) }
}

data class LogState(
    val photoPath: String? = null,
    val timeMs: Long = System.currentTimeMillis(),
    val items: List<DraftItem> = emptyList(),
    val aiKey: String? = null,
    val aiCached: Boolean = false,
    val failure: String? = null,
    val matchIndex: Int = 0,
    val query: String = "",
    val results: List<UsdaFood> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val selectedFdc: Long? = null,
    val tags: Set<String> = emptySet(),
    val saveUsual: Boolean = false,
    val usualName: String = "",
    val savedMealId: Long? = null,
) {
    val total: Nutrients get() = items.mapNotNull { it.nutrients }.fold(Nutrients.ZERO) { a, b -> a + b }
    val incomplete: Int get() = items.count { it.nutrients == null }
}

class LogMealViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LogState())
    val state: StateFlow<LogState> = _state.asStateFlow()

    fun start() { _state.value = LogState() }

    fun newCaptureUri(context: Context): Uri {
        val f = c.photos.newCaptureFile()
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    /** Stores the photo, runs recognition, and returns the next step (Confirm, or Manual on failure). */
    suspend fun onPhoto(uri: Uri): LogStep {
        val photo = runCatching { c.photos.store(uri) }.getOrElse {
            _state.update { s -> s.copy(failure = "Couldn't read that photo") }
            return LogStep.Manual
        }
        _state.update { it.copy(photoPath = photo.path, timeMs = photo.takenAtMs?.takeIf { t -> t <= System.currentTimeMillis() } ?: it.timeMs) }
        if (!c.prefs.state.value.foodRecognition) {
            _state.update { it.copy(failure = "Food recognition is turned off in Settings.") }
            return LogStep.Manual
        }
        return when (val r = c.recognizer.recognize(photo.jpeg)) {
            is RecognitionOutcome.Success -> {
                if (r.foods.isEmpty()) {
                    _state.update { it.copy(failure = "No food was recognized in this photo.") }
                    LogStep.Manual
                } else {
                    _state.update { s ->
                        s.copy(
                            aiKey = r.resultKey, aiCached = r.cached, failure = null,
                            items = r.foods.map { DraftItem(it.food_name, it.portion_estimate, it.portion_unit, it.confidence, ai = true) },
                        )
                    }
                    LogStep.Confirm
                }
            }
            is RecognitionOutcome.Failed -> {
                _state.update { it.copy(failure = r.reason) }
                LogStep.Manual
            }
        }
    }

    fun adjust(i: Int, up: Boolean) = _state.update { s ->
        val it = s.items[i]
        val step = when (NutritionMath.normalizeUnit(it.unit)) { "g", "ml" -> 10.0; "oz" -> 1.0; "cup" -> 0.25; else -> 0.5 }
        val next = (it.amount + if (up) step else -step).coerceAtLeast(step)
        s.copy(items = s.items.toMutableList().also { l -> l[i] = it.copy(amount = next, gramsOverride = null) })
    }

    fun remove(i: Int) = _state.update { s -> s.copy(items = s.items.filterIndexed { j, _ -> j != i }) }

    fun setGrams(i: Int, grams: Double?) = _state.update { s -> s.copy(items = s.items.toMutableList().also { l -> l[i] = l[i].copy(gramsOverride = grams) }) }

    fun setTime(ms: Long) = _state.update { it.copy(timeMs = ms) }
    fun toggleTag(tag: String) = _state.update { s -> s.copy(tags = if (tag in s.tags) s.tags - tag else s.tags + tag) }
    fun setSaveUsual(v: Boolean) = _state.update { it.copy(saveUsual = v) }
    fun setUsualName(v: String) = _state.update { it.copy(usualName = v) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun select(fdcId: Long) = _state.update { it.copy(selectedFdc = fdcId) }

    /** First item without a USDA match, or null when all are matched. */
    fun nextUnmatched(): Int? = _state.value.items.indexOfFirst { it.match == null }.takeIf { it >= 0 }

    fun beginMatch(i: Int) = _state.update { it.copy(matchIndex = i, query = it.items.getOrNull(i)?.name ?: "", results = emptyList(), selectedFdc = null, searchError = null) }

    suspend fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        _state.update { it.copy(searching = true, searchError = null) }
        runCatching { c.usda.search(q) }
            .onSuccess { r -> _state.update { it.copy(searching = false, results = r, selectedFdc = it.selectedFdc ?: r.firstOrNull()?.fdcId) } }
            .onFailure { e -> _state.update { it.copy(searching = false, results = emptyList(), searchError = e.message ?: "USDA lookup failed") } }
    }

    /** Applies the selected USDA match to the current item (fetching portions for unit conversion). */
    suspend fun confirmMatch() {
        val s = _state.value
        val food = s.results.firstOrNull { it.fdcId == s.selectedFdc } ?: return
        val portions = runCatching { c.usda.portions(food.fdcId) }.getOrDefault(emptyList())
        _state.update { st -> st.copy(items = st.items.toMutableList().also { l -> l[st.matchIndex] = l[st.matchIndex].copy(match = food, portions = portions) }) }
    }

    /** Manual entry: add a USDA result as a new 100 g item named after the search text. */
    suspend fun addManual(food: UsdaFood) {
        val portions = runCatching { c.usda.portions(food.fdcId) }.getOrDefault(emptyList())
        val name = _state.value.query.trim().ifEmpty { food.description }
        _state.update { it.copy(items = it.items + DraftItem(name.lowercase(), 100.0, "g", match = food, portions = portions)) }
    }

    fun useUsual(meal: MealWithItems) = _state.update { s ->
        s.copy(
            items = meal.items.map {
                DraftItem(
                    name = it.name, amount = it.amount, unit = it.unit,
                    match = if (it.fdcId != null || it.carbs100 != null) UsdaFood(it.fdcId ?: 0, it.fdcDescription ?: it.name, "", NutrientsPer100g(it.carbs100, it.protein100, it.fat100, it.fiber100, it.kcal100)) else null,
                    gramsOverride = it.grams,
                )
            },
            tags = meal.meal.tags.split(",").filter { it.isNotBlank() }.toSet(),
            timeMs = System.currentTimeMillis(),
        )
    }

    suspend fun save(context: Context): Long {
        val s = _state.value
        val meal = MealEntity(
            timeMs = s.timeMs, photoPath = s.photoPath, tags = s.tags.joinToString(","),
            usualName = if (s.saveUsual) s.usualName.ifBlank { s.items.joinToString(", ") { it.name } } else null,
            aiResultKey = s.aiKey,
        )
        val items = s.items.map { d ->
            MealItemEntity(
                mealId = 0, name = d.name, amount = d.amount, unit = d.unit, grams = d.grams,
                fdcId = d.match?.fdcId?.takeIf { it > 0 }, fdcDescription = d.match?.description,
                carbs100 = d.match?.per100g?.carbsG, protein100 = d.match?.per100g?.proteinG, fat100 = d.match?.per100g?.fatG,
                fiber100 = d.match?.per100g?.fiberG, kcal100 = d.match?.per100g?.kcal, aiConfidence = d.confidence,
            )
        }
        val id = c.dao.saveMeal(meal, items)
        _state.update { it.copy(savedMealId = id) }
        if (c.prefs.state.value.walkNudge && s.timeMs > System.currentTimeMillis() - 15 * 60_000) {
            HealthApp.scheduleWalkNudge(context, s.items.joinToString(", ") { it.name }.replaceFirstChar(Char::uppercase))
        }
        return id
    }
}
