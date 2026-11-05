package com.javon.healthdash.ui.screens

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.FitWorkout
import com.javon.healthdash.calc.GarminConnectJson
import com.javon.healthdash.calc.HeartRate
import com.javon.healthdash.calc.PaceMath
import com.javon.healthdash.calc.RaceType
import com.javon.healthdash.calc.RepeatGroup
import com.javon.healthdash.calc.RunStep
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.calc.TargetInputs
import com.javon.healthdash.calc.WatchTargets
import com.javon.healthdash.calc.WatchWorkout
import com.javon.healthdash.calc.WorkoutKind
import com.javon.healthdash.calc.localDateOf
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.AppSheet
import com.javon.healthdash.ui.components.ButtonSize
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.Stepper
import com.javon.healthdash.ui.components.Toggle
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Heart-rate profile: max HR, threshold HR, zones (with source and date), easy fitness marker and settings. */
@Composable
fun ZonesScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    val weather = rememberWeather(container)
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val heart = remember(state.workouts, data.planned, weather) { heartProfile(state, data.planned, zone, weather) }
    val trend = remember(state.workouts) { pace145Trend(state, zone) }
    ScreenFrame(topBar = { SmallTopBar("Heart rate & zones", onBack) }) {
        MetricTiles(
            listOf(
                "Max HR" to (heart.maxHr?.label ?: "—"),
                "Threshold" to (heart.lthr?.bpm?.toString() ?: "—"),
                "Easy ceiling" to (heart.easyCeiling?.let { "$it bpm" } ?: "—"),
            ),
        )
        Caption(
            buildString {
                append(heart.maxHr?.let { m -> "Max HR: highest reliable reading in 12 months (held ≥ 10 s, after the first 3 min, spikes removed)" + (m.date?.let { " · $it" } ?: "") + (if (m.floor) ". Shown as a floor (+) until a marked all-out effort (rate it 10/10)." else ".") }
                    ?: "No reliable max HR yet — the app will suggest a max effort in place of a quality session.")
                append(" ")
                append(heart.lthr?.let { l -> "Threshold HR from ${l.source} on ${l.date}${if (l.valid(localDateOf(state.nowMs, zone))) "" else " — older than 6 weeks, retest suggested"}." }
                    ?: "No threshold HR yet: it comes from a hard 30–40 min or 45–75 min effort, or the 30-min test.")
            },
        )
        heart.lthr?.let { l ->
            SectionHeader("Zones (% of threshold ${l.bpm})")
            RowGroup {
                HeartRate.zones(l.bpm).zip(listOf("Recovery", "Easy · aerobic", "Steady / marathon", "Threshold", "Above threshold")).forEach { (z, what) ->
                    ListRow(z.name, subtitle = what, trailing = when { z.low == null -> "< ${z.high!! + 1}"; z.high == null -> "${z.low}+"; else -> "${z.low}–${z.high}" }, chevron = false)
                }
            }
            Caption("Easy runs are judged by heart rate: stay at or under ${HeartRate.easyCeiling(l.bpm)} bpm.")
        }

        SectionHeader("30-minute threshold test")
        AppCard { Text(HeartRate.TEST_PROTOCOL, style = Theme.type.bodyS, color = Theme.colors.textPrimary); Caption("It replaces a quality session — never an extra hard day, never within 10 days of the race.") }

        SectionHeader("Easy fitness · pace at 145 bpm")
        if (trend.isEmpty()) Caption("Logged on easy runs averaging 140–150 bpm, normalised to 145. None yet.")
        else RowGroup { trend.takeLast(8).reversed().forEach { (d, p) -> ListRow(Fmt.dayShort(d.atStartOfDay(zone).toInstant().toEpochMilli()), trailing = "${PaceMath.fmt(p)}/mi", chevron = false) } }

        SectionHeader("Settings")
        RowGroup {
            ListRow("Usual run time", subtitle = "For heat-adjusted pace targets", chevron = false,
                trailingContent = { Stepper("%d:%02d".format(prefs.runTimeMin / 60, prefs.runTimeMin % 60), { container.prefs.setRunTimeMin(((prefs.runTimeMin - 30) + 1440) % 1440) }, { container.prefs.setRunTimeMin((prefs.runTimeMin + 30) % 1440) }) })
            ListRow("First marathon", subtitle = "Adds 1.5% to the projection", chevron = false, trailingContent = { Toggle(prefs.firstMarathon, { container.prefs.setFirstMarathon(it) }) })
        }
    }
}

/** Next 7 days of runs as watch workouts: pace targets, a dry-run Garmin JSON and a .FIT file to copy to the watch. */
@Composable
fun WatchWorkoutsScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = rememberTrainingData(container)
    val weather = rememberWeather(container)
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val today = localDateOf(state.nowMs, zone)
    var json by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    val plan = data.plans.firstOrNull { !LocalDate.ofEpochDay(it.raceDate).isBefore(today) }
    val bundle = remember(state.workouts, data.planned, weather, plan) { plan?.let { forecastFor(state, it, data.planned, zone, weather, prefs.firstMarathon) } }
    val f = bundle?.forecast
    val workouts = remember(data.planned, weather, bundle, prefs.runTimeMin) {
        data.planned.filter { e -> plan != null && e.planId == plan.id && e.sport == Sport.Run.name && e.status == "Planned" && LocalDate.ofEpochDay(e.date) in today..today.plusDays(6) && matchedWorkout(e, state, zone) == null }
            .mapNotNull { e ->
                val d = LocalDate.ofEpochDay(e.date)
                val t = TargetInputs(plan!!.goalMin?.times(60), f?.projectedSec, f?.lowSec, f?.highSec, bundle?.heart?.lthr?.thresholdPaceSec, forecastAt(weather, d, prefs.runTimeMin, zone))
                WatchTargets.build(e.id, d, WorkoutKind.valueOf(e.kind), e.distanceMi, e.week, t)?.let { it to t.forecastF }
            }
    }
    json?.let { j -> AppSheet("Dry run · Garmin JSON", { json = null }) { Text(j, style = Theme.type.caption.copy(fontFamily = FontFamily.Monospace), color = Theme.colors.textPrimary) } }
    ScreenFrame(topBar = { SmallTopBar("Watch workouts", onBack) }) {
        Text("Garmin's personal API is read-only, so workouts can't be sent to the watch over the air. Save a .FIT file, then copy it to the watch's GARMIN/NewFiles folder over USB — it appears under Training › Workouts with pace targets.", style = Theme.type.bodyS, color = Theme.colors.textSecondary)
        saved?.let { Caption("Saved to Downloads/Wellness: $it") }
        if (plan?.goalMin == null) Caption("Pace targets come from your goal time — set one on the race plan.")
        if (workouts.isEmpty()) EmptyState("No runs in the next 7 days", "Planned runs appear here a week ahead.")
        workouts.forEach { (w, temp) ->
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${Fmt.dayShort(w.date.atStartOfDay(zone).toInstant().toEpochMilli())} · ${w.name}", style = Theme.type.labelL, color = Theme.colors.textPrimary)
                    temp?.let { Text("${it.toInt()}°F", style = Theme.type.labelM, color = Theme.colors.textSecondary) }
                }
                stepsText(w).forEach { Text(it, style = Theme.type.bodyS, color = Theme.colors.textSecondary) }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                    AppButton("Save .FIT", { scope.launch { saved = saveFit(context, w) } }, size = ButtonSize.Small)
                    AppButton("Dry run JSON", { json = GarminConnectJson.pretty(w).also { android.util.Log.i("WatchDryRun", it) } }, style = ButtonStyle.Ghost, size = ButtonSize.Small)
                }
            }
        }
        if (workouts.isNotEmpty()) AppButton("Save all ${workouts.size} as .FIT", { scope.launch { saved = workouts.map { saveFit(context, it.first) }.joinToString() } }, Modifier.fillMaxWidth(), style = ButtonStyle.Secondary)
        Caption("Targets: easy and long runs at goal marathon pace + 75–105 s/mi; marathon-pace finishes from week 7; slowed 1% per 5°F above 60°F using the forecast at your usual run time (Settings › Heart rate & zones).")
    }
}

private fun stepsText(w: WatchWorkout): List<String> {
    fun one(s: RunStep) = buildString {
        append(s.name)
        s.meters?.let { append(" · ${"%.2f".format(it / com.javon.healthdash.calc.METERS_PER_MI)} mi") }
        s.seconds?.let { append(" · ${it / 60} min") }
        append(s.pace?.let { " @ ${PaceMath.fmt(it.fast)}–${PaceMath.fmt(it.slow)}/mi" } ?: " · no target")
    }
    return w.steps.map { s -> when (s) { is RunStep -> one(s); is RepeatGroup -> "${s.times} × (" + s.steps.joinToString(", ") { one(it) } + ")" } }
}

/** Writes the .FIT to Downloads/Wellness and returns the file name. */
private suspend fun saveFit(context: Context, w: WatchWorkout): String = withContext(Dispatchers.IO) {
    val name = "${w.date}-${w.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}.fit"
    val bytes = FitWorkout.encode(w, System.currentTimeMillis())
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Wellness")
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create $name")
    context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
    name
}
