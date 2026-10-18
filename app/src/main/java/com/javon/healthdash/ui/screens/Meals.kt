package com.javon.healthdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.DayPart
import com.javon.healthdash.calc.MINUTE_MS
import com.javon.healthdash.calc.MealAnalytics
import com.javon.healthdash.calc.ResponseResult
import com.javon.healthdash.calc.TimeWindow
import com.javon.healthdash.domain.Analysis
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.domain.MealView
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.BottomNav
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.FilterChip
import com.javon.healthdash.ui.components.GlucoseChart
import com.javon.healthdash.ui.components.InsightRow
import com.javon.healthdash.ui.components.LargeTopBar
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.OverlayChart
import com.javon.healthdash.ui.components.PhotoBox
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScoreBadge
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SegmentedControl
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.Tab
import com.javon.healthdash.ui.components.Tag
import com.javon.healthdash.ui.components.TagType
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.launch

fun titleCase(key: String) = key.split(" + ").joinToString(", ") { it.replaceFirstChar(Char::uppercase) }

@Composable
fun MealPhoto(path: String?, modifier: Modifier) {
    if (path != null && File(path).exists()) {
        AsyncImage(model = File(path), contentDescription = "Meal photo", contentScale = ContentScale.Crop, modifier = modifier.clip(Shapes.lg))
    } else {
        PhotoBox(modifier)
    }
}

@Composable
fun MealsScreen(
    state: DashboardState,
    initialTab: Int,
    onTab: (Tab) -> Unit,
    onLogMeal: () -> Unit,
    onMeal: (Long) -> Unit,
    onFood: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    ScreenFrame(
        topBar = { LargeTopBar("Meals", if (tab == 0) Fmt.dayLong(state.nowMs) else "Last 90 days") },
        bottomBar = { BottomNav(Tab.Meals) { onTab(it) } },
    ) {
        SegmentedControl(listOf("Log", "Ranking", "Walk effect"), tab, { tab = it })
        when (tab) {
            0 -> MealLog(state, onLogMeal, onMeal)
            1 -> Ranking(state, onFood)
            else -> WalkEffectTab(state, onFood)
        }
    }
}

@Composable
private fun MealLog(state: DashboardState, onLogMeal: () -> Unit, onMeal: (Long) -> Unit) {
    AppButton("Log a meal", onLogMeal, Modifier.fillMaxWidth(), icon = AppIcons.camera)
    if (state.meals.isEmpty()) {
        EmptyState("No meals yet", "Log a meal with a photo, a usual meal, or by typing it in.")
        return
    }
    val zone = ZoneId.systemDefault()
    state.meals.take(60).groupBy { Fmt.localDate(it.event.timeMs) }.forEach { (_, meals) ->
        SectionHeader(Fmt.dayLabel(meals.first().event.timeMs, state.nowMs), "${meals.size} meal${if (meals.size == 1) "" else "s"}")
        RowGroup {
            meals.forEach { m -> MealRow(m, onMeal) }
        }
    }
    Caption("Peak rise above pre-meal baseline. \"—\" means glucose data for that window has not arrived yet.")
}

@Composable
fun MealRow(m: MealView, onMeal: (Long) -> Unit) {
    val sub = buildString {
        append(Fmt.time(m.event.timeMs)); append(" · "); append(Fmt.int(m.nutrients.carbsG)); append(" g carbs")
        if (m.incompleteItems > 0) append(" · incomplete")
        if (m.inTraining) append(" · training") else if (m.walk.walked) append(" · walked")
    }
    ListRow(
        m.title, subtitle = sub, trailing = m.response?.let { Fmt.signed(it.peakRise) } ?: "—",
        leading = { MealPhoto(m.data.meal.photoPath, Modifier.size(40.dp)) },
        onClick = { onMeal(m.id) },
    )
}

@Composable
private fun Ranking(state: DashboardState, onFood: (String) -> Unit) {
    var part by rememberSaveable { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
        listOf("All meals", "Breakfast", "Lunch", "Dinner").forEachIndexed { i, l -> FilterChip(l, part == i, { part = i }) }
    }
    val scored = when (part) {
        1 -> state.scored.filter { MealAnalytics.dayPart(it.meal.timeMs, zone) == DayPart.Morning }
        2 -> state.scored.filter { MealAnalytics.dayPart(it.meal.timeMs, zone) == DayPart.Midday }
        3 -> state.scored.filter { MealAnalytics.dayPart(it.meal.timeMs, zone) == DayPart.Evening }
        else -> state.scored
    }
    val ranks = MealAnalytics.rank(scored, state.targets)
    SectionHeader("Best to worst")
    if (ranks.isEmpty()) {
        EmptyState("Not enough meals to rank", "A food appears once it has at least ${state.targets.minRankInstances} logged meals with glucose data.")
    } else {
        RowGroup {
            ranks.forEachIndexed { i, r ->
                ListRow(
                    titleCase(r.foodKey), subtitle = "${r.count} meals · avg ${Fmt.signed(r.avgRise)}",
                    leading = { Text("${i + 1}", style = Theme.type.labelL, color = Theme.colors.textTertiary, modifier = Modifier.width(16.dp)) },
                    trailingContent = { ScoreBadge(r.avgScore.toInt()) }, chevron = false,
                    onClick = { onFood(r.foodKey) },
                )
            }
        }
    }
    Caption("Ranked by average response score. Foods need at least ${state.targets.minRankInstances} logged meals with glucose data. Training-window meals are excluded.")
}

@Composable
private fun WalkEffectTab(state: DashboardState, onFood: (String) -> Unit) {
    SectionHeader("Walk vs no walk")
    if (state.walkComparisons.isEmpty()) {
        EmptyState("No comparisons yet", "A food shows here once it has meals both with and without a walk afterwards.")
        return
    }
    RowGroup {
        state.walkComparisons.forEach { c ->
            ListRow(
                titleCase(c.foodKey),
                subtitle = "Walk ${c.walk.avgRise?.let { Fmt.signed(it) } ?: "—"} (${c.walk.count}) · No walk ${c.noWalk.avgRise?.let { Fmt.signed(it) } ?: "—"} (${c.noWalk.count})",
                icon = AppIcons.walk, onClick = { onFood(c.foodKey) },
            )
        }
    }
    Caption("Average peak rise above baseline. A walk = ${Fmt.int(state.targets.walkSteps)}+ steps or a workout within ${state.targets.walkWindowMin} min after eating.")
}

@Composable
fun MealDetailScreen(container: AppContainer, state: DashboardState, id: Long, onBack: () -> Unit, onFood: (String) -> Unit) {
    val m = state.meal(id)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    if (m == null) {
        ScreenFrame(topBar = { SmallTopBar("Meal", onBack) }) { EmptyState("Meal not found", "It may have been deleted.") }
        return
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this meal?") },
            text = { Text("The meal and its foods are removed from this phone.") },
            confirmButton = { TextButton({ scope.launch { container.dao.deleteMeal(id); onBack() } }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
    val c = Theme.colors
    val t0 = m.event.timeMs
    ScreenFrame(topBar = { SmallTopBar("${Fmt.dayLabel(t0, state.nowMs)} · ${Fmt.time(t0)}", onBack) }) {
        MealPhoto(m.data.meal.photoPath, Modifier.fillMaxWidth().height(200.dp))
        when (val r = m.result) {
            is ResponseResult.Ready -> {
                AppCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd), verticalAlignment = Alignment.CenterVertically) {
                        ScoreBadge(m.score!!)
                        Column {
                            Text("Response score ${m.score}", style = Theme.type.headingH3, color = c.textPrimary)
                            Text(
                                "Peak ${Fmt.signed(r.response.peakRise)} mg/dL at ${r.response.timeToPeakMin} min" +
                                    (r.response.backToBaselineMin?.let { ", back to baseline at $it min" } ?: ", not back to baseline within 2 h"),
                                style = Theme.type.bodyS, color = c.textSecondary,
                            )
                        }
                    }
                    MetricTiles(
                        listOf(
                            "Baseline" to "${r.response.baseline.toInt()}",
                            "Peak rise" to Fmt.signed(r.response.peakRise),
                            "Time to peak" to "${r.response.timeToPeakMin} min",
                            "Back to baseline" to (r.response.backToBaselineMin?.let { "$it min" } ?: "> 120 min"),
                            "AUC 2 h" to Fmt.int(r.response.auc),
                            "Steps after" to Fmt.int(m.walk.steps),
                        ),
                    )
                }
            }
            is ResponseResult.Incomplete -> EmptyState("Waiting for glucose data", "Readings cover ${Fmt.int(r.coverage * 100)}% of the 2 hours after this meal. The response is computed once coverage reaches ${Fmt.int(state.targets.minCoverage * 100)}% — late CGM data is picked up automatically.")
            ResponseResult.NoBaseline -> EmptyState("No baseline", "There are no glucose readings in the ${state.targets.baselineMin} minutes before this meal, so its response can't be computed.")
        }
        GlucoseChart(
            "Glucose · 2 hours", state.glucose, TimeWindow(t0 - 15 * MINUTE_MS, t0 + 120 * MINUTE_MS),
            ticks = listOf(-15, 15, 45, 75, 105).map { Fmt.time(t0 + it * MINUTE_MS) },
            meals = listOf(t0), workouts = state.workoutWindows, targets = state.targets,
        )
        val noWalk = state.scored.filter { it.meal.foodKey == m.event.foodKey && !it.walk.walked && it.meal.id != m.id }
        val walkStatement = when {
            m.walk.workout -> "A workout started within ${state.targets.walkWindowMin} min after this meal."
            m.walk.walked -> "You walked ${Fmt.int(m.walk.steps)} steps within ${state.targets.walkWindowMin} min." +
                (if (noWalk.size >= state.targets.minForecastInstances) " Your no-walk average for this meal is ${Fmt.signed(noWalk.map { it.response.peakRise }.average())}." else "")
            else -> "${Fmt.int(m.walk.steps)} steps within ${state.targets.walkWindowMin} min — below the ${Fmt.int(state.targets.walkSteps)}-step walk threshold."
        }
        RowGroup { InsightRow(walkStatement, if (noWalk.isEmpty()) "Steps" else "${noWalk.size + 1} meals", onTrace = { onFood(m.event.foodKey) }) }

        SectionHeader("Foods")
        RowGroup {
            m.data.items.forEach { item ->
                val n = Analysis.itemNutrients(item)
                ListRow(
                    item.name.replaceFirstChar(Char::uppercase),
                    subtitle = buildString {
                        append(item.grams?.let { "${Fmt.amount(item.amount)} ${item.unit} · ${Fmt.grams(it)}" } ?: "${Fmt.amount(item.amount)} ${item.unit} · grams unknown")
                        append(" · "); append(n?.let { "${Fmt.int(it.carbsG)} g carbs" } ?: "no nutrition")
                        item.fdcDescription?.let { append("\n"); append(if (item.fdcId != null) "USDA · $it" else it) }
                    },
                    icon = AppIcons.meal, chevron = false,
                )
            }
        }
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Nutrition", style = Theme.type.labelL, color = c.textPrimary)
                Text("USDA FoodData Central", style = Theme.type.caption, color = c.textTertiary)
            }
            MetricTiles(
                listOf(
                    "Carbs" to "${Fmt.int(m.nutrients.carbsG)} g", "Protein" to "${Fmt.int(m.nutrients.proteinG)} g", "Fat" to "${Fmt.int(m.nutrients.fatG)} g",
                    "Fiber" to "${Fmt.int(m.nutrients.fiberG)} g", "kcal" to Fmt.int(m.nutrients.kcal),
                ),
            )
            if (m.incompleteItems > 0) Caption("${m.incompleteItems} food(s) have no grams or USDA match and are not counted.")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
            m.data.meal.tags.split(",").filter { it.isNotBlank() }.forEach { Tag(it, TagType.Neutral) }
            if (m.data.meal.aiResultKey != null) Tag("AI-recognized · confirmed", TagType.AI)
            if (m.data.meal.sample) Tag("Sample data", TagType.Derived)
        }
        AppButton("Delete meal", { confirmDelete = true }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
    }
}

@Composable
fun FoodDetailScreen(state: DashboardState, key: String, onBack: () -> Unit, onMeal: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val meals = state.scored.filter { it.meal.foodKey == key && !it.inTrainingWindow }
    val walk = meals.filter { it.walk.walked }
    val noWalk = meals.filter { !it.walk.walked }
    ScreenFrame(topBar = { SmallTopBar(titleCase(key), onBack) }) {
        if (meals.isEmpty()) {
            EmptyState("No scored meals", "Meals of this food have no complete glucose response yet.")
            return@ScreenFrame
        }
        MetricTiles(
            listOf(
                "Avg peak rise" to Fmt.signed(meals.map { it.response.peakRise }.average()),
                "Logged" to "${meals.size}×",
                "Avg score" to Fmt.int(meals.map { it.score.toDouble() }.average()),
            ),
        )
        val c = Theme.colors
        val walkCurve = MealAnalytics.averageCurve(walk.map { it.meal }, state.glucose, state.targets)
        val noWalkCurve = MealAnalytics.averageCurve(noWalk.map { it.meal }, state.glucose, state.targets)
        OverlayChart(
            "Walk vs no walk · 2 h",
            listOfNotNull(
                walkCurve?.let { Triple("Walk", c.chartWalk, it) },
                noWalkCurve?.let { Triple("No walk", c.chartNoWalk, it) },
            ),
        )
        AppCard {
            Text("Walk effect", style = Theme.type.labelL, color = c.textPrimary)
            MetricTiles(
                listOf(
                    "With walk · ${walk.size} meals" to (walk.takeIf { it.isNotEmpty() }?.let { Fmt.signed(it.map { m -> m.response.peakRise }.average()) } ?: "—"),
                    "No walk · ${noWalk.size} meals" to (noWalk.takeIf { it.isNotEmpty() }?.let { Fmt.signed(it.map { m -> m.response.peakRise }.average()) } ?: "—"),
                ),
                columns = 2,
            )
        }
        SectionHeader("Pre-meal forecast")
        val today = java.time.LocalDate.now(zone)
        RowGroup {
            listOf(DayPart.Morning to 8, DayPart.Midday to 13, DayPart.Evening to 19).forEach { (part, hour) ->
                val at = today.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                val f = MealAnalytics.forecast(key, at, state.scored, zone, state.targets)
                val n = f.walk.count + f.noWalk.count
                ListRow(
                    part.name,
                    subtitle = if (!f.hasAny) "$n meal${if (n == 1) "" else "s"} — not enough data" else
                        "Walk ${f.walk.avgRise?.let { Fmt.signed(it) } ?: "—"} (${f.walk.count}) · No walk ${f.noWalk.avgRise?.let { Fmt.signed(it) } ?: "—"} (${f.noWalk.count})",
                    chevron = false,
                )
            }
        }
        if (walk.size >= state.targets.minForecastInstances && noWalk.size >= state.targets.minForecastInstances) {
            val diff = noWalk.map { it.response.peakRise }.average() - walk.map { it.response.peakRise }.average()
            RowGroup {
                InsightRow(
                    if (diff > 0) "Walking after this meal lowered your average peak by ${diff.toInt()} mg/dL." else "Walking after this meal did not lower your average peak.",
                    "${meals.size} meals",
                )
            }
        }
        SectionHeader("Meals")
        RowGroup {
            meals.sortedByDescending { it.meal.timeMs }.forEach { s ->
                state.meal(s.meal.id)?.let { MealRow(it, onMeal) }
            }
        }
    }
}
