package com.javon.healthdash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.Completion
import com.javon.healthdash.calc.FuelStatus
import com.javon.healthdash.calc.Fueling
import com.javon.healthdash.calc.HrZone
import com.javon.healthdash.calc.Intensity
import com.javon.healthdash.calc.MINUTE_MS
import com.javon.healthdash.calc.PlanBuilder
import com.javon.healthdash.calc.SleepContext
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.calc.TimeWindow
import com.javon.healthdash.calc.WorkoutKind
import com.javon.healthdash.calc.Zones
import com.javon.healthdash.calc.localDateOf
import com.javon.healthdash.data.TargetsRepository
import com.javon.healthdash.data.db.AdjustmentEntity
import com.javon.healthdash.data.db.FuelEntity
import com.javon.healthdash.data.db.PlanEntity
import com.javon.healthdash.data.db.PlannedEntity
import com.javon.healthdash.data.db.WorkoutEntity
import com.javon.healthdash.data.done
import com.javon.healthdash.data.sportOf
import com.javon.healthdash.data.toSession
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.AppSheet
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.BottomNav
import com.javon.healthdash.ui.components.ButtonSize
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.CalendarMonth
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.DayState
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.FilterChip
import com.javon.healthdash.ui.components.GlucoseChart
import com.javon.healthdash.ui.components.LargeTopBar
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.Notice
import com.javon.healthdash.ui.components.NoticeType
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.Tab
import com.javon.healthdash.ui.components.Tag
import com.javon.healthdash.ui.components.TagType
import com.javon.healthdash.ui.components.TrendLineChart
import com.javon.healthdash.ui.components.TrendPoint
import com.javon.healthdash.ui.components.WeeklyBars
import com.javon.healthdash.ui.components.WorkoutCard
import com.javon.healthdash.ui.components.WorkoutCardStatus
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

/** Everything the training screens read from the database. */
data class TrainingData(
    val plans: List<PlanEntity>,
    val planned: List<PlannedEntity>,
    val adjustments: List<AdjustmentEntity>,
    val fuel: List<FuelEntity>,
)

@Composable
fun rememberTrainingData(container: AppContainer): TrainingData {
    val plans by container.training.plans.collectAsStateWithLifecycle(emptyList())
    val planned by container.training.planned.collectAsStateWithLifecycle(emptyList())
    val adjustments by container.training.adjustments.collectAsStateWithLifecycle(emptyList())
    val fuel by container.training.fuel.collectAsStateWithLifecycle(emptyList())
    return TrainingData(plans, planned, adjustments, fuel)
}

fun sportIcon(sport: Sport?): ImageVector = when (sport) {
    Sport.Strength -> AppIcons.strength
    else -> AppIcons.run
}

fun sessionTitle(e: PlannedEntity): String {
    val kind = WorkoutKind.valueOf(e.kind)
    val sport = Sport.valueOf(e.sport)
    return when {
        sport == Sport.Strength -> "Strength · ${e.durationMin} min"
        kind == WorkoutKind.Race -> "Race day"
        else -> "${kind.label} ${if (sport == Sport.Ride) "ride" else "run"} · ${e.durationMin} min"
    }
}

/** HR zones from resting HR (14-day morning average) and the highest workout max HR in 90 days. */
fun zonesFor(s: DashboardState, zone: ZoneId): List<HrZone> {
    val today = localDateOf(s.nowMs, zone)
    val rhr = (0..13).mapNotNull { SleepContext.morningValue(today.minusDays(it.toLong()), zone, s.restingHr) }
    val max = s.workouts.mapNotNull { it.maxHr }.maxOrNull()
    if (rhr.isEmpty() || max == null) return emptyList()
    return Zones.heartRate(rhr.average(), max)
}

fun sessionStatus(e: PlannedEntity, s: DashboardState, zone: ZoneId): WorkoutCardStatus = when {
    Completion.matches(e.toSession(), s.workouts.map { it.done() }, zone) != null -> WorkoutCardStatus.Completed
    e.status == "Missed" -> WorkoutCardStatus.Missed
    e.status == "Swapped" -> WorkoutCardStatus.Swapped
    else -> WorkoutCardStatus.Planned
}

fun sessionDetail(e: PlannedEntity, zones: List<HrZone>): String =
    listOfNotNull(e.phase.lowercase().replaceFirstChar(Char::uppercase) + " week", Zones.forKind(WorkoutKind.valueOf(e.kind), zones)).joinToString(" · ")

/** Average endurance minutes per week over the last 4 full weeks (auto-fill for the plan builder). */
fun recentWeeklyMinutes(s: DashboardState, zone: ZoneId): Int {
    val thisWeek = PlanBuilder.monday(localDateOf(s.nowMs, zone))
    val weekly = Completion.weeklyMinutes(s.workouts.map { it.done() }, zone)
    return (1..4).sumOf { weekly[thisWeek.minusWeeks(it.toLong())] ?: 0 } / 4
}

/** Runs the missed-session and readiness rules whenever training data changes. */
@Composable
fun ReconcileEffect(container: AppContainer, state: DashboardState, data: TrainingData) {
    LaunchedEffect(state.workouts.size, state.sleep.size, state.hrv.size, data.planned.size) {
        if (data.planned.isEmpty() || state.loading) return@LaunchedEffect
        container.training.reconcile(
            ZoneId.systemDefault(), state.workouts, state.sleep, state.hrv, state.targets,
            TargetsRepository.toFuelTable(state.targetValues),
        )
    }
}

@Composable
fun TrainingScreen(
    container: AppContainer,
    state: DashboardState,
    onTab: (Tab) -> Unit,
    onWorkout: (String) -> Unit,
    onPlans: () -> Unit,
    onWeek: (LocalDate) -> Unit,
    onAdjustments: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    ReconcileEffect(container, state, data)
    val today = localDateOf(state.nowMs, zone)
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var dayOpen by remember { mutableStateOf<LocalDate?>(null) }
    var vo2Open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val zones = zonesFor(state, zone)
    val done = state.workouts.map { it.done() }
    val nextPlan = data.plans.firstOrNull { !LocalDate.ofEpochDay(it.raceDate).isBefore(today) }
    val overline = nextPlan?.let {
        val race = LocalDate.ofEpochDay(it.raceDate)
        "${com.javon.healthdash.calc.RaceType.valueOf(it.raceType).label} · ${Fmt.dayShort(race.atStartOfDay(zone).toInstant().toEpochMilli())} · ${ChronoUnit.WEEKS.between(today, race)} weeks out"
    } ?: "No race plan yet"

    if (vo2Open) Vo2Sheet(onDismiss = { vo2Open = false }) { v -> scope.launch { container.training.addVo2(System.currentTimeMillis(), v); vo2Open = false } }
    dayOpen?.let { d ->
        AppSheet(Fmt.dayLong(d.atStartOfDay(zone).toInstant().toEpochMilli()), onDismiss = { dayOpen = null }) {
            val sessions = data.planned.filter { it.date == d.toEpochDay() }
            val ws = state.workouts.filter { localDateOf(it.startMs, zone) == d }
            if (sessions.isEmpty() && ws.isEmpty()) Text("Rest day.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
            sessions.forEach { e -> WorkoutCard(sessionTitle(e), sessionDetail(e, zones), e.note, sessionStatus(e, state, zone), sportIcon(Sport.valueOf(e.sport))) }
            if (ws.isNotEmpty()) RowGroup { ws.forEach { w -> WorkoutRow(w, zone) { dayOpen = null; onWorkout(w.id) } } }
            AppButton("Open week", { dayOpen = null; onWeek(PlanBuilder.monday(d)) }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
        }
    }

    ScreenFrame(topBar = { LargeTopBar("Training", overline) }, bottomBar = { BottomNav(Tab.Training) { onTab(it) } }) {
        CalendarMonth(
            month, today,
            stateOf = { d ->
                val sessions = data.planned.filter { it.date == d.toEpochDay() }
                when {
                    done.any { localDateOf(it.window.startMs, zone) == d } -> DayState.Completed
                    sessions.any { it.status == "Missed" } -> DayState.Missed
                    sessions.isNotEmpty() && !d.isBefore(today) -> DayState.Planned
                    else -> DayState.Rest
                }
            },
            onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) }, onDay = { dayOpen = it },
        )

        val todaySessions = data.planned.filter { it.date == today.toEpochDay() }
        SectionHeader("Today · ${Fmt.dayShort(state.nowMs)}", if (data.plans.isEmpty()) null else "Week plan") { onWeek(PlanBuilder.monday(today)) }
        data.adjustments.firstOrNull { a -> a.kind == "Swapped" && !a.reverted && todaySessions.any { it.id == a.sessionId } }?.let { a ->
            Notice(NoticeType.Gentle, a.summary, a.reason + ". Hard sessions after poor recovery are swapped for easy.", action = "Keep original plan", onAction = { scope.launch { container.training.undoSwap(a) } })
        }
        when {
            data.plans.isEmpty() -> EmptyState("No race plan yet", "Create a plan for your marathon, 80-mile ride or 200-mile relay. It's built by fixed rules from your recent training.")
            todaySessions.isEmpty() -> AppCard { Text("Rest day — nothing planned today.", style = Theme.type.bodyM, color = Theme.colors.textSecondary) }
            else -> todaySessions.forEach { e -> WorkoutCard(sessionTitle(e), sessionDetail(e, zones), e.note, sessionStatus(e, state, zone), sportIcon(Sport.valueOf(e.sport))) }
        }
        RowGroup {
            ListRow("Race plans", subtitle = if (data.plans.isEmpty()) "Create your first plan" else "${data.plans.size} plan${if (data.plans.size == 1) "" else "s"} in sequence", icon = AppIcons.calendar, onClick = onPlans)
            ListRow("Plan adjustments", subtitle = "${data.adjustments.size} rule-based changes", icon = AppIcons.sync, onClick = onAdjustments)
        }

        val thisWeek = PlanBuilder.monday(today)
        val weeks = (-5..2).map { thisWeek.plusWeeks(it.toLong()) }
        val doneByWeek = Completion.weeklyMinutes(done, zone)
        val plannedByWeek = data.planned.filter { it.sport != Sport.Strength.name }.groupBy { PlanBuilder.monday(LocalDate.ofEpochDay(it.date)) }.mapValues { (_, l) -> l.sumOf { it.durationMin } }
        WeeklyBars(
            "Weekly endurance minutes",
            planned = weeks.map { plannedByWeek[it] }, done = weeks.map { if (it.isAfter(thisWeek)) null else doneByWeek[it] ?: 0 },
            labels = weeks.map { if (it == thisWeek) "Now" else "${it.monthValue}/${it.dayOfMonth}" },
        )

        val vo2 = state.vo2.sortedBy { it.timeMs }
        if (vo2.isEmpty()) {
            AppCard {
                Text("VO2 max", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                Text("Garmin isn't sharing VO2 max with Health Connect. Enter the value shown on your watch.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
                AppButton("Add VO2 max", { vo2Open = true }, style = ButtonStyle.Secondary, size = ButtonSize.Small, icon = AppIcons.add)
            }
        } else {
            TrendLineChart(
                "VO2 max · ${vo2.last().value.let { Fmt.one(it) }}", vo2.map { TrendPoint(it.timeMs, it.value) }, target = null, targetLabel = "", goalAbove = true,
                ticks = listOf(Fmt.dayShort(vo2.first().timeMs).substringAfter(' '), Fmt.dayShort(vo2.last().timeMs).substringAfter(' ')),
                seriesLabel = "ml/kg/min", format = { Fmt.one(it) },
            )
            AppButton("Add VO2 max", { vo2Open = true }, style = ButtonStyle.Ghost, size = ButtonSize.Small, icon = AppIcons.add)
        }

        SectionHeader("Recent workouts")
        val recent = state.workouts.sortedByDescending { it.startMs }.take(8)
        if (recent.isEmpty()) Caption("No workouts from Garmin yet. They appear once Garmin Connect shares with Health Connect.")
        else RowGroup { recent.forEach { w -> WorkoutRow(w, zone) { onWorkout(w.id) } } }
        if (zones.isNotEmpty()) Caption("HR zones: ${zones.joinToString(" · ") { "${it.name} ${it.low}–${it.high}" }} (from resting HR and your max in recent workouts).")
    }
}

@Composable
fun WorkoutRow(w: WorkoutEntity, zone: ZoneId, onClick: () -> Unit) {
    val min = (w.endMs - w.startMs) / MINUTE_MS
    ListRow(
        w.title ?: when (sportOf(w.exerciseType)) { Sport.Run -> "Run"; Sport.Ride -> "Ride"; Sport.Strength -> "Strength"; null -> "Workout" },
        subtitle = listOfNotNull(Fmt.dayShort(w.startMs), "$min min", w.avgHr?.let { "avg HR ${it.toInt()}" }).joinToString(" · "),
        icon = sportIcon(sportOf(w.exerciseType)), onClick = onClick,
    )
}

@Composable
fun WorkoutDetailScreen(container: AppContainer, state: DashboardState, workoutId: String, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    val w = state.workouts.firstOrNull { it.id == workoutId }
    var fuelOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (w == null) {
        ScreenFrame(topBar = { SmallTopBar("Workout", onBack) }) { EmptyState("Workout not found", "It may be outside the last 90 days.") }
        return
    }
    val window = TimeWindow(w.startMs, w.endMs)
    val minutes = (window.durationMs / MINUTE_MS).toInt()
    val fuel = data.fuel.filter { it.workoutId == w.id }
    val planned = data.planned.firstOrNull { e -> Completion.matches(e.toSession(), listOf(w.done()), zone) != null }
    val intensity = planned?.let { WorkoutKind.valueOf(it.kind).intensity } ?: Intensity.Moderate
    val table = TargetsRepository.toFuelTable(state.targetValues)
    val review = Fueling.review(window, intensity, fuel.sumOf { it.carbsG }, state.glucose, table, state.targets)
    val zones = zonesFor(state, zone)
    val title = w.title ?: "Workout"

    if (fuelOpen) FuelSheet(minutes, onDismiss = { fuelOpen = false }) { label, grams, offsetMin ->
        scope.launch { container.training.addFuel(w.id, w.startMs + offsetMin * MINUTE_MS, label, grams); fuelOpen = false }
    }

    ScreenFrame(topBar = { SmallTopBar("$title · ${Fmt.dayShort(w.startMs)}", onBack) }) {
        MetricTiles(
            listOf(
                "Time" to "${minutes / 60}:${"%02d".format(minutes % 60)}",
                "Avg HR" to (w.avgHr?.let { "${it.toInt()}" } ?: "—"),
                "Max HR" to (w.maxHr?.let { "${it.toInt()}" } ?: "—"),
                "Distance" to (w.distanceM?.let { "${Fmt.one(it / 1609.344)} mi" } ?: "—"),
                "Zone" to (w.avgHr?.let { hr -> zones.firstOrNull { hr >= it.low && hr <= it.high }?.name } ?: "—"),
                "Fuel taken" to "${review.takenG.toInt()} g",
            ),
        )
        GlucoseChart(
            "Glucose during ${title.lowercase()}", state.glucose, TimeWindow(w.startMs - 30 * MINUTE_MS, w.endMs + 60 * MINUTE_MS),
            ticks = listOf(-30, minutes / 3, 2 * minutes / 3, minutes, minutes + 60).map { Fmt.time(w.startMs + it * MINUTE_MS) },
            workouts = listOf(window), targets = state.targets, fuel = fuel.map { it.timeMs },
        )
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fueling vs glucose", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                when (review.status) {
                    FuelStatus.Below -> Tag("Below target", TagType.High)
                    FuelStatus.Within -> Tag("On target", TagType.InRange)
                    FuelStatus.Above -> Tag("Above target", TagType.Neutral)
                }
            }
            MetricTiles(
                listOf(
                    "Target" to "${review.target.lo}–${review.target.hi} g",
                    "Taken" to "${review.takenG.toInt()} g",
                    "Lowest" to (review.lowest?.let { "${it.toInt()} mg/dL" } ?: "—"),
                ),
            )
            val r = table.range(minutes, intensity)
            Text(
                buildString {
                    append("${r.lo}–${r.hi} g/h for a $minutes-min ${intensity.name.lowercase()} session")
                    if (minutes >= 90) append(" (after the first 45 min)")
                    append(". ")
                    append(if (review.coverage < state.targets.minCoverage) "Not enough glucose data during this workout to judge the curve." else if (review.lowsBelow70 > 0) "${review.lowsBelow70} reading(s) below 70." else "No lows below 70.")
                },
                style = Theme.type.bodyS, color = Theme.colors.textSecondary,
            )
        }
        SectionHeader("Fuel log", "Add") { fuelOpen = true }
        if (fuel.isEmpty()) Caption("Log gels, chews and drinks to compare fueling with your glucose curve.")
        else RowGroup {
            fuel.forEach { f ->
                ListRow(f.label, subtitle = "${(f.timeMs - w.startMs) / MINUTE_MS} min in", trailing = "${f.carbsG.toInt()} g", icon = AppIcons.bolt, chevron = false,
                    trailingContent = { Text("Remove", style = Theme.type.labelM, color = Theme.colors.textAccent, modifier = Modifier.clickable { scope.launch { container.training.deleteFuel(f.id) } }.padding(start = Dimens.spacingSm)) })
            }
        }
        planned?.let { Caption("Matched to planned: ${sessionTitle(it)}.") }
    }
}

@Composable
fun FuelSheet(workoutMin: Int, onDismiss: () -> Unit, onSave: (String, Double, Long) -> Unit) {
    var label by remember { mutableStateOf("Gel") }
    var grams by remember { mutableStateOf("25") }
    var at by remember { mutableStateOf("45") }
    AppSheet("Add fuel", onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            listOf("Gel" to 25, "Chews" to 45, "Sports drink" to 30, "Banana" to 27, "Bar" to 40).forEach { (l, g) ->
                FilterChip("$l · $g g", label == l, { label = l; grams = "$g" })
            }
        }
        AppTextField("Label", label, { label = it })
        AppTextField("Carbs", grams, { grams = it }, suffix = "g", keyboardType = KeyboardType.Number)
        AppTextField("Minutes into the workout", at, { at = it }, suffix = "min", keyboardType = KeyboardType.Number, helper = "Workout length: $workoutMin min")
        val g = grams.toDoubleOrNull()
        val m = at.toLongOrNull()
        AppButton("Save", { onSave(label.ifBlank { "Fuel" }, g!!, m!!.coerceIn(0, workoutMin.toLong())) }, Modifier.fillMaxWidth(), enabled = g != null && g > 0 && m != null)
    }
}

@Composable
fun Vo2Sheet(onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var v by remember { mutableStateOf("") }
    val parsed = v.toDoubleOrNull()
    AppSheet("Add VO2 max", onDismiss) {
        Text("Garmin is not sharing VO2 max with Health Connect. Enter the value shown on your watch.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
        AppTextField("VO2 max", v, { v = it }, suffix = "ml/kg/min", keyboardType = KeyboardType.Decimal,
            error = if (parsed != null && parsed !in 20.0..90.0) "Enter a value between 20 and 90" else null)
        AppButton("Save", { onSave(parsed!!) }, Modifier.fillMaxWidth(), enabled = parsed != null && parsed in 20.0..90.0)
    }
}
