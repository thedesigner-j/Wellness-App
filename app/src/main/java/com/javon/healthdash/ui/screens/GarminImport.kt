package com.javon.healthdash.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.CsvActivity
import com.javon.healthdash.calc.DAY_MS
import com.javon.healthdash.calc.GarminCsv
import com.javon.healthdash.calc.METERS_PER_MI
import com.javon.healthdash.calc.MINUTE_MS
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.data.db.WorkoutEntity
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.Heading
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.Notice
import com.javon.healthdash.ui.components.NoticeType
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SegmentedControl
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Theme
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Health Connect only gets Garmin activities from the day sharing was turned on. This imports
 * older history from the CSV that Garmin Connect exports, so plans start from the real last 30 days.
 */
@Composable
fun GarminImportScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var units by remember { mutableIntStateOf(0) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var parsed by remember { mutableStateOf<List<CsvActivity>?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()

    fun parse(u: Uri) = scope.launch {
        error = null; result = null
        val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(u)?.bufferedReader()?.readText() }.getOrNull() }
        val acts = text?.let { GarminCsv.parse(it, zone, units == 1) }
        if (acts.isNullOrEmpty()) { parsed = null; error = "That file doesn't look like a Garmin Connect activities CSV." } else parsed = acts
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { uri = u; parse(u) } }

    ScreenFrame(
        topBar = { SmallTopBar("Import Garmin history", onBack) },
        footer = {
            val acts = parsed
            if (acts != null) {
                AppButton("Import ${acts.size} activities", {
                    scope.launch {
                        val existing = state.workouts.filter { it.source != "garmin-csv" }
                        val rows = acts.filter { a -> existing.none { kotlin.math.abs(it.startMs - a.startMs) < 3 * MINUTE_MS } }.map { a ->
                            WorkoutEntity(
                                id = "garmin-csv-${a.startMs}", startMs = a.startMs, endMs = a.startMs + a.durationSec * 1000L,
                                exerciseType = when (a.sport) {
                                    Sport.Run -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                                    Sport.Ride -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                                    Sport.Strength -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
                                    null -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
                                },
                                title = a.title, source = "garmin-csv",
                                avgHr = a.avgHr?.toDouble(), maxHr = a.maxHr?.toDouble(), distanceM = a.distanceMi?.times(METERS_PER_MI),
                            )
                        }
                        container.dao.upsertWorkouts(rows)
                        result = "Imported ${rows.size} activities (${acts.size - rows.size} were already synced)."
                        parsed = null
                    }
                }, Modifier.fillMaxWidth())
            }
            AppButton(if (uri == null) "Choose CSV file" else "Choose a different file", { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) },
                Modifier.fillMaxWidth(), style = if (parsed == null) ButtonStyle.Primary else ButtonStyle.Ghost, icon = AppIcons.document)
        },
    ) {
        Heading("Bring in your last 30 days", "Garmin only shares new activities with Health Connect, so your earlier runs need a one-time import.")
        RowGroup {
            ListRow("1. Open connect.garmin.com", subtitle = "In a browser (on the phone, use \"Desktop site\")", icon = AppIcons.link, chevron = false)
            ListRow("2. Activities → All Activities", subtitle = "Scroll so the last 30+ days are listed", icon = AppIcons.run, chevron = false)
            ListRow("3. Export CSV", subtitle = "Top right of the list · saves Activities.csv", icon = AppIcons.document, chevron = false)
            ListRow("4. Choose that file here", subtitle = "Runs already synced from Health Connect are skipped", icon = AppIcons.check, chevron = false)
        }
        Text("Distance units in your Garmin account", style = Theme.type.labelM, color = Theme.colors.textSecondary)
        SegmentedControl(listOf("Miles", "Kilometers"), units, { units = it; uri?.let { u -> parse(u) } })
        error?.let { Notice(NoticeType.Info, "Couldn't read the file", it) }
        result?.let { Notice(NoticeType.Info, "Done", "$it Your plan builder now uses this history.") }
        parsed?.let { acts ->
            val month = acts.filter { it.startMs >= state.nowMs - 30 * DAY_MS }
            val runs = month.filter { it.sport == Sport.Run && it.distanceMi != null }
            MetricTiles(
                listOf(
                    "Activities" to "${acts.size}",
                    "Runs · 30 days" to "${runs.size}",
                    "Miles · 30 days" to Fmt.one(runs.sumOf { it.distanceMi ?: 0.0 }),
                ),
            )
            RowGroup {
                acts.sortedByDescending { it.startMs }.take(8).forEach { a ->
                    ListRow(a.title, subtitle = "${Fmt.dayShort(a.startMs)} · ${a.type}" + (a.distanceMi?.let { " · ${Fmt.one(it)} mi" } ?: "") + " · ${a.durationSec / 60} min", icon = sportIcon(a.sport), chevron = false)
                }
            }
            Caption("Check the distances look right — if they're off by about 1.6×, switch the units above.")
        }
    }
}
