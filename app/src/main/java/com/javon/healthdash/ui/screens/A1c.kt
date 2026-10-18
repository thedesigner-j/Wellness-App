package com.javon.healthdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.Metric
import com.javon.healthdash.data.db.LabEntity
import com.javon.healthdash.domain.A1cModel
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.domain.buildA1c
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.AppSheet
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.BottomNav
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.LargeTopBar
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SegmentedControl
import com.javon.healthdash.ui.components.Tab
import com.javon.healthdash.ui.components.Tag
import com.javon.healthdash.ui.components.TagType
import com.javon.healthdash.ui.components.TrendLineChart
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Month/day ticks across the last [days] days. */
fun dateTicks(nowMs: Long, days: Int, count: Int = 4): List<String> {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)
    val zone = ZoneId.systemDefault()
    val end = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (count - 1 downTo 0).map { i -> end.minusDays((days - 1L) * i / (count - 1)).format(fmt) }
}

@Composable
fun A1cScreen(container: AppContainer, state: DashboardState, onTab: (Tab) -> Unit, onFastingDetail: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val model by produceState<A1cModel?>(null, state) { value = withContext(Dispatchers.Default) { buildA1c(state, zone) } }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LabEntity?>(null) }
    val scope = rememberCoroutineScope()
    val goal = state.targetValues["a1cGoal"] ?: 5.7
    val c = Theme.colors

    if (adding) AddLabSheet(onDismiss = { adding = false }) { lab -> scope.launch { container.dao.insertLab(lab); adding = false } }
    deleting?.let { lab ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this lab result?") },
            text = { Text("${labName(lab.kind)} ${labValue(lab)} on ${Fmt.dayShort(lab.timeMs)}") },
            confirmButton = { TextButton({ scope.launch { container.dao.deleteLab(lab.id); deleting = null } }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }

    ScreenFrame(topBar = { LargeTopBar("A1c Progress", "Goal · below ${Fmt.amount(goal)}%") }, bottomBar = { BottomNav(Tab.A1c) { onTab(it) } }) {
        val m = model
        if (m == null) {
            CircularProgressIndicator(Modifier.size(24.dp), color = c.textAccent)
            return@ScreenFrame
        }
        val g90 = m.gmi90.valueOrNull()
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("90-day GMI · estimated A1c", style = Theme.type.labelM, color = c.textSecondary)
                m.change?.let { ch -> Tag("${if (ch <= 0) "−" else "+"}${Fmt.two(abs(ch))} since ${Fmt.dayShort(m.changeSince!!).substringAfter(' ')}", if (ch <= 0) TagType.InRange else TagType.High) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs), verticalAlignment = Alignment.Bottom) {
                Text(g90?.let { Fmt.two(it.gmi) } ?: "—", style = Theme.type.displayGlucose, color = c.textPrimary)
                Text("%", style = Theme.type.headingH2, color = c.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(
                if (g90 != null) {
                    val d = g90.gmi - goal
                    "${if (d <= 0) "${Fmt.two(-d)} below" else "${Fmt.two(d)} above"} the ${Fmt.amount(goal)}% goal. Mean glucose ${g90.mean.toInt()} mg/dL · coverage ${Fmt.int((m.gmi90 as Metric.Value).coverage * 100)}%."
                } else "Not enough data for 90 days yet (${Fmt.int((m.gmi90 as Metric.NotEnoughData).coverage * 100)}% coverage). Shorter periods are below.",
                style = Theme.type.bodyS, color = c.textTertiary,
            )
            MetricTiles(
                listOf(
                    "14-day GMI" to (m.gmi14.valueOrNull()?.let { Fmt.two(it.gmi) + "%" } ?: "—"),
                    "30-day GMI" to (m.gmi30.valueOrNull()?.let { Fmt.two(it.gmi) + "%" } ?: "—"),
                    "90-day GMI" to (g90?.let { Fmt.two(it.gmi) + "%" } ?: "—"),
                ),
            )
        }

        TrendLineChart(
            "GMI · 90 days", m.rolling, target = goal, targetLabel = "${Fmt.amount(goal)}% target", goalAbove = false,
            ticks = dateTicks(state.nowMs, 90), seriesLabel = "14-day GMI",
            markers = m.labsA1c.map { it.timeMs to it.value }, markerLabel = "Lab A1c", format = { Fmt.two(it) },
        )

        val latest = m.latestLab
        AppCard {
            Text("Lab A1c vs GMI", style = Theme.type.labelL, color = c.textPrimary)
            when {
                latest == null -> Text("Add a lab A1c to compare it with your CGM estimate.", style = Theme.type.bodyM, color = c.textSecondary)
                m.comparison is Metric.Value -> {
                    val cmp = (m.comparison as Metric.Value).value
                    MetricTiles(
                        listOf(
                            "Lab A1c · ${Fmt.dayShort(latest.timeMs).substringAfter(' ')}" to "${Fmt.one(cmp.lab)}%",
                            "GMI · 90 days before" to "${Fmt.two(cmp.gmi)}%",
                            "Difference" to Fmt.two(abs(cmp.diff)),
                        ),
                    )
                    Text(
                        if (cmp.differsNotably) "Lab and GMI differ by more than 0.5. This can happen for reasons that have nothing to do with your effort, such as red blood cell lifespan or sensor accuracy — worth raising with your doctor."
                        else "Within 0.5 — your lab result and CGM estimate agree.",
                        style = Theme.type.bodyS, color = c.textSecondary,
                    )
                }
                else -> Text("Not enough CGM data in the 90 days before the ${Fmt.dayShort(latest.timeMs)} lab to compare.", style = Theme.type.bodyM, color = c.textSecondary)
            }
        }

        val fastingGoal = state.targetValues["fastingGoal"] ?: 100.0
        TrendLineChart(
            "Fasting glucose · 90 days", m.fasting, target = fastingGoal, targetLabel = "${fastingGoal.toInt()} mg/dL", goalAbove = false,
            ticks = dateTicks(state.nowMs, 90), seriesLabel = "Fasting",
            format = { "${it.toInt()}" },
        )
        Text(
            m.fastingAvg14?.let { "14-day fasting average ${it.toInt()} mg/dL from ${m.fastingDays14} mornings (${hm(state.targets.fastingStartMin)}–${hm(state.targets.fastingEndMin)}, before the first meal)." }
                ?: "No mornings with enough data for a fasting value in the last 14 days.",
            style = Theme.type.bodyS, color = c.textTertiary,
        )

        SectionHeader("Lab results", "Add") { adding = true }
        if (state.labs.isEmpty()) {
            EmptyState("No lab results yet", "Add your A1c and fasting glucose from lab reports. You'll get a reminder every 3 months.")
        } else {
            RowGroup {
                state.labs.forEach { lab ->
                    ListRow(labName(lab.kind), subtitle = Fmt.dayShort(lab.timeMs) + (lab.lab?.let { " · $it" } ?: ""), trailing = labValue(lab), icon = AppIcons.lab, chevron = false, onClick = { deleting = lab })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            Icon(AppIcons.info, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
            Caption("Personal tool, not medical advice. Discuss these results with your doctor.")
        }
    }
}

fun hm(minutes: Int) = "%d:%02d".format(minutes / 60, minutes % 60)
fun hoursMin(minutes: Int) = "${minutes / 60}h ${"%02d".format(minutes % 60)}m"

fun labName(kind: String) = if (kind == LabEntity.A1C) "A1c" else "Fasting glucose"
fun labValue(lab: LabEntity) = if (lab.kind == LabEntity.A1C) "${Fmt.one(lab.value)}%" else "${lab.value.toInt()} mg/dL"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLabSheet(onDismiss: () -> Unit, onSave: (LabEntity) -> Unit) {
    var kind by remember { mutableIntStateOf(0) }
    var value by remember { mutableStateOf("") }
    var lab by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val parsed = value.toDoubleOrNull()
    val error = when {
        value.isBlank() || parsed == null -> null
        kind == 0 && parsed !in 3.0..15.0 -> "A1c is usually between 3 and 15%"
        kind == 1 && parsed !in 40.0..400.0 -> "Enter mg/dL between 40 and 400"
        else -> null
    }
    if (picking) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton({ ds.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; picking = false }) { Text("Set") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(ds) }
    }
    AppSheet("Add lab result", onDismiss) {
        SegmentedControl(listOf("A1c", "Fasting glucose"), kind, { kind = it; value = "" })
        AppTextField("Result", value, { value = it }, suffix = if (kind == 0) "%" else "mg/dL", keyboardType = KeyboardType.Decimal, error = error)
        RowGroup { ListRow("Test date", trailing = Fmt.dayShort(date.atStartOfDay(zone).toInstant().toEpochMilli()), icon = AppIcons.calendar, onClick = { picking = true }) }
        AppTextField("Lab (optional)", lab, { lab = it }, placeholder = "e.g. Quest Diagnostics")
        Caption("Next reminder in 3 months: ${Fmt.dayShort(date.plusMonths(3).atStartOfDay(zone).toInstant().toEpochMilli())}")
        AppButton(
            "Save result",
            { onSave(LabEntity(timeMs = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), kind = if (kind == 0) LabEntity.A1C else LabEntity.FASTING, value = parsed!!, lab = lab.ifBlank { null })) },
            Modifier.fillMaxWidth(), enabled = parsed != null && error == null,
        )
    }
}
