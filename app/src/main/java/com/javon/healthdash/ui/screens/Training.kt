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
import androidx.compose.ui.Alignment
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
import com.javon.healthdash.data.label
import com.javon.healthdash.data.inputs
import com.javon.healthdash.calc.DoneActivity
import com.javon.healthdash.calc.Phase
import com.javon.healthdash.calc.PlanProgress
import com.javon.healthdash.calc.ProgressSession
import com.javon.healthdash.ui.components.PlanHeroCard
import com.javon.healthdash.ui.components.GoalOutlookCard
import com.javon.healthdash.ui.components.ProgressLine
import com.javon.healthdash.ui.components.WeekStrip
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
        kind.recurring -> listOfNotNull(e.name ?: "Recurring session", e.distanceMi?.let { "${Fmt.amount(it)} mi" }, "${e.durationMin} min",
            when (kind) { WorkoutKind.ClubHard -> "hard"; WorkoutKind.ClubSteady -> "steady"; else -> "easy" }).joinToString(" · ")
        sport == Sport.Strength -> "Strength · ${e.durationMin} min"
        kind == WorkoutKind.Race -> "Race day" + (e.distanceMi?.let { " · ${Fmt.one(it)} mi" } ?: "")
        else -> "${kind.label} ${if (sport == Sport.Ride) "ride" else "run"} · " + (e.distanceMi?.let { "${Fmt.amount(it)} mi" } ?: "${e.durationMin} min")
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
    e.status == "Done" -> WorkoutCardStatus.Completed
    Completion.matches(e.toSession(), s.workouts.map { it.done() }, zone) != null -> WorkoutCardStatus.Completed
    e.status == "Missed" -> WorkoutCardStatus.Missed
    e.status == "Swapped" -> WorkoutCardStatus.Swapped
    else -> WorkoutCardStatus.Planned
}

/** True when a planned run was completed by a ride (cross-training on an easy day). */
fun doneAsRide(e: PlannedEntity, s: DashboardState, zone: ZoneId): Boolean =
    e.sport == Sport.Run.name && Completion.matches(e.toSession(), s.workouts.map { it.done() }, zone)?.sport == Sport.Ride

fun sessionDetail(e: PlannedEntity, zones: List<HrZone>): String =
    listOfNotNull(e.detail, (if (e.distanceMi != null && e.sport != Sport.Strength.name) "≈ ${e.durationMin / 60}:${"%02d".format(e.durationMin % 60)}" else null), Zones.forKind(WorkoutKind.valueOf(e.kind), zones), e.phase.lowercase().replaceFirstChar(Char::uppercase) + " week").joinToString(" · ")

data class RecentVolume(val weeklyMin: Int, val workouts: Int, val days: Int, val sources: Set<String>)

/**
 * Endurance minutes per week from Garmin (via Health Connect) over the last 28 days, scaled to the
 * days of data that exist — a new Garmin connection with one week of history isn't divided by four.
 */
fun recentVolume(s: DashboardState): RecentVolume {
    val from = s.nowMs - 28 * 24 * 3_600_000L
    val runsRides = s.workouts.filter { it.startMs >= from && sportOf(it.exerciseType).let { sp -> sp == Sport.Run || sp == Sport.Ride } }
    val firstData = listOfNotNull(s.workouts.minOfOrNull { it.startMs }, s.steps.minOfOrNull { it.startMs }).minOrNull() ?: s.nowMs
    val days = ((s.nowMs - maxOf(from, firstData)) / (24 * 3_600_000L)).toInt().coerceIn(7, 28)
    val total = runsRides.sumOf { (it.endMs - it.startMs) / MINUTE_MS }
    return RecentVolume((total * 7 / days).toInt(), runsRides.size, days, runsRides.map { it.source }.toSet())
}

fun recentWeeklyMinutes(s: DashboardState, zone: ZoneId): Int = recentVolume(s).weeklyMin

/** Last 30 days of Garmin runs that have a distance → weekly miles, longest run, typical pace. */
fun runFitness(s: DashboardState): com.javon.healthdash.calc.RunFitness? {
    val runs = s.workouts.filter { sportOf(it.exerciseType) == Sport.Run && (it.distanceM ?: 0.0) > 0 }
        .map { com.javon.healthdash.calc.RunSample(it.startMs, it.distanceM!! / com.javon.healthdash.calc.METERS_PER_MI, ((it.endMs - it.startMs) / 1000).toInt()) }
    val firstData = listOfNotNull(s.workouts.minOfOrNull { it.startMs }, s.steps.minOfOrNull { it.startMs }).minOrNull() ?: s.nowMs
    val historyDays = ((s.nowMs - firstData) / (24 * 3_600_000L)).toInt()
    val rides = s.workouts.filter { sportOf(it.exerciseType) == Sport.Ride }
        .map { com.javon.healthdash.calc.RideSample(it.startMs, ((it.endMs - it.startMs) / 1000).toInt(), it.distanceM?.div(com.javon.healthdash.calc.METERS_PER_MI)) }
    return com.javon.healthdash.calc.Fitness.fromActivities(runs, rides, s.nowMs, 30, historyDays)
}

/** Last 30 days of Garmin rides with distance → weekly miles, longest ride, typical speed. */
fun rideFitness(s: DashboardState): com.javon.healthdash.calc.RideFitness? {
    val firstData = listOfNotNull(s.workouts.minOfOrNull { it.startMs }, s.steps.minOfOrNull { it.startMs }).minOrNull() ?: s.nowMs
    val historyDays = ((s.nowMs - firstData) / (24 * 3_600_000L)).toInt()
    val rides = s.workouts.filter { sportOf(it.exerciseType) == Sport.Ride }
        .map { com.javon.healthdash.calc.RideSample(it.startMs, ((it.endMs - it.startMs) / 1000).toInt(), it.distanceM?.div(com.javon.healthdash.calc.METERS_PER_MI)) }
    return com.javon.healthdash.calc.RideFitnessCalc.fromRides(rides, s.nowMs, 30, historyDays)
}

/** "9:45" → 585 seconds. */
fun parsePace(text: String): Int? = text.trim().split(":").takeIf { it.size == 2 }?.let { (m, s) ->
    val mm = m.toIntOrNull(); val ss = s.toIntOrNull()
    if (mm == null || ss == null || ss !in 0..59 || mm !in 4..20) null else mm * 60 + ss
}

/** Asks for any Health Connect permissions added since setup (e.g. distance for run pace). */
@Composable
fun HealthPermissionNotice(container: AppContainer, onGranted: () -> Unit = {}) {
    var granted by remember { mutableStateOf<Set<String>?>(null) }
    val scope = rememberCoroutineScope()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { g ->
        granted = g
        scope.launch { runCatching { container.syncNow() }; onGranted() }
    }
    LaunchedEffect(Unit) { granted = container.health.granted() }
    val distance = androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.DistanceRecord::class)
    if (container.health.available && granted != null && distance !in granted!!) {
        Notice(NoticeType.Info, "Allow run distance", "Plans use the distance and pace of your last 30 days of Garmin runs. Health Connect needs one more permission for that.",
            action = "Allow", onAction = { launcher.launch(container.health.permissions) })
    }
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
        "${it.label()} · ${Fmt.dayShort(race.atStartOfDay(zone).toInstant().toEpochMilli())} · ${ChronoUnit.WEEKS.between(today, race)} weeks out"
    } ?: "No race plan yet"

    if (vo2Open) Vo2Sheet(onDismiss = { vo2Open = false }) { v -> scope.launch { container.training.addVo2(System.currentTimeMillis(), v); vo2Open = false } }
    dayOpen?.let { d ->
        AppSheet(Fmt.dayLong(d.atStartOfDay(zone).toInstant().toEpochMilli()), onDismiss = { dayOpen = null }) {
            val sessions = data.planned.filter { it.date == d.toEpochDay() }
            val ws = state.workouts.filter { localDateOf(it.startMs, zone) == d }
            if (sessions.isEmpty() && ws.isEmpty()) Text("Rest day.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
            sessions.forEach { e -> SessionCard(container, state, e, zones, zone) }
            if (ws.isNotEmpty()) RowGroup { ws.forEach { w -> WorkoutRow(w, zone) { dayOpen = null; onWorkout(w.id) } } }
            AppButton("Open week", { dayOpen = null; onWeek(PlanBuilder.monday(d)) }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
        }
    }

    val progress = nextPlan?.let { plan ->
        val sessions = data.planned.filter { it.planId == plan.id }.map {
            ProgressSession(it.id, LocalDate.ofEpochDay(it.date), Sport.valueOf(it.sport), WorkoutKind.valueOf(it.kind), it.durationMin, it.distanceMi, it.week, Phase.valueOf(it.phase), it.status, it.doneMin, it.doneMi)
        }
        if (sessions.isEmpty()) null else PlanProgress.compute(
            sessions,
            state.workouts.map { DoneActivity(localDateOf(it.startMs, zone), sportOf(it.exerciseType), ((it.endMs - it.startMs) / MINUTE_MS).toInt(), it.distanceM?.div(com.javon.healthdash.calc.METERS_PER_MI)) },
            today, LocalDate.ofEpochDay(plan.startDate), LocalDate.ofEpochDay(plan.raceDate),
            easyPaceSec = runFitness(state)?.takeIf { it.runs > 0 }?.easyPaceSec ?: com.javon.healthdash.calc.Fitness.DEFAULT_EASY_PACE_SEC,
        )
    }

    ScreenFrame(topBar = { LargeTopBar("Training", overline) }, bottomBar = { BottomNav(Tab.Training) { onTab(it) } }) {
        HealthPermissionNotice(container)
        val unit = if (progress?.sport == Sport.Ride) "ride" else "run"
        if (progress != null && nextPlan != null) {
            if (LocalDate.ofEpochDay(nextPlan.startDate).isAfter(today)) Notice(NoticeType.Info, "Plan starts ${Fmt.dayShort(LocalDate.ofEpochDay(nextPlan.startDate).atStartOfDay(zone).toInstant().toEpochMilli())}", "Your ${nextPlan.label()} plan hasn't started yet.")
            PlanHeroCard(progress, nextPlan.label(), Fmt.dayShort(LocalDate.ofEpochDay(nextPlan.raceDate).atStartOfDay(zone).toInstant().toEpochMilli())) { onPlans() }
            val raceType = com.javon.healthdash.calc.RaceType.valueOf(nextPlan.raceType)
            if (!raceType.relay) {
                val raceMi = nextPlan.inputs().raceMi
                val samples = state.workouts.filter { sportOf(it.exerciseType) == progress.sport && (it.distanceM ?: 0.0) > 0 }.map {
                    com.javon.healthdash.calc.EffortSample(it.distanceM!! / com.javon.healthdash.calc.METERS_PER_MI, ((it.endMs - it.startMs) / 1000).toInt(), ((state.nowMs - it.startMs) / (24 * 3_600_000L)).toInt())
                }
                val vo2 = state.vo2.filter { state.nowMs - it.timeMs < 90L * 24 * 3_600_000L }.maxByOrNull { it.timeMs }?.value
                val sessionsByWeek = data.planned.filter { it.planId == nextPlan.id }.groupBy { it.week }
                val trainingLeft = sessionsByWeek.count { (w, l) -> w > progress.weekIndex && l.first().phase in setOf("Base", "Build", "Peak") }
                val outlook = com.javon.healthdash.calc.GoalLikelihood.estimate(
                    progress.sport, raceMi, (nextPlan.goalMin ?: 0) * 60, samples, vo2,
                    trainingLeft, progress.totalWeeks - progress.weekIndex - 1, if (progress.due14 == 0) 1.0 else progress.done14.toDouble() / progress.due14,
                    typicalPaceSec = runFitness(state)?.takeIf { it.runs > 0 }?.easyPaceSec,
                )
                if (outlook != null) GoalOutlookCard(outlook, nextPlan.goalMin != null, progress.sport)
                else AppCard {
                    Text("Chance of hitting your goal", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                    Text(
                        "Needs a ${if (progress.sport == Sport.Ride) "ride" else "run"} of 3+ miles with distance in the last 6 weeks${if (progress.sport == Sport.Run) ", or your VO2 max from the watch" else ""}.",
                        style = Theme.type.bodyS, color = Theme.colors.textSecondary,
                    )
                    if (progress.sport == Sport.Run) AppButton("Add VO2 max", { vo2Open = true }, style = ButtonStyle.Secondary, size = ButtonSize.Small, icon = AppIcons.add)
                }
            }

            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("This week", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                    Text("Week plan", style = Theme.type.labelM, color = Theme.colors.textAccent, modifier = Modifier.clickable { onWeek(PlanBuilder.monday(today)) })
                }
                WeekStrip(progress.thisWeek) { dayOpen = it.date }
                ProgressLine("Sessions", "${progress.weekSessionsDone} of ${progress.weekSessions}", if (progress.weekSessions == 0) 0.0 else progress.weekSessionsDone.toDouble() / progress.weekSessions)
                if (progress.weekPlannedMi > 0) {
                    val total = progress.weekDoneMi + progress.weekRideCreditMi
                    ProgressLine("Miles", "${Fmt.one(total)} of ${Fmt.one(progress.weekPlannedMi)} mi", total / progress.weekPlannedMi)
                    if (progress.weekRideCreditMi > 0) Caption("${Fmt.one(progress.weekDoneMi)} mi running + ${Fmt.one(progress.weekRideCreditMi)} mi run-equivalent from rides (40% of ride time at your easy pace).")
                }
                if (progress.sport == Sport.Run) Caption("A ride at least as long as a planned easy or recovery run counts as that run. Long runs, tempos and intervals stay run-only.")
            }
        }

        val todaySessions = data.planned.filter { it.date == today.toEpochDay() }
        SectionHeader("Today · ${Fmt.dayShort(state.nowMs)}", if (data.plans.isEmpty() || progress != null) null else "Week plan") { onWeek(PlanBuilder.monday(today)) }
        data.adjustments.firstOrNull { a -> a.kind == "Swapped" && !a.reverted && todaySessions.any { it.id == a.sessionId } }?.let { a ->
            Notice(NoticeType.Gentle, a.summary, a.reason + ". Hard sessions after poor recovery are swapped for easy.", action = "Keep original plan", onAction = { scope.launch { container.training.undoSwap(a) } })
        }
        when {
            data.plans.isEmpty() -> EmptyState("No race plan yet", "Create a plan for any run or ride distance. It's built by fixed rules from your recent training.")
            todaySessions.isEmpty() -> AppCard { Text("Rest day — nothing planned today.", style = Theme.type.bodyM, color = Theme.colors.textSecondary) }
            else -> todaySessions.forEach { e -> SessionCard(container, state, e, zones, zone) }
        }

        if (progress != null) {
            progress.nextKey?.takeIf { it.date.isAfter(today) }?.let { k ->
                data.planned.firstOrNull { it.id == k.id }?.let { e ->
                    SectionHeader("Next key workout · ${Fmt.dayShort(k.date.atStartOfDay(zone).toInstant().toEpochMilli())}")
                    SessionCard(container, state, e, zones, zone)
                }
            }
            SectionHeader("Progress")
            MetricTiles(
                listOfNotNull(
                    "Sessions done" to "${progress.doneSoFar}/${progress.dueSoFar}",
                    "Last 14 days" to if (progress.due14 == 0) "—" else "${progress.done14 * 100 / progress.due14}%",
                    "Last long $unit" to (progress.lastLong?.let { (s, d) -> d.distanceMi?.let { "${Fmt.one(it)} mi" } ?: "${d.durationMin} min" } ?: "—"),
                ),
            )
            progress.lastLong?.let { (s, d) ->
                val planned = s.distanceMi
                if (planned != null && d.distanceMi != null) Caption("Last long $unit: ${Fmt.one(d.distanceMi!!)} of ${Fmt.one(planned)} mi planned · ${Fmt.dayShort(s.date.atStartOfDay(zone).toInstant().toEpochMilli())}")
            }
            val from = (progress.weekIndex - 5).coerceAtLeast(0)
            val shown = progress.weeks.filter { it.week in from..(progress.weekIndex + 2) }
            WeeklyBars(
                "Weekly miles · plan vs done", planned = shown.map { it.plannedMi.toInt() }, done = shown.map { if (it.week > progress.weekIndex) null else (it.doneMi + it.rideCreditMi).toInt() },
                labels = shown.map { if (it.week == progress.weekIndex) "Now" else "W${it.week + 1}" },
            )
        }
        CalendarMonth(
            month, today,
            stateOf = { d ->
                val sessions = data.planned.filter { it.date == d.toEpochDay() }
                when {
                    done.any { localDateOf(it.window.startMs, zone) == d } || sessions.any { it.status == "Done" } -> DayState.Completed
                    sessions.any { it.status == "Missed" } -> DayState.Missed
                    sessions.isNotEmpty() && !d.isBefore(today) -> DayState.Planned
                    else -> DayState.Rest
                }
            },
            onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) }, onDay = { dayOpen = it },
        )

        RowGroup {
            ListRow("Race plans", subtitle = if (data.plans.isEmpty()) "Create your first plan" else "${data.plans.size} plan${if (data.plans.size == 1) "" else "s"} in sequence", icon = AppIcons.calendar, onClick = onPlans)
            ListRow("Plan adjustments", subtitle = "${data.adjustments.size} rule-based changes", icon = AppIcons.sync, onClick = onAdjustments)
        }

        val thisWeek = PlanBuilder.monday(today)
        val weeks = (-5..2).map { thisWeek.plusWeeks(it.toLong()) }
        val doneByWeek = Completion.weeklyMinutes(done, zone)
        val plannedByWeek = data.planned.filter { it.sport != Sport.Strength.name }.groupBy { PlanBuilder.monday(LocalDate.ofEpochDay(it.date)) }.mapValues { (_, l) -> l.sumOf { it.durationMin } }
        if (progress == null) WeeklyBars(
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

/**
 * A planned session with "Mark complete" for workouts done without the watch. Shown for today and past days;
 * watch-matched sessions are already done, manual ones can be undone.
 */
@Composable
fun SessionCard(container: AppContainer, state: DashboardState, e: PlannedEntity, zones: List<HrZone>, zone: ZoneId) {
    val scope = rememberCoroutineScope()
    var marking by remember { mutableStateOf(false) }
    val today = localDateOf(state.nowMs, zone)
    val status = sessionStatus(e, state, zone)
    val manual = e.status == "Done"
    val canMark = status != WorkoutCardStatus.Completed && !LocalDate.ofEpochDay(e.date).isAfter(today)
    val detail = when {
        manual -> "Marked done" + (listOfNotNull(e.doneMi?.let { "${Fmt.one(it)} mi" }, e.doneMin?.let { "$it min" }).takeIf { it.isNotEmpty() }?.joinToString(" · ", " · ") ?: " as planned") + " · " + sessionDetail(e, zones)
        doneAsRide(e, state, zone) -> "Done as a ride · " + sessionDetail(e, zones)
        else -> sessionDetail(e, zones)
    }
    WorkoutCard(
        sessionTitle(e), detail, e.note, status, sportIcon(Sport.valueOf(e.sport)),
        action = when { manual -> "Undo complete"; canMark -> "Mark complete"; else -> null },
        onAction = { if (manual) scope.launch { container.training.undoDone(e.id, zone) } else marking = true },
    )
    if (marking) MarkDoneSheet(e, onDismiss = { marking = false }) { min, mi ->
        scope.launch { container.training.markDone(e.id, min, mi); marking = false }
    }
}

@Composable
fun MarkDoneSheet(e: PlannedEntity, onDismiss: () -> Unit, onSave: (Int?, Double?) -> Unit) {
    val endurance = e.sport != Sport.Strength.name
    var minutes by remember { mutableStateOf(e.durationMin.toString()) }
    var miles by remember { mutableStateOf(e.distanceMi?.let { Fmt.one(it) } ?: "") }
    AppSheet("Mark complete", onDismiss) {
        Text(sessionTitle(e), style = Theme.type.labelL, color = Theme.colors.textPrimary)
        Text("Worked out without the watch? Log what you did — it counts toward your plan, miles and goal outlook.", style = Theme.type.bodyS, color = Theme.colors.textSecondary)
        AppTextField("Time", minutes, { minutes = it.filter(Char::isDigit).take(3) }, suffix = "min", keyboardType = KeyboardType.Number)
        if (endurance) AppTextField("Distance", miles, { miles = it }, suffix = "mi", keyboardType = KeyboardType.Decimal,
            error = if (miles.isNotBlank() && miles.toDoubleOrNull() == null) "Enter miles, e.g. 4.5" else null)
        val m = minutes.toIntOrNull()?.takeIf { it > 0 }
        val d = if (endurance) miles.toDoubleOrNull()?.takeIf { it > 0 } else null
        AppButton("Save", {
            // Store only what differs from the plan; null = as planned.
            onSave(m?.takeIf { it != e.durationMin }, d?.takeIf { e.distanceMi == null || kotlin.math.abs(it - e.distanceMi) > 0.01 })
        }, Modifier.fillMaxWidth(), enabled = m != null && (miles.isBlank() || d != null))
        AppButton("Cancel", onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
    }
}
