package com.javon.healthdash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import com.javon.healthdash.ui.components.Toggle
import com.javon.healthdash.calc.PlanResult
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.javon.healthdash.ui.components.TodayTrainingCard
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
/** Watch workouts whose distance you corrected on the matched planned session, by workout id. */
fun correctedRuns(planned: List<PlannedEntity>, s: DashboardState, zone: ZoneId): Map<String, PlannedEntity> =
    planned.filter { it.status != "Done" && it.doneMi != null && it.sport != Sport.Strength.name }
        .mapNotNull { e -> matchedWorkout(e, s, zone)?.let { it.id to e } }.toMap()

/**
 * Current running fitness from the last 30 days. Watch runs count with their distance — or the distance you
 * corrected on the session ("Edit distance") — and runs marked done by hand count too, since the watch
 * connection can drop runs. Typical pace comes from watch runs only (hand-logged times are rough).
 */
fun runFitness(s: DashboardState, planned: List<PlannedEntity> = emptyList()): com.javon.healthdash.calc.RunFitness? {
    val zone = ZoneId.systemDefault()
    val corrected = correctedRuns(planned, s, zone)
    val watch = s.workouts.filter { sportOf(it.exerciseType) == Sport.Run && ((it.distanceM ?: 0.0) > 0 || it.id in corrected) }.map {
        val fix = corrected[it.id]
        com.javon.healthdash.calc.RunSample(it.startMs, fix?.doneMi ?: (it.distanceM!! / com.javon.healthdash.calc.METERS_PER_MI), fix?.doneMin?.times(60) ?: ((it.endMs - it.startMs) / 1000).toInt())
    }
    val manual = planned.filter { it.status == "Done" && it.sport == Sport.Run.name && (it.doneMi ?: it.distanceMi) != null }.map {
        com.javon.healthdash.calc.RunSample(LocalDate.ofEpochDay(it.date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), it.doneMi ?: it.distanceMi!!, (it.doneMin ?: it.durationMin) * 60)
    }
    val firstData = listOfNotNull(s.workouts.minOfOrNull { it.startMs }, s.steps.minOfOrNull { it.startMs }).minOrNull() ?: s.nowMs
    val historyDays = ((s.nowMs - firstData) / (24 * 3_600_000L)).toInt()
    val rides = s.workouts.filter { sportOf(it.exerciseType) == Sport.Ride }
        .map { com.javon.healthdash.calc.RideSample(it.startMs, ((it.endMs - it.startMs) / 1000).toInt(), it.distanceM?.div(com.javon.healthdash.calc.METERS_PER_MI)) }
    val all = com.javon.healthdash.calc.Fitness.fromActivities(watch + manual, rides, s.nowMs, 30, historyDays) ?: return null
    val watchPace = com.javon.healthdash.calc.Fitness.fromActivities(watch, emptyList(), s.nowMs, 30, historyDays)?.takeIf { it.runs > 0 }?.easyPaceSec
    return all.copy(easyPaceSec = watchPace ?: all.easyPaceSec)
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
    onRaceDay: (Long) -> Unit = {},
) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    ReconcileEffect(container, state, data)
    val today = localDateOf(state.nowMs, zone)
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var dayOpen by remember { mutableStateOf<LocalDate?>(null) }
    var vo2Open by remember { mutableStateOf(false) }
    var travelOpen by remember { mutableStateOf(false) }
    var rebuildOpen by remember { mutableStateOf(false) }
    var movingId by remember { mutableStateOf<Long?>(null) }
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
            correctedRuns(data.planned, state, zone).let { fix ->
                state.workouts.map { DoneActivity(localDateOf(it.startMs, zone), sportOf(it.exerciseType), fix[it.id]?.doneMin ?: ((it.endMs - it.startMs) / MINUTE_MS).toInt(), fix[it.id]?.doneMi ?: it.distanceM?.div(com.javon.healthdash.calc.METERS_PER_MI)) }
            },
            today, LocalDate.ofEpochDay(plan.startDate), LocalDate.ofEpochDay(plan.raceDate),
            easyPaceSec = runFitness(state, data.planned)?.takeIf { it.runs > 0 }?.easyPaceSec ?: com.javon.healthdash.calc.Fitness.DEFAULT_EASY_PACE_SEC,
        )
    }

    ScreenFrame(topBar = { LargeTopBar("Training", overline) }, bottomBar = { BottomNav(Tab.Training) { onTab(it) } }) {
        HealthPermissionNotice(container)
        // Today's sessions first, as a checklist — so a second session (e.g. strength) is never missed.
        if (data.plans.isNotEmpty()) {
            val openStrength = LocalOpenStrength.current
            val summary = com.javon.healthdash.data.todaySummary(data.planned, state.workouts, today, zone)
            val hour = java.time.Instant.ofEpochMilli(state.nowMs).atZone(zone).hour
            TodayTrainingCard(
                summary, hour,
                onItem = { item -> if (item.sport == Sport.Strength) openStrength(item.id) else dayOpen = today },
                onMove = { item -> movingId = item.id },
            )
            data.planned.firstOrNull { it.id == movingId }?.let { e -> MoveSheet(container, state, e, zone, onDismiss = { movingId = null }) }
        }
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
                    typicalPaceSec = runFitness(state, data.planned)?.takeIf { it.runs > 0 }?.easyPaceSec,
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

            // Effort ratings → ease the next week, or suggest a goal check. Not again within 7 days of easing.
            val rated = data.planned.filter { it.planId == nextPlan.id && it.rpe != null }.map {
                com.javon.healthdash.calc.RatedSession(LocalDate.ofEpochDay(it.date), WorkoutKind.valueOf(it.kind), it.rpe!!, reviewFor(it, state, zone, zones)?.pace ?: com.javon.healthdash.calc.PaceCheck.NoData)
            }
            val recentlyEased = data.adjustments.any { it.kind == "Eased" && state.nowMs - it.timeMs < 7L * 24 * 3_600_000L }
            com.javon.healthdash.calc.Adaptation.suggest(rated, today)?.takeIf { !recentlyEased || it.kind != com.javon.healthdash.calc.SuggestionKind.Ease }?.let { s ->
                if (s.kind == com.javon.healthdash.calc.SuggestionKind.Ease) Notice(NoticeType.Gentle, s.title, s.reason, action = "Ease next 7 days", onAction = { scope.launch { container.training.ease(nextPlan.id, today, s.factor, s.reason) } })
                else Notice(NoticeType.Info, s.title, s.reason, action = "Race plans", onAction = onPlans)
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
            if (nextPlan != null) {
                ListRow("Race-day plan", subtitle = "Splits, fueling and carb loading", icon = AppIcons.run, onClick = { onRaceDay(nextPlan.id) })
                ListRow("Travel or time off", subtitle = "Skip days and rebuild the plan around them", icon = AppIcons.calendar, onClick = { travelOpen = true })
                ListRow("Rebuild plan from today", subtitle = "Apply the latest rules and your current fitness", icon = AppIcons.sync, onClick = { rebuildOpen = true })
            }
            ListRow("Plan adjustments", subtitle = "${data.adjustments.size} changes", icon = AppIcons.sync, onClick = onAdjustments)
        }
        if (travelOpen && nextPlan != null) TravelSheet(container, state, nextPlan, zone) { travelOpen = false }
        if (rebuildOpen && nextPlan != null) RebuildSheet(container, state, nextPlan, zone) { rebuildOpen = false }

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
/** Opens the strength workout screen for a planned strength session (provided by navigation). */
val LocalOpenStrength = androidx.compose.runtime.staticCompositionLocalOf<(Long) -> Unit> { {} }

/** The watch workout that completed a planned session, if any. */
fun matchedWorkout(e: PlannedEntity, s: DashboardState, zone: ZoneId): WorkoutEntity? =
    if (e.status == "Done") null else s.workouts.firstOrNull { w -> Completion.matches(e.toSession(), listOf(w.done()), zone) != null }

/** Post-session review from the matched watch workout or the manual log. */
fun reviewFor(e: PlannedEntity, s: DashboardState, zone: ZoneId, zones: List<HrZone>): com.javon.healthdash.calc.SessionReview? {
    if (e.sport == Sport.Strength.name) return null
    val kind = WorkoutKind.valueOf(e.kind)
    val sport = Sport.valueOf(e.sport)
    if (e.status == "Done") return com.javon.healthdash.calc.Feedback.review(kind, sport, e.durationMin, e.distanceMi, e.doneMin ?: e.durationMin, e.doneMi ?: e.distanceMi?.takeIf { false }, null, zones)
    val w = matchedWorkout(e, s, zone) ?: return null
    val doneSport = sportOf(w.exerciseType)
    return com.javon.healthdash.calc.Feedback.review(
        kind, if (doneSport == Sport.Ride && sport == Sport.Run) Sport.Ride else sport, e.durationMin,
        if (doneSport == Sport.Ride && sport == Sport.Run) null else e.distanceMi,
        e.doneMin ?: ((w.endMs - w.startMs) / MINUTE_MS).toInt(), e.doneMi ?: w.distanceM?.div(com.javon.healthdash.calc.METERS_PER_MI), w.avgHr, zones,
    )
}

/**
 * A planned session: "Mark complete" for workouts done without the watch, "Move" to another day, and — once
 * done — a review against the plan and a "How did it feel?" rating that drives plan easing.
 */
@Composable
fun SessionCard(container: AppContainer, state: DashboardState, e: PlannedEntity, zones: List<HrZone>, zone: ZoneId) {
    val scope = rememberCoroutineScope()
    val openStrength = LocalOpenStrength.current
    var marking by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var correcting by remember { mutableStateOf(false) }
    val today = localDateOf(state.nowMs, zone)
    val date = LocalDate.ofEpochDay(e.date)
    val status = sessionStatus(e, state, zone)
    val manual = e.status == "Done"
    val strength = e.sport == Sport.Strength.name
    val canMark = status != WorkoutCardStatus.Completed && !date.isAfter(today)
    val canMove = status != WorkoutCardStatus.Completed && e.status != "Dropped" && !date.isBefore(today) && WorkoutKind.valueOf(e.kind).let { it != WorkoutKind.Race && !it.recurring }
    val review = if (status == WorkoutCardStatus.Completed) reviewFor(e, state, zone, zones) else null
    // The watch run that completed this (run/ride) session, if any — its distance can be corrected.
    val watchRun = if (status == WorkoutCardStatus.Completed && !strength && !manual) matchedWorkout(e, state, zone) else null
    val detail = when {
        manual -> "Marked done" + (listOfNotNull(e.doneMi?.let { "${Fmt.one(it)} mi" }, e.doneMin?.let { "$it min" }).takeIf { it.isNotEmpty() }?.joinToString(" · ", " · ") ?: " as planned") + " · " + sessionDetail(e, zones)
        doneAsRide(e, state, zone) -> "Done as a ride · " + sessionDetail(e, zones)
        else -> sessionDetail(e, zones)
    }
    WorkoutCard(
        sessionTitle(e), detail, e.note, status, sportIcon(Sport.valueOf(e.sport)),
        onClick = if (strength) ({ openStrength(e.id) }) else null,
        action = when { manual -> "Undo complete"; canMark -> "Mark complete"; else -> null },
        onAction = { if (manual) scope.launch { container.training.undoDone(e.id, zone) } else marking = true },
        secondary = when { strength && status != WorkoutCardStatus.Completed -> "Open workout"; canMove -> "Move"; watchRun != null -> "Edit distance"; else -> null },
        onSecondary = { if (strength && status != WorkoutCardStatus.Completed) openStrength(e.id) else if (canMove) moving = true else correcting = true },
        extra = {
            if (review != null) SessionReviewBlock(review, Sport.valueOf(e.sport))
            if (status == WorkoutCardStatus.Completed && !strength) EffortPicker(e.rpe) { r -> scope.launch { container.training.setRpe(e.id, r) } }
        },
    )
    if (marking) MarkDoneSheet(e, onDismiss = { marking = false }) { min, mi ->
        scope.launch { container.training.markDone(e.id, min, mi); marking = false }
    }
    watchRun?.let { w ->
        if (correcting) MarkDoneSheet(e, onDismiss = { correcting = false }, correcting = ((w.endMs - w.startMs) / MINUTE_MS).toInt() to w.distanceM?.div(com.javon.healthdash.calc.METERS_PER_MI)) { min, mi ->
            scope.launch { container.training.setActual(e.id, min, mi); correcting = false }
        }
    }
    if (moving) MoveSheet(container, state, e, zone, onDismiss = { moving = false })
}

@Composable
fun SessionReviewBlock(r: com.javon.healthdash.calc.SessionReview, sport: Sport) {
    val c = Theme.colors
    Column(
        Modifier.fillMaxWidth().clip(com.javon.healthdash.ui.theme.Shapes.md).background(c.bgSurfaceSunken).padding(Dimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(r.headline, style = Theme.type.labelL, color = c.textPrimary)
            Tag(r.pace.label, when (r.pace) { com.javon.healthdash.calc.PaceCheck.OnTarget -> TagType.InRange; com.javon.healthdash.calc.PaceCheck.NoData -> TagType.Neutral; else -> TagType.High })
        }
        val tiles = listOfNotNull(
            r.completion?.let { "Done" to "${(it * 100).roundToInt()}%" },
            r.plannedPace?.let { pp -> r.actualPace?.let { ap ->
                if (sport == Sport.Ride) "Speed" to "${Fmt.one(ap / 10.0)} / ${Fmt.one(pp / 10.0)} mph"
                else "Pace" to "${com.javon.healthdash.calc.PaceMath.fmt(ap)} / ${com.javon.healthdash.calc.PaceMath.fmt(pp)}"
            } },
            r.hrZone?.let { "Avg HR" to "Z$it" },
        )
        if (tiles.isNotEmpty()) Text(tiles.joinToString("   ·   ") { "${it.first} ${it.second}" }, style = Theme.type.bodyS, color = c.textSecondary)
        r.notes.forEach { Text(it, style = Theme.type.bodyS, color = c.textSecondary) }
    }
}

/** "How did it feel?" 1–10. */
@Composable
fun EffortPicker(rpe: Int?, onPick: (Int?) -> Unit) {
    val c = Theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
        Text(rpe?.let { "Felt $it/10 · ${com.javon.healthdash.calc.EffortBand.of(it).label}" } ?: "How did it feel? (1 easy – 10 all out)", style = Theme.type.labelM, color = c.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..10).forEach { n ->
                val on = rpe == n
                Box(
                    Modifier.weight(1f).height(32.dp).clip(com.javon.healthdash.ui.theme.Shapes.sm)
                        .background(if (on) c.bgAccent else c.bgNeutral).clickable { onPick(if (on) null else n) },
                    contentAlignment = Alignment.Center,
                ) { Text("$n", style = Theme.type.labelS, color = if (on) c.textOnAccent else c.textPrimary) }
            }
        }
    }
}

/** Move a session to another day this week (today or later), with rule warnings. */
@Composable
fun MoveSheet(container: AppContainer, state: DashboardState, e: PlannedEntity, zone: ZoneId, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val data = rememberTrainingData(container)
    val today = localDateOf(state.nowMs, zone)
    val from = LocalDate.ofEpochDay(e.date)
    val monday = PlanBuilder.monday(from)
    val days = (0..13).map { monday.plusDays(it.toLong()) }.filter { !it.isBefore(today) && it != from }
    var to by remember { mutableStateOf<LocalDate?>(null) }
    val week = data.planned.filter { it.planId == e.planId && it.status != "Dropped" && LocalDate.ofEpochDay(it.date) in monday.minusDays(1)..monday.plusDays(14) }
    val warnings = to?.let { t -> com.javon.healthdash.calc.Reschedule.warnings(e.toSession(), t, week.filter { it.id != e.id }.map { it.toSession() }) } ?: emptyList()
    AppSheet("Move ${sessionTitle(e).substringBefore(" ·")}", onDismiss) {
        Text("From ${Fmt.dayShort(from.atStartOfDay(zone).toInstant().toEpochMilli())} to:", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            days.forEach { d -> FilterChip(Fmt.dayShort(d.atStartOfDay(zone).toInstant().toEpochMilli()), d == to, { to = d }) }
        }
        warnings.forEach { Notice(NoticeType.Gentle, "Heads up", it) }
        if (to != null && warnings.isEmpty()) Caption("No conflicts with your other sessions.")
        AppButton(if (warnings.isEmpty()) "Move" else "Move anyway", { scope.launch { container.training.move(e.id, to!!); onDismiss() } }, Modifier.fillMaxWidth(), enabled = to != null)
    }
}

@Composable
fun MarkDoneSheet(
    e: PlannedEntity, onDismiss: () -> Unit,
    /** Editing a watch run: prefill with what the watch recorded and always save exact values. */
    correcting: Pair<Int, Double?>? = null,
    onSave: (Int?, Double?) -> Unit,
) {
    val endurance = e.sport != Sport.Strength.name
    var minutes by remember { mutableStateOf((correcting?.first ?: e.doneMin ?: e.durationMin).toString()) }
    var miles by remember { mutableStateOf((e.doneMi ?: correcting?.second ?: e.distanceMi)?.let { Fmt.one(it) } ?: "") }
    AppSheet(if (correcting != null) "Edit distance" else "Mark complete", onDismiss) {
        Text(sessionTitle(e), style = Theme.type.labelL, color = Theme.colors.textPrimary)
        Text(
            if (correcting != null) "Watch dropped part of the run? Enter what you actually ran — it's used for your progress, long-run history and plan sizing."
            else "Worked out without the watch? Log what you did — it counts toward your plan, miles and goal outlook.",
            style = Theme.type.bodyS, color = Theme.colors.textSecondary,
        )
        AppTextField("Time", minutes, { minutes = it.filter(Char::isDigit).take(3) }, suffix = "min", keyboardType = KeyboardType.Number)
        if (endurance) AppTextField("Distance", miles, { miles = it }, suffix = "mi", keyboardType = KeyboardType.Decimal,
            error = if (miles.isNotBlank() && miles.toDoubleOrNull() == null) "Enter miles, e.g. 4.5" else null)
        val m = minutes.toIntOrNull()?.takeIf { it > 0 }
        val d = if (endurance) miles.toDoubleOrNull()?.takeIf { it > 0 } else null
        AppButton("Save", {
            // Marking done stores only what differs from the plan (null = as planned); a correction stores exact values.
            if (correcting != null) onSave(m, d)
            else onSave(m?.takeIf { it != e.durationMin }, d?.takeIf { e.distanceMi == null || kotlin.math.abs(it - e.distanceMi) > 0.01 })
        }, Modifier.fillMaxWidth(), enabled = m != null && (miles.isBlank() || d != null))
        AppButton("Cancel", onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
    }
}

/**
 * Travel / time off: drops the sessions in a date range and, optionally, regenerates the rest of the plan from
 * the day after with current fitness (same race, goal and days).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TravelSheet(container: AppContainer, state: DashboardState, plan: PlanEntity, zone: ZoneId, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = localDateOf(state.nowMs, zone)
    var from by remember { mutableStateOf(today) }
    var to by remember { mutableStateOf(today.plusDays(6)) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var rebuild by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val recurring by container.prefs.recurring.collectAsStateWithLifecycle()
    val rules = TargetsRepository.toRules(state.targetValues)
    val fuel = TargetsRepository.toFuelTable(state.targetValues)
    val race = LocalDate.ofEpochDay(plan.raceDate)
    val back = to.plusDays(1)

    picking?.let { which ->
        val ds = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = (if (which == 0) from else to).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    ds.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        if (which == 0) { from = d; if (to.isBefore(d)) to = d } else to = if (d.isBefore(from)) from else d
                    }
                    picking = null
                }) { Text("Set") }
            },
            dismissButton = { androidx.compose.material3.TextButton({ picking = null }) { Text("Cancel") } },
        ) { androidx.compose.material3.DatePicker(ds) }
    }

    fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    val planned = rememberTrainingData(container).planned
    val skipped = planned.count { it.planId == plan.id && it.status == "Planned" && LocalDate.ofEpochDay(it.date) in from..to }
    AppSheet("Travel or time off", onDismiss) {
        RowGroup {
            ListRow("First day off", trailing = Fmt.dayShort(ms(from)), icon = AppIcons.calendar, onClick = { picking = 0 })
            ListRow("Last day off", trailing = Fmt.dayShort(ms(to)), icon = AppIcons.calendar, onClick = { picking = 1 })
        }
        Text("$skipped planned ${if (skipped == 1) "session" else "sessions"} will be skipped.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Rebuild the plan from ${Fmt.dayShort(ms(back))}", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                Text("Regenerates the remaining weeks from your current fitness, so you don't jump back in at full load.", style = Theme.type.bodyS, color = Theme.colors.textSecondary)
            }
            Toggle(rebuild, { rebuild = it })
        }
        error?.let { Notice(NoticeType.Info, "Couldn't rebuild", it) }
        AppButton("Save", {
            scope.launch {
                error = null
                if (rebuild && back.isBefore(race)) {
                    val result = regeneratePlan(plan, back, state, recurring.map { it.toFixed() }, rules, fuel, runFitness(state, planned))
                    when (result) {
                        null -> { error = "Needs recent ${if (plan.inputs().race.sport == Sport.Ride) "rides" else "runs"} from Garmin to size the plan. Days were not skipped."; return@launch }
                        is PlanResult.Invalid -> { error = result.reason + ". Days were not skipped."; return@launch }
                        is PlanResult.Ok -> { container.training.skip(plan.id, from, to); container.training.rebuildFrom(plan, back, result) }
                    }
                } else container.training.skip(plan.id, from, to)
                onDismiss()
            }
        }, Modifier.fillMaxWidth())
    }
}

/** Regenerates [plan] from [from] with the current rules (same race, goal and days). Null when there's no fitness to size it. */
fun regeneratePlan(
    plan: PlanEntity, from: LocalDate, state: DashboardState, fixed: List<com.javon.healthdash.calc.FixedSession>,
    rules: com.javon.healthdash.calc.TrainingRules, fuel: com.javon.healthdash.calc.FuelTable, run: com.javon.healthdash.calc.RunFitness?,
): PlanResult? {
    val inputs = plan.inputs().copy(startDate = from, fixed = fixed)
    return if (inputs.race.sport == Sport.Ride) rideFitness(state)?.let { com.javon.healthdash.calc.RidePlanBuilder.generate(inputs, it, rules, fuel).first }
    else run?.let { com.javon.healthdash.calc.RunPlanBuilder.generate(inputs, it, rules, com.javon.healthdash.calc.RunRules(), fuel).first }
}

/**
 * Rebuilds the rest of the plan from today with the current rules (run club counts as the tempo, paces from
 * current fitness). Shows — and lets you correct — the fitness it sizes the plan from.
 */
@Composable
fun RebuildSheet(container: AppContainer, state: DashboardState, plan: PlanEntity, zone: ZoneId, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = localDateOf(state.nowMs, zone)
    val planned = rememberTrainingData(container).planned
    val recurring by container.prefs.recurring.collectAsStateWithLifecycle()
    val rules = TargetsRepository.toRules(state.targetValues)
    val fuel = TargetsRepository.toFuelTable(state.targetValues)
    val auto = runFitness(state, planned)
    var weekly by remember(auto) { mutableStateOf(auto?.let { Fmt.one(it.weeklyMiles) } ?: "") }
    var longest by remember(auto) { mutableStateOf(auto?.let { Fmt.one(it.longestRunMi) } ?: "") }
    var pace by remember(auto) { mutableStateOf(auto?.let { com.javon.healthdash.calc.PaceMath.fmt(it.easyPaceSec) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val isRide = com.javon.healthdash.calc.RaceType.valueOf(plan.raceType).sport == Sport.Ride
    val fitness = run {
        val w = weekly.toDoubleOrNull(); val l = longest.toDoubleOrNull(); val p = parsePace(pace)
        if (w != null && l != null && p != null) com.javon.healthdash.calc.RunFitness(w, l, p, auto?.runs ?: 0, auto?.days ?: 30) else null
    }
    val preview = remember(fitness, recurring) { regeneratePlan(plan, today, state, recurring.map { it.toFixed() }, rules, fuel, fitness) }
    AppSheet("Rebuild plan from today", onDismiss) {
        Text("Keeps your race, goal, days and everything already done. Regenerates the rest with the latest rules:", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
        listOf(
            "A steady or hard run club counts as that week's tempo",
            "Easy and long runs at your current paces; workouts build toward goal pace by the peak",
            "Long runs start from your longest recent run",
        ).forEach { Text("• $it", style = Theme.type.bodyS, color = Theme.colors.textPrimary) }
        if (!isRide) {
            Text("Your running now — fix anything the watch missed", style = Theme.type.labelL, color = Theme.colors.textPrimary)
            AppTextField("Weekly mileage", weekly, { weekly = it }, suffix = "mi", keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
            AppTextField("Longest run, last 30 days", longest, { longest = it }, suffix = "mi", keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
            AppTextField("Typical easy pace", pace, { pace = it }, suffix = "/mi", placeholder = "12:00",
                error = if (pace.isNotBlank() && parsePace(pace) == null) "Use m:ss, e.g. 12:00" else null)
        }
        (preview as? PlanResult.Ok)?.let { ok ->
            ok.weeks.flatMap { it.sessions }.firstOrNull { it.kind == WorkoutKind.Long && !it.date.isBefore(today) }?.let { l ->
                Notice(NoticeType.Info, "Next long run · ${Fmt.dayShort(l.date.atStartOfDay(zone).toInstant().toEpochMilli())}", l.detail ?: "")
            }
        }
        (preview as? PlanResult.Invalid)?.let { Notice(NoticeType.Info, "Can't rebuild", it.reason) }
        error?.let { Notice(NoticeType.Info, "Couldn't rebuild", it) }
        AppButton("Rebuild", {
            scope.launch {
                when (val r = preview) {
                    is PlanResult.Ok -> { container.training.rebuildFrom(plan, today, r); onDismiss() }
                    is PlanResult.Invalid -> error = r.reason
                    null -> error = "Enter your current running above."
                }
            }
        }, Modifier.fillMaxWidth(), enabled = preview is PlanResult.Ok)
    }
}
