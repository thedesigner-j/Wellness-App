package com.javon.healthdash.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.javon.healthdash.HealthApp
import com.javon.healthdash.calc.MealAnalytics
import com.javon.healthdash.calc.foodKeyOf
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.AppIconButton
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.ButtonSize
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.FilterChip
import com.javon.healthdash.ui.components.IconButtonStyle
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.Notice
import com.javon.healthdash.ui.components.NoticeType
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.Stepper
import com.javon.healthdash.ui.components.Tag
import com.javon.healthdash.ui.components.TagType
import com.javon.healthdash.ui.components.Toggle
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

val MEAL_TAGS = listOf("Veggies / protein first", "Caffeine", "Alcohol", "Late meal", "Sick", "Stressed")

fun NavGraphBuilder.logMealGraph(
    nav: NavHostController,
    vm: LogMealViewModel,
    dash: DashboardState,
    onDone: () -> Unit,
    onViewMeal: (Long) -> Unit,
) {
    val back: () -> Unit = { nav.popBackStack() }
    /** After confirming foods: match the next unmatched item, or go to details. */
    val advance: () -> Unit = {
        val next = vm.nextUnmatched()
        if (next != null) { vm.beginMatch(next); nav.navigate(LogStep.Match.route) } else nav.navigate(LogStep.Details.route)
    }
    composable(LogStep.Capture.route) { CaptureScreen(vm, nav, back) }
    composable(LogStep.Analyzing.route) { AnalyzingScreen(vm, back, onManual = { nav.navigate(LogStep.Manual.route) }) }
    composable(LogStep.Confirm.route) { ConfirmScreen(vm, back, onAdd = { vm.setQuery(""); nav.navigate(LogStep.Manual.route) }, onConfirm = advance) }
    composable(LogStep.Match.route) {
        MatchScreen(vm, back, onMatched = {
            val next = vm.nextUnmatched()
            if (next != null) vm.beginMatch(next) else nav.navigate(LogStep.Details.route) { popUpTo(LogStep.Match.route) { inclusive = true } }
        })
    }
    composable(LogStep.Manual.route) { ManualScreen(vm, back, onContinue = { nav.navigate(LogStep.Details.route) }, onUsual = { nav.navigate(LogStep.Usual.route) }) }
    composable(LogStep.Usual.route) {
        val container = HealthApp.container(LocalContext.current)
        UsualScreen(container, vm, back, onPicked = { nav.navigate(LogStep.Details.route) })
    }
    composable(LogStep.Details.route) {
        DetailsScreen(vm, back, onSaved = { nav.navigate(LogStep.Saved.route) { popUpTo(LogStep.Capture.route) { inclusive = true } } })
    }
    composable(LogStep.Saved.route) { SavedScreen(vm, dash, onDone = onDone, onViewMeal = onViewMeal) }
}

@Composable
private fun CaptureScreen(vm: LogMealViewModel, nav: NavHostController, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showTime by remember { mutableStateOf(false) }

    fun handle(uri: Uri) {
        nav.navigate(LogStep.Analyzing.route)
        scope.launch {
            val next = vm.onPhoto(uri)
            nav.navigate(next.route) { popUpTo(LogStep.Analyzing.route) { inclusive = true } }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pendingUri?.let(::handle) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::handle) }
    val takePhoto = { val u = vm.newCaptureUri(context); pendingUri = u; camera.launch(u) }

    if (showTime) TimeDialog(state.timeMs, onDismiss = { showTime = false }) { vm.setTime(it); showTime = false }

    ScreenFrame(
        topBar = { SmallTopBar("Log meal", onBack) },
        footer = {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.spacing2xl), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LabeledIcon("Gallery") { AppIconButton(AppIcons.image, "Pick from gallery", { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, style = IconButtonStyle.Tonal) }
                LabeledIcon("Take photo") { AppIconButton(AppIcons.camera, "Take photo", takePhoto, style = IconButtonStyle.Filled, size = 72.dp, iconSize = 30.dp) }
                LabeledIcon("Type it") { AppIconButton(AppIcons.edit, "Type it in", { vm.setQuery(""); nav.navigate(LogStep.Manual.route) }, style = IconButtonStyle.Tonal) }
            }
        },
    ) {
        Box(
            Modifier.fillMaxWidth().height(420.dp).clip(Shapes.xxl).background(Theme.colors.bgSurfaceSunken)
                .border(1.dp, Theme.colors.borderSubtle, Shapes.xxl).clickable(onClick = takePhoto),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                Icon(AppIcons.camera, null, tint = Theme.colors.iconSecondary, modifier = Modifier.size(40.dp))
                Text("Fit the whole plate in frame", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
            }
        }
        RowGroup {
            ListRow("Meal time · ${Fmt.time(state.timeMs)}", subtitle = "From photo when available · editable", icon = AppIcons.calendar, trailing = "Edit", chevron = false, onClick = { showTime = true })
            ListRow("Usual meals", subtitle = "Log a saved meal in one tap", icon = AppIcons.meal, onClick = { nav.navigate(LogStep.Usual.route) })
        }
    }
}

@Composable
private fun LabeledIcon(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
        content()
        Text(label, style = Theme.type.labelS, color = Theme.colors.textSecondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(timeMs: Long, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(timeMs).atZone(zone)
    val picker = rememberTimePickerState(dt.hour, dt.minute, false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                var t = dt.withHour(picker.hour).withMinute(picker.minute)
                if (t.toInstant().toEpochMilli() > System.currentTimeMillis()) t = t.minusDays(1)
                onSet(t.toInstant().toEpochMilli())
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        text = { TimePicker(picker) },
    )
}

@Composable
private fun AnalyzingScreen(vm: LogMealViewModel, onBack: () -> Unit, onManual: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenFrame(
        topBar = { SmallTopBar("Log meal", onBack) },
        footer = { AppButton("Cancel and type it instead", { vm.setQuery(""); onManual() }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost) },
    ) {
        MealPhoto(state.photoPath, Modifier.fillMaxWidth().height(260.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalAlignment = Alignment.CenterVertically) {
            Tag("AI", TagType.AI)
            Text("Identifying foods and portions…", style = Theme.type.headingH3, color = Theme.colors.textPrimary)
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.colors.textAccent)
        }
        repeat(3) { Box(Modifier.fillMaxWidth().height(56.dp).clip(Shapes.lg).background(Theme.colors.bgSurface)) }
        Caption("AI only names the foods and estimates portions. Carbs, protein, fat, fiber and calories come from the USDA FoodData Central database, and you confirm everything before it is saved.")
    }
}

@Composable
private fun ConfirmScreen(vm: LogMealViewModel, onBack: () -> Unit, onAdd: () -> Unit, onConfirm: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenFrame(
        topBar = { SmallTopBar("Confirm foods", onBack) },
        footer = { AppButton("Confirm ${state.items.size} food${if (state.items.size == 1) "" else "s"}", onConfirm, Modifier.fillMaxWidth(), enabled = state.items.isNotEmpty()) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd), verticalAlignment = Alignment.CenterVertically) {
            MealPhoto(state.photoPath, Modifier.size(72.dp))
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
                Tag(if (state.aiCached) "Recognized by AI · saved result" else "Recognized by AI", TagType.AI)
                Text("Check names and portions. Nothing is saved until you confirm.", style = Theme.type.bodyS, color = Theme.colors.textSecondary)
            }
        }
        state.items.forEachIndexed { i, item ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name.replaceFirstChar(Char::uppercase), style = Theme.type.bodyL, color = Theme.colors.textPrimary, modifier = Modifier.weight(1f))
                    item.confidence?.let { Tag("${it.replaceFirstChar(Char::uppercase)} confidence", if (it == "high") TagType.Neutral else TagType.High) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Stepper("${Fmt.amount(item.amount)} ${item.unit}", { vm.adjust(i, false) }, { vm.adjust(i, true) })
                    Text("Remove", style = Theme.type.labelM, color = Theme.colors.textAccent, modifier = Modifier.clickable { vm.remove(i) }.padding(Dimens.spacingSm))
                }
            }
        }
        AppButton("Add a food", onAdd, style = ButtonStyle.Ghost, size = ButtonSize.Small, icon = AppIcons.add)
    }
}

@Composable
private fun MatchScreen(vm: LogMealViewModel, onBack: () -> Unit, onMatched: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.matchIndex) { vm.search() }
    val item = state.items.getOrNull(state.matchIndex)
    ScreenFrame(
        topBar = { SmallTopBar("Choose a match · ${state.matchIndex + 1} of ${state.items.size}", onBack) },
        footer = {
            AppButton("Use this match", { scope.launch { vm.confirmMatch(); onMatched() } }, Modifier.fillMaxWidth(), enabled = state.selectedFdc != null)
        },
    ) {
        AppTextField("Search USDA FoodData Central", state.query, { vm.setQuery(it) }, suffix = null)
        AppButton("Search", { scope.launch { vm.search() } }, style = ButtonStyle.Secondary, size = ButtonSize.Small, icon = AppIcons.search)
        if (item?.ai == true) Caption("AI suggested \"${item.name}\". Pick the closest database entry — nutrition comes only from this source.")
        when {
            state.searching -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.colors.textAccent)
                Text("Searching…", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
            }
            state.searchError != null -> Notice(NoticeType.Info, "USDA lookup failed", "${state.searchError}. Your foods are kept — try again.")
            state.results.isEmpty() -> EmptyState("No matches", "Try a simpler name, e.g. \"rice brown cooked\".")
            else -> state.results.forEach { f -> FoodMatchRow(f.description, nutrientLine(f), f.fdcId == state.selectedFdc) { vm.select(f.fdcId) } }
        }
    }
}

fun nutrientLine(f: com.javon.healthdash.data.UsdaFood) =
    "FDC ${f.fdcId} · per 100 g: ${f.per100g.carbsG?.let { "${Fmt.one(it)} g carbs" } ?: "carbs n/a"}" + (f.per100g.fiberG?.let { " · ${Fmt.one(it)} g fiber" } ?: "")

/** Figma: Food Match Row. */
@Composable
fun FoodMatchRow(name: String, source: String, selected: Boolean, onClick: () -> Unit) {
    val c = Theme.colors
    Row(
        Modifier.fillMaxWidth().clip(Shapes.lg).background(if (selected) c.bgAccentSubtle else c.bgSurface)
            .border(1.dp, if (selected) c.borderAccent else c.borderSubtle, Shapes.lg).clickable(onClick = onClick)
            .padding(horizontal = Dimens.spacingLg, vertical = Dimens.spacingMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(if (selected) c.bgAccent else c.bgSurface)
                .then(if (selected) Modifier else Modifier.border(2.dp, c.borderStrong, CircleShape)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
            Text(name, style = Theme.type.bodyL, color = c.textPrimary)
            Text(source, style = Theme.type.bodyS, color = c.textSecondary)
        }
    }
}

@Composable
private fun ManualScreen(vm: LogMealViewModel, onBack: () -> Unit, onContinue: () -> Unit, onUsual: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ScreenFrame(
        topBar = { SmallTopBar("Add foods", onBack) },
        footer = {
            AppButton("Continue", onContinue, Modifier.fillMaxWidth(), enabled = state.items.isNotEmpty())
            AppButton("Pick a usual meal", onUsual, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
        },
    ) {
        state.failure?.let { Notice(NoticeType.Info, "Couldn't recognize this photo", "$it Add the foods yourself — the photo is still attached.") }
        AppTextField("Food", state.query, { vm.setQuery(it) }, helper = "Searches USDA FoodData Central", placeholder = "e.g. oatmeal")
        AppButton("Search", { scope.launch { vm.search() } }, style = ButtonStyle.Secondary, size = ButtonSize.Small, icon = AppIcons.search)
        when {
            state.searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.colors.textAccent)
            state.searchError != null -> Notice(NoticeType.Info, "USDA lookup failed", "${state.searchError}.")
            state.results.isNotEmpty() -> RowGroup {
                state.results.forEach { f ->
                    ListRow(f.description, subtitle = nutrientLine(f), chevron = false,
                        trailingContent = { Icon(AppIcons.add, "Add", tint = Theme.colors.iconAccent) },
                        onClick = { scope.launch { vm.addManual(f) } })
                }
            }
        }
        if (state.items.isNotEmpty()) {
            SectionHeader("Added")
            RowGroup {
                state.items.forEachIndexed { i, it ->
                    ListRow(it.name.replaceFirstChar(Char::uppercase), subtitle = "${Fmt.amount(it.amount)} ${it.unit}" + (it.nutrients?.let { n -> " · ${Fmt.int(n.carbsG)} g carbs" } ?: ""),
                        icon = AppIcons.meal, chevron = false,
                        trailingContent = { Text("Remove", style = Theme.type.labelM, color = Theme.colors.textAccent, modifier = Modifier.clickable { vm.remove(i) }) })
                }
            }
            Caption("Amounts start at 100 g — adjust them on the next screen.")
        }
    }
}

@Composable
private fun UsualScreen(container: com.javon.healthdash.AppContainer, vm: LogMealViewModel, onBack: () -> Unit, onPicked: () -> Unit) {
    val usual by container.dao.usualMeals().collectAsStateWithLifecycle(emptyList())
    ScreenFrame(topBar = { SmallTopBar("Usual meals", onBack) }) {
        if (usual.isEmpty()) {
            EmptyState("No usual meals yet", "Turn on \"Save as a usual meal\" when you log a meal you eat often.")
        } else {
            val grouped = usual.groupBy { it.meal.usualName }
            RowGroup {
                grouped.forEach { (name, list) ->
                    val m = list.first()
                    val carbs = m.items.sumOf { com.javon.healthdash.domain.Analysis.itemNutrients(it)?.carbsG ?: 0.0 }
                    ListRow(name ?: "Meal", subtitle = "${Fmt.int(carbs)} g carbs", trailing = "${list.size}×", icon = AppIcons.meal,
                        onClick = { vm.useUsual(m); onPicked() })
                }
            }
            Caption("Tap to log with the current time. Portions and tags can be edited before saving.")
        }
    }
}

@Composable
private fun DetailsScreen(vm: LogMealViewModel, onBack: () -> Unit, onSaved: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showTime by remember { mutableStateOf(false) }
    if (showTime) TimeDialog(state.timeMs, onDismiss = { showTime = false }) { vm.setTime(it); showTime = false }
    ScreenFrame(
        topBar = { SmallTopBar("Meal details", onBack) },
        footer = { AppButton("Save meal", { scope.launch { vm.save(context); onSaved() } }, Modifier.fillMaxWidth(), enabled = state.items.isNotEmpty()) },
    ) {
        RowGroup {
            ListRow("Meal time", subtitle = if (state.photoPath != null) "From photo · editable" else "Editable", trailing = "${Fmt.dayLabel(state.timeMs, System.currentTimeMillis())} ${Fmt.time(state.timeMs)}", icon = AppIcons.calendar, onClick = { showTime = true })
        }
        SectionHeader("Foods")
        state.items.forEachIndexed { i, item ->
            AppCard {
                Text(item.name.replaceFirstChar(Char::uppercase), style = Theme.type.bodyL, color = Theme.colors.textPrimary)
                Text(item.match?.let { "USDA · ${it.description}" } ?: "No USDA match", style = Theme.type.bodyS, color = Theme.colors.textTertiary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Stepper("${Fmt.amount(item.amount)} ${item.unit}", { vm.adjust(i, false) }, { vm.adjust(i, true) })
                    Text(item.grams?.let { Fmt.grams(it) } ?: "grams?", style = Theme.type.labelL, color = Theme.colors.textSecondary)
                }
                if (item.grams == null || item.gramsOverride != null) {
                    var text by remember(i) { mutableStateOf(item.gramsOverride?.let { Fmt.amount(it) } ?: "") }
                    AppTextField("Weight in grams", text, { text = it; vm.setGrams(i, it.toDoubleOrNull()?.takeIf { g -> g > 0 }) },
                        suffix = "g", keyboardType = KeyboardType.Decimal,
                        helper = "No USDA portion for \"${item.unit}\" — enter grams so nutrition can be computed.")
                }
            }
        }
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Nutrition", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                Text("USDA FoodData Central", style = Theme.type.caption, color = Theme.colors.textTertiary)
            }
            val t = state.total
            MetricTiles(listOf("Carbs" to "${Fmt.int(t.carbsG)} g", "Protein" to "${Fmt.int(t.proteinG)} g", "Fat" to "${Fmt.int(t.fatG)} g", "Fiber" to "${Fmt.int(t.fiberG)} g", "kcal" to Fmt.int(t.kcal)))
            if (state.incomplete > 0) Caption("${state.incomplete} food(s) without grams or a USDA match are not counted.")
        }
        SectionHeader("Tags")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            MEAL_TAGS.forEach { tag -> FilterChip(tag, tag in state.tags, { vm.toggleTag(tag) }) }
        }
        RowGroup {
            ListRow("Save as a usual meal", subtitle = "Log it again in one tap", chevron = false, trailingContent = { Toggle(state.saveUsual, vm::setSaveUsual) })
        }
        if (state.saveUsual) AppTextField("Usual meal name", state.usualName, vm::setUsualName, placeholder = state.items.joinToString(", ") { it.name })
    }
}

@Composable
private fun SavedScreen(vm: LogMealViewModel, dash: DashboardState, onDone: () -> Unit, onViewMeal: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val container = HealthApp.container(LocalContext.current)
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val key = foodKeyOf(state.items.map { it.name })
    val forecast = MealAnalytics.forecast(key, state.timeMs, dash.scored, ZoneId.systemDefault(), dash.targets)
    ScreenFrame(
        topBar = {},
        footer = {
            AppButton("Done", onDone, Modifier.fillMaxWidth())
            state.savedMealId?.let { id -> AppButton("View meal", { onViewMeal(id) }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost) }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = Dimens.spacing4xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Theme.colors.bgSuccessSubtle), contentAlignment = Alignment.Center) {
                Icon(AppIcons.check, null, tint = Theme.colors.statusInRange, modifier = Modifier.size(36.dp))
            }
            Text("Meal saved", style = Theme.type.headingH1, color = Theme.colors.textPrimary)
            Text("${Fmt.time(state.timeMs)} · ${Fmt.int(state.total.carbsG)} g carbs", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
        }
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Expected response", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                Tag("${forecast.walk.count + forecast.noWalk.count} past meals", TagType.Neutral)
            }
            if (forecast.hasAny) {
                MetricTiles(
                    listOf(
                        "With a walk · ${forecast.walk.count}" to (forecast.walk.avgRise?.let { "${Fmt.signed(it)} mg/dL" } ?: "—"),
                        "No walk · ${forecast.noWalk.count}" to (forecast.noWalk.avgRise?.let { "${Fmt.signed(it)} mg/dL" } ?: "—"),
                    ),
                    columns = 2,
                )
                Caption("Average peak rise for this meal at this time of day, from your own history. A branch needs at least ${dash.targets.minForecastInstances} past meals.")
            } else {
                Text("No forecast yet — fewer than ${dash.targets.minForecastInstances} past meals like this at this time of day.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
            }
        }
        RowGroup {
            ListRow("Walk reminder in 10 min", subtitle = "${Fmt.int(dash.targets.walkSteps)} steps within ${dash.targets.walkWindowMin} min counts", icon = AppIcons.walk, chevron = false,
                trailingContent = { Toggle(prefs.walkNudge, { container.prefs.setWalkNudge(it) }) })
        }
    }
}
