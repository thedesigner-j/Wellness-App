package com.javon.healthdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.Intensity
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.data.RecurringSession
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppSheet
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.FilterChip
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SegmentedControl
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.Toggle
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private val EFFORTS = listOf(Intensity.Easy to "Easy", Intensity.Moderate to "Steady", Intensity.Hard to "Hard")

fun describe(r: RecurringSession): String = listOfNotNull(
    "Every ${DayOfWeek.of(r.day).getDisplayName(TextStyle.FULL, Locale.US)}",
    r.distanceMi?.let { "${com.javon.healthdash.ui.Fmt.amount(it)} mi" },
    "${r.durationMin} min",
    EFFORTS.first { it.first.name == r.intensity }.second.lowercase() + " effort",
).joinToString(" · ")

/** Settings › Recurring sessions — e.g. a weekly run club at a set distance that plans are built around. */
@Composable
fun RecurringSessionsScreen(container: AppContainer, onBack: () -> Unit) {
    val list by container.prefs.recurring.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RecurringSession?>(null) }
    editing?.let { r ->
        RecurringSheet(
            initial = r,
            isNew = list.none { it.id == r.id },
            onDismiss = { editing = null },
            onSave = { saved -> container.prefs.setRecurring(list.filter { it.id != saved.id } + saved); editing = null },
            onDelete = { container.prefs.setRecurring(list.filter { it.id != r.id }); editing = null },
        )
    }
    val newSession = { RecurringSession(System.currentTimeMillis(), "Run club", DayOfWeek.WEDNESDAY.value, Sport.Run.name, null, 60, Intensity.Moderate.name) }
    ScreenFrame(
        topBar = { SmallTopBar("Recurring sessions", onBack, AppIcons.add) { editing = newSession() } },
        footer = { AppButton("Add a recurring session", { editing = newSession() }, Modifier.fillMaxWidth(), icon = AppIcons.add) },
    ) {
        Text(
            "Sessions you already do every week — like a run club with a set distance. New race plans keep them on their day, count them toward weekly volume, and plan the rest of the week around them.",
            style = Theme.type.bodyM, color = Theme.colors.textSecondary,
        )
        if (list.isEmpty()) {
            EmptyState("No recurring sessions", "Add your weekly run club so plans build around what you're already doing.")
        } else {
            RowGroup {
                list.sortedBy { it.day }.forEach { r -> ListRow(r.name, subtitle = describe(r), icon = AppIcons.calendar, onClick = { editing = r }) }
            }
        }
        Caption("A hard session counts as one of the week's hard days, and nothing hard is planned the day before or after it. In taper and race week it stays at an easy effort. Changes apply to new plans — rebuild an existing plan to include them.")
    }
}

@Composable
private fun RecurringSheet(initial: RecurringSession, isNew: Boolean, onDismiss: () -> Unit, onSave: (RecurringSession) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var day by remember { mutableIntStateOf(initial.day) }
    var sport by remember { mutableStateOf(initial.sport) }
    var distance by remember { mutableStateOf(initial.distanceMi?.let { com.javon.healthdash.ui.Fmt.amount(it) } ?: "") }
    var minutes by remember { mutableStateOf(initial.durationMin.toString()) }
    var effort by remember { mutableIntStateOf(EFFORTS.indexOfFirst { it.first.name == initial.intensity }.coerceAtLeast(0)) }
    var keep by remember { mutableStateOf(initial.keepInTaper) }
    val dist = distance.toDoubleOrNull()
    val min = minutes.toIntOrNull()
    AppSheet(if (isNew) "Add recurring session" else "Edit recurring session", onDismiss) {
        AppTextField("Name", name, { name = it }, placeholder = "Run club")
        Text("Day", style = Theme.type.labelM, color = Theme.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            DayOfWeek.entries.forEach { d -> FilterChip(d.getDisplayName(TextStyle.SHORT, Locale.US), day == d.value, { day = d.value }) }
        }
        SegmentedControl(listOf("Run", "Ride"), if (sport == Sport.Ride.name) 1 else 0, { sport = if (it == 1) Sport.Ride.name else Sport.Run.name })
        AppTextField("Distance", distance, { distance = it }, suffix = "mi", keyboardType = KeyboardType.Decimal, helper = "The set distance (optional)")
        AppTextField("Usual time", minutes, { minutes = it }, suffix = "min", keyboardType = KeyboardType.Number, helper = "Used for weekly volume and fueling",
            error = if (minutes.isNotBlank() && (min == null || min !in 10..400)) "Enter minutes between 10 and 400" else null)
        Text("Effort", style = Theme.type.labelM, color = Theme.colors.textSecondary)
        SegmentedControl(EFFORTS.map { it.second }, effort, { effort = it })
        RowGroup { ListRow("Keep during taper", subtitle = "At an easy effort; skipped within 2 days of the race", chevron = false, trailingContent = { Toggle(keep, { keep = it }) }) }
        AppButton("Save", {
            onSave(initial.copy(name = name.ifBlank { "Run club" }, day = day, sport = sport, distanceMi = dist, durationMin = min!!, intensity = EFFORTS[effort].first.name, keepInTaper = keep))
        }, Modifier.fillMaxWidth(), enabled = min != null && min in 10..400 && (distance.isBlank() || dist != null))
        if (!isNew) AppButton("Delete", onDelete, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
    }
}
