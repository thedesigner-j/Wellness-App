package com.javon.healthdash.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.Completion
import com.javon.healthdash.calc.PlanBuilder
import com.javon.healthdash.calc.PlanInputs
import com.javon.healthdash.calc.PlanResult
import com.javon.healthdash.calc.RaceType
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.calc.WorkoutKind
import com.javon.healthdash.calc.Zones
import com.javon.healthdash.calc.localDateOf
import com.javon.healthdash.data.TargetsRepository
import com.javon.healthdash.data.done
import com.javon.healthdash.data.inputs
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.AppIconButton
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.FilterChip
import com.javon.healthdash.ui.components.Heading
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.Notice
import com.javon.healthdash.ui.components.NoticeType
import com.javon.healthdash.ui.components.PhaseBar
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SegmentedControl
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.StepDots
import com.javon.healthdash.ui.components.Tag
import com.javon.healthdash.ui.components.TagType
import com.javon.healthdash.ui.components.WeeklyBars
import com.javon.healthdash.ui.components.WorkoutCardStatus
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

private fun LocalDate.ms(zone: ZoneId) = atStartOfDay(zone).toInstant().toEpochMilli()
private fun dayShortName(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.US)

@Composable
fun RacePlansScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit, onNew: () -> Unit, onWeek: (LocalDate) -> Unit, onExport: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<com.javon.healthdash.data.db.PlanEntity?>(null) }
    val rules = TargetsRepository.toRules(state.targetValues)
    val today = localDateOf(state.nowMs, zone)
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${RaceType.valueOf(p.raceType).label} plan?") },
            text = { Text("All of its planned sessions and adjustments are removed. Completed workouts from Garmin are not affected.") },
            confirmButton = { TextButton({ scope.launch { container.training.deletePlan(p.id); deleting = null } }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
    ScreenFrame(
        topBar = { SmallTopBar("Race plans", onBack, AppIcons.add, onNew) },
        footer = {
            AppButton("New race plan", onNew, Modifier.fillMaxWidth())
            if (data.plans.isNotEmpty()) AppButton("Export for Garmin", onExport, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
        },
    ) {
        Text("Plans run in sequence with ${rules.recoveryWeeks} recovery weeks between them. Rules are editable in Settings › Targets.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
        if (data.plans.isEmpty()) {
            EmptyState("No plans yet", "Start with your marathon, then add the 80-mile ride and the 200-mile relay — each one starts after the previous race's recovery.")
        }
        data.plans.forEachIndexed { i, p ->
            val race = LocalDate.ofEpochDay(p.raceDate)
            val type = RaceType.valueOf(p.raceType)
            val sessions = data.planned.filter { it.planId == p.id }
            val current = sessions.firstOrNull { LocalDate.ofEpochDay(it.date) >= today }
            val active = !LocalDate.ofEpochDay(p.startDate).isAfter(today) && !race.isBefore(today)
            val weeks = sessions.maxOfOrNull { it.week }?.plus(1) ?: 0
            Row(
                Modifier.fillMaxWidth().clip(Shapes.xl).background(Theme.colors.bgSurface).padding(Dimens.spacingLg),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(Shapes.md).background(if (active) Theme.colors.bgAccent else Theme.colors.bgNeutral), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.run, null, tint = if (active) Theme.colors.textOnAccent else Theme.colors.iconPrimary, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
                    Text(type.label, style = Theme.type.headingH3, color = Theme.colors.textPrimary)
                    Text("${Fmt.dayShort(race.ms(zone))} · ${p.goalMin?.let { "Goal ${it / 60}:${"%02d".format(it % 60)}" } ?: "Finish"} · $weeks weeks", style = Theme.type.bodyS, color = Theme.colors.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
                        Text("Week view", style = Theme.type.labelM, color = Theme.colors.textAccent, modifier = Modifier.padding(top = Dimens.spacingXs).clickable { onWeek(PlanBuilder.monday(current?.let { LocalDate.ofEpochDay(it.date) } ?: LocalDate.ofEpochDay(p.startDate))) })
                        Text("Delete", style = Theme.type.labelM, color = Theme.colors.textTertiary, modifier = Modifier.padding(top = Dimens.spacingXs).clickable { deleting = p })
                    }
                }
                Tag(
                    when {
                        race.isBefore(today) -> "Done"
                        active -> current?.let { "Wk ${it.week + 1} · ${it.phase}" } ?: "Active"
                        else -> "Starts ${Fmt.dayShort(LocalDate.ofEpochDay(p.startDate).ms(zone)).substringAfter(' ')}"
                    },
                    if (active) TagType.Brand else TagType.Neutral,
                )
            }
            if (i < data.plans.lastIndex) {
                Row(
                    Modifier.fillMaxWidth().clip(Shapes.xl).border(1.dp, Theme.colors.borderSubtle, Shapes.xl).padding(Dimens.spacingMd),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.sleep, null, tint = Theme.colors.iconSecondary, modifier = Modifier.size(18.dp))
                    Text("Recovery · ${rules.recoveryWeeks} weeks", style = Theme.type.labelL, color = Theme.colors.textSecondary)
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanBuilderScreen(container: AppContainer, state: DashboardState, onClose: () -> Unit, onCreated: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    val scope = rememberCoroutineScope()
    val today = localDateOf(state.nowMs, zone)
    val rules = TargetsRepository.toRules(state.targetValues)
    val fuel = TargetsRepository.toFuelTable(state.targetValues)
    val chainedFrom = data.plans.filter { !LocalDate.ofEpochDay(it.raceDate).isBefore(today) }.maxByOrNull { it.raceDate }
    val start = chainedFrom?.let { PlanBuilder.nextStart(LocalDate.ofEpochDay(it.raceDate), rules) } ?: today

    var step by remember { mutableIntStateOf(1) }
    var race by remember { mutableStateOf(RaceType.Marathon) }
    var raceDate by remember { mutableStateOf(start.plusWeeks(16)) }
    var timeGoal by remember { mutableStateOf(false) }
    var goal by remember { mutableStateOf("3:45") }
    var weekly by remember { mutableStateOf(recentWeeklyMinutes(state, zone).takeIf { it > 0 }?.toString() ?: "") }
    var days by remember { mutableStateOf(setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) }
    var longDay by remember { mutableStateOf(DayOfWeek.SATURDAY) }
    var strength by remember { mutableStateOf(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val goalMin = if (!timeGoal) null else goal.split(":").let { p -> p.getOrNull(0)?.toIntOrNull()?.let { h -> h * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0) } }
    val inputs = PlanInputs(race, raceDate, start, goalMin, weekly.toIntOrNull() ?: 0, days, longDay, strength)
    val back: () -> Unit = { if (step > 1) step-- else onClose() }

    if (picking) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = raceDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton({ ds.selectedDateMillis?.let { raceDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; picking = false }) { Text("Set") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(ds) }
    }

    val preview = if (step == 4) PlanBuilder.generate(inputs, rules, fuel) else null
    ScreenFrame(
        topBar = { SmallTopBar(if (step == 4) "Review plan" else "New race plan", back, AppIcons.close, onClose) },
        footer = {
            when (step) {
                4 -> AppButton("Create plan", {
                    scope.launch {
                        when (val r = container.training.create(inputs, rules, fuel)) {
                            is PlanResult.Ok -> onCreated()
                            is PlanResult.Invalid -> error = r.reason
                        }
                    }
                }, Modifier.fillMaxWidth(), enabled = preview is PlanResult.Ok)
                3 -> AppButton("Generate plan", { step = 4 }, Modifier.fillMaxWidth(), enabled = (weekly.toIntOrNull() ?: 0) > 0 && days.size >= 3)
                else -> AppButton("Next", { step++ }, Modifier.fillMaxWidth(), enabled = step != 2 || !timeGoal || goalMin != null)
            }
            if (step > 1) AppButton("Back", back, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
        },
    ) {
        StepDots(step, 4)
        when (step) {
            1 -> {
                Heading("What are you training for?", "The plan is generated by fixed rules — no AI.")
                RaceType.entries.forEach { t ->
                    FoodMatchRow(
                        t.label,
                        when (t) {
                            RaceType.Marathon -> "Run · taper ${rules.taperWeeksMarathon} weeks"
                            RaceType.Bike80 -> "Ride · taper ${rules.taperWeeksBike} weeks"
                            RaceType.Relay200 -> "Repeated run legs · back-to-backs and double days · taper ${rules.taperWeeksRelay} weeks"
                        },
                        race == t,
                    ) { race = t; goal = when (t) { RaceType.Marathon -> "3:45"; RaceType.Bike80 -> "4:45"; RaceType.Relay200 -> "3:00" } }
                }
                chainedFrom?.let { Caption("Starts ${Fmt.dayShort(start.ms(zone))}, after the ${RaceType.valueOf(it.raceType).label.lowercase()} plus ${rules.recoveryWeeks} recovery weeks.") }
            }
            2 -> {
                Heading("Race date and goal")
                RowGroup { ListRow("Race date", subtitle = "Plan starts ${Fmt.dayShort(start.ms(zone))}", trailing = Fmt.dayShort(raceDate.ms(zone)), icon = AppIcons.calendar, onClick = { picking = true }) }
                Text("Goal", style = Theme.type.labelM, color = Theme.colors.textSecondary)
                SegmentedControl(listOf("Finish", "Target time"), if (timeGoal) 1 else 0, { timeGoal = it == 1 })
                if (timeGoal) AppTextField("Target time", goal, { goal = it }, suffix = "h:mm", helper = if (race == RaceType.Relay200) "Your total running time across legs" else null, error = if (goalMin == null) "Use h:mm, e.g. 3:45" else null)
            }
            3 -> {
                Heading("Volume and availability")
                AppCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Current weekly volume", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                        Tag("From Garmin · last 4 weeks", TagType.Derived)
                    }
                    AppTextField("Minutes per week", weekly, { weekly = it }, suffix = "min", keyboardType = KeyboardType.Number,
                        helper = if (recentWeeklyMinutes(state, zone) == 0) "No recent workouts found — enter your usual weekly training time." else "Average of your recent endurance workouts. Edit if needed.")
                }
                Text("Training days", style = Theme.type.labelM, color = Theme.colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                    DayOfWeek.entries.forEach { d -> FilterChip(dayShortName(d), d in days, { days = if (d in days) days - d else days + d }) }
                }
                Text("Long workout day", style = Theme.type.labelM, color = Theme.colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                    days.sortedBy { it.value }.forEach { d -> FilterChip(dayShortName(d), d == longDay, { longDay = d }) }
                }
                Text("Strength days (1–2 recommended)", style = Theme.type.labelM, color = Theme.colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                    DayOfWeek.entries.forEach { d -> FilterChip(dayShortName(d), d in strength, { strength = if (d in strength) strength - d else strength + d }) }
                }
            }
            else -> {
                Heading(race.label, "${Fmt.dayShort(raceDate.ms(zone))} · ${goalMin?.let { "goal ${it / 60}:${"%02d".format(it % 60)}" } ?: "finish"}")
                error?.let { Notice(NoticeType.Info, "Couldn't create the plan", it) }
                when (preview) {
                    is PlanResult.Invalid -> Notice(NoticeType.Info, "Adjust the inputs", preview.reason)
                    is PlanResult.Ok -> {
                        val weeks = preview.weeks
                        AppCard {
                            Text("Phases · ${weeks.size} weeks", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                            PhaseBar(weeks.map { it.phase })
                        }
                        WeeklyBars(
                            "Weekly ${if (race.sport == Sport.Ride) "riding" else "running"} minutes", planned = weeks.map { it.targetMin }, done = weeks.map { null },
                            labels = weeks.map { if (it.index % 2 == 0) "W${it.index + 1}" else "" }, legendDone = "Done",
                        )
                        SectionHeader("Rules applied")
                        val peak = weeks.maxOf { it.targetMin }
                        RowGroup {
                            listOf(
                                "Volume +${(rules.maxWeeklyIncrease * 100).toInt()}% max per week" to "Peak ${peak / 60} h ${peak % 60} min",
                                "Every ${rules.lighterEvery}th week lighter" to "${(rules.lighterFactor * 100).toInt()}% of the last full week",
                                "Long session cap" to "+${rules.longIncreaseMaxMin} min per week max",
                                "Hard days separated" to "Never next to each other or the long day",
                                "Strength ${rules.strengthPerWeek}× per week" to "Dropped in the final taper week",
                                "Taper ${rules.taperWeeks(race)} weeks" to "Volume steps down before race week",
                            ).forEach { (t, s) -> ListRow(t, subtitle = s, icon = AppIcons.check, chevron = false) }
                        }
                    }
                    null -> {}
                }
            }
        }
    }
}

@Composable
fun WeekViewScreen(container: AppContainer, state: DashboardState, weekStart: LocalDate, onBack: () -> Unit, onWeek: (LocalDate) -> Unit) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    ReconcileEffect(container, state, data)
    val today = localDateOf(state.nowMs, zone)
    val zones = zonesFor(state, zone)
    val sessions = data.planned.filter { LocalDate.ofEpochDay(it.date) in weekStart..weekStart.plusDays(6) }
    val phase = sessions.firstOrNull()?.let { "Week ${it.week + 1} · ${it.phase}" } ?: "No plan this week"
    val done = Completion.weeklyMinutes(state.workouts.map { it.done() }, zone)
    val plannedMin = sessions.filter { it.sport != Sport.Strength.name }.sumOf { it.durationMin }
    val doneMin = done[weekStart] ?: 0
    val last = done[weekStart.minusWeeks(1)]
    ScreenFrame(topBar = { SmallTopBar(phase, onBack) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcons.arrowBack, "Previous week", { onWeek(weekStart.minusWeeks(1)) }, size = 36.dp, iconSize = 20.dp)
            Text("${Fmt.dayShort(weekStart.ms(zone))} – ${Fmt.dayShort(weekStart.plusDays(6).ms(zone))}", style = Theme.type.labelL, color = Theme.colors.textPrimary)
            AppIconButton(AppIcons.chevronRight, "Next week", { onWeek(weekStart.plusWeeks(1)) }, size = 36.dp, iconSize = 22.dp)
        }
        MetricTiles(
            listOf(
                "Planned" to "${plannedMin / 60}:${"%02d".format(plannedMin % 60)} h",
                "Done" to "${doneMin / 60}:${"%02d".format(doneMin % 60)} h",
                "vs last week" to (last?.takeIf { it > 0 }?.let { "${if (doneMin >= it) "+" else "−"}${kotlin.math.abs((doneMin - it) * 100 / it)}%" } ?: "—"),
            ),
        )
        RowGroup {
            (0..6).map { weekStart.plusDays(it.toLong()) }.forEach { d ->
                val day = sessions.filter { it.date == d.toEpochDay() }
                Row(
                    Modifier.fillMaxWidth().background(if (d == today) Theme.colors.bgAccentSubtle else Theme.colors.bgSurface).padding(horizontal = Dimens.spacingLg, vertical = Dimens.spacingMd),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(56.dp)) {
                        Text(dayShortName(d.dayOfWeek), style = Theme.type.labelS, color = Theme.colors.textTertiary)
                        Text("${d.monthValue}/${d.dayOfMonth}", style = Theme.type.labelM, color = Theme.colors.textPrimary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
                        if (day.isEmpty()) Text("Rest", style = Theme.type.bodyL, color = Theme.colors.textSecondary)
                        day.forEach { e ->
                            Text(sessionTitle(e), style = Theme.type.bodyL, color = if (e.status == "Missed") Theme.colors.textTertiary else Theme.colors.textPrimary)
                            Zones.forKind(WorkoutKind.valueOf(e.kind), zones)?.let { Text(it, style = Theme.type.bodyS, color = Theme.colors.textSecondary) }
                        }
                    }
                    day.firstOrNull { it.sport != Sport.Strength.name }?.let { e ->
                        val st = sessionStatus(e, state, zone)
                        when {
                            st == WorkoutCardStatus.Completed -> Tag("Done", TagType.InRange)
                            st == WorkoutCardStatus.Missed -> Tag("Missed", TagType.Low)
                            st == WorkoutCardStatus.Swapped -> Tag("Adjusted", TagType.Brand)
                            d == today -> Tag("Today", TagType.Brand)
                            else -> Tag("Planned", TagType.Neutral)
                        }
                    }
                }
            }
        }
        val notes = data.adjustments.filter { a -> sessions.any { it.id == a.sessionId } }
        notes.forEach { Caption("${it.summary}: ${it.reason}.") }
        if (sessions.isNotEmpty()) Caption("Fueling notes for each session are on the Training tab calendar. Every rule is editable in Settings › Targets.")
    }
}

@Composable
fun AdjustmentsScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit) {
    val data = rememberTrainingData(container)
    ReconcileEffect(container, state, data)
    val scope = rememberCoroutineScope()
    val today = localDateOf(state.nowMs, ZoneId.systemDefault()).toEpochDay()
    ScreenFrame(topBar = { SmallTopBar("Plan adjustments", onBack) }) {
        data.adjustments.filter { it.kind == "Swapped" && !it.reverted }.forEach { a ->
            val isToday = data.planned.any { it.id == a.sessionId && it.date == today }
            Notice(NoticeType.Gentle, a.summary, a.reason + ".", action = if (isToday) "Keep original plan" else null, onAction = { scope.launch { container.training.undoSwap(a) } })
        }
        SectionHeader("Recent changes")
        if (data.adjustments.isEmpty()) EmptyState("No changes yet", "Missed sessions and low-recovery days are handled here automatically by fixed rules.")
        else RowGroup {
            data.adjustments.take(30).forEach { a ->
                ListRow(a.summary + if (a.reverted) " · kept original" else "", subtitle = a.reason, icon = AppIcons.sync, chevron = false, trailing = Fmt.dayShort(a.timeMs).substringAfter(' '))
            }
        }
        Caption("Rules: easy and strength sessions are not made up; a missed hard session is dropped if another is within 2 days, else it replaces a nearby easy day; poor sleep or HRV below baseline swaps a hard session for easy. No AI is involved.")
    }
}

@Composable
fun ExportScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val today = localDateOf(state.nowMs, zone)
    val zones = zonesFor(state, zone)
    val until = when (range) { 0 -> PlanBuilder.monday(today).plusDays(6); 1 -> PlanBuilder.monday(today).plusDays(13); else -> LocalDate.MAX }
    val lines = data.planned
        .filter { it.status != "Missed" && LocalDate.ofEpochDay(it.date) >= today && LocalDate.ofEpochDay(it.date) <= until }
        .groupBy { it.date }
        .map { (d, list) ->
            val date = LocalDate.ofEpochDay(d)
            "${dayShortName(date.dayOfWeek).uppercase()} ${date.month.getDisplayName(TextStyle.SHORT, Locale.US).uppercase()} ${date.dayOfMonth} — " +
                list.joinToString(" + ") { e -> listOfNotNull(sessionTitle(e), Zones.forKind(WorkoutKind.valueOf(e.kind), zones), e.note.takeIf { it.isNotBlank() && !it.startsWith("Under") }).joinToString(" · ") }
        }
    val text = lines.joinToString("\n")
    ScreenFrame(
        topBar = { SmallTopBar("Export for Garmin", onBack) },
        footer = {
            AppButton(if (copied) "Copied" else "Copy list", { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("Training plan", text).toClipEntry()); copied = true } }, Modifier.fillMaxWidth(), enabled = text.isNotEmpty())
            AppButton("Share as text", {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share plan"))
            }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost, enabled = text.isNotEmpty())
        },
    ) {
        Text("Garmin workout push needs a developer API, so this is a plain list to enter in Garmin Connect by hand.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
        SegmentedControl(listOf("This week", "Next 2 weeks", "Full plan"), range, { range = it; copied = false })
        if (lines.isEmpty()) EmptyState("Nothing to export", "No upcoming planned sessions in this range.")
        else Column(
            Modifier.fillMaxWidth().clip(Shapes.lg).background(Theme.colors.bgSurfaceSunken).padding(Dimens.spacingLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        ) { lines.forEach { Text(it, style = Theme.type.bodyS, color = Theme.colors.textPrimary) } }
    }
}
