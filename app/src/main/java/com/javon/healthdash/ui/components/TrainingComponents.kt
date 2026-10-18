package com.javon.healthdash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.javon.healthdash.calc.Phase
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

enum class DayState { Rest, Planned, Completed, Missed }

/** Figma: Calendar Day + month grid. Hollow = planned, orange = completed, red = missed. */
@Composable
fun CalendarMonth(
    month: YearMonth,
    today: LocalDate,
    stateOf: (LocalDate) -> DayState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val c = Theme.colors
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${month.month.getDisplayName(TextStyle.FULL, Locale.US)} ${month.year}", style = Theme.type.labelL, color = c.textPrimary)
            Row {
                AppIconButton(AppIcons.chevronRight, "Previous month", onPrev, size = 36.dp, iconSize = 20.dp, modifier = Modifier.rotate(180f))
                AppIconButton(AppIcons.chevronRight, "Next month", onNext, size = 36.dp, iconSize = 20.dp)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { Text(it.getDisplayName(TextStyle.NARROW, Locale.US), style = Theme.type.labelS, color = c.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = (0 until lead).map { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (d != null) CalendarDay(d, d == today, stateOf(d)) { onDay(d) } }
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingLg)) {
            LegendDot("Done", c.chartCompleted); LegendDot("Planned", c.chartPlanned); LegendDot("Missed", c.statusLow)
        }
    }
}

@Composable
private fun CalendarDay(date: LocalDate, isToday: Boolean, state: DayState, onClick: () -> Unit) {
    val c = Theme.colors
    Column(
        Modifier.size(44.dp, 52.dp).clip(Shapes.md)
            .then(if (isToday) Modifier.background(c.bgAccentSubtle).border(1.dp, c.borderAccent, Shapes.md) else Modifier)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs, Alignment.CenterVertically),
    ) {
        Text("${date.dayOfMonth}", style = Theme.type.labelL, color = if (state == DayState.Missed) c.textTertiary else c.textPrimary)
        Box(
            Modifier.size(8.dp).clip(CircleShape).then(
                when (state) {
                    DayState.Completed -> Modifier.background(c.chartCompleted)
                    DayState.Missed -> Modifier.background(c.statusLow)
                    DayState.Planned -> Modifier.border(1.5.dp, c.chartPlanned, CircleShape)
                    DayState.Rest -> Modifier
                },
            ),
        )
    }
}

/** Figma: Bar Chart — planned (dashed outline) vs completed (filled) minutes per week. */
@Composable
fun WeeklyBars(title: String, planned: List<Int?>, done: List<Int?>, labels: List<String>, modifier: Modifier = Modifier, legendDone: String = "Done") {
    val c = Theme.colors
    AppCard(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Theme.type.labelL, color = c.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) { LegendDot(legendDone, c.chartCompleted); LegendDot("Planned", c.chartPlanned) }
        }
        val max = ((planned.filterNotNull() + done.filterNotNull()).maxOrNull() ?: 60).coerceAtLeast(60).toFloat()
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val n = labels.size.coerceAtLeast(1)
            val bw = size.width / n
            val r = CornerRadius(4.dp.toPx())
            for (i in 0 until n) {
                val x = i * bw + bw * 0.22f
                val w = bw * 0.56f
                planned.getOrNull(i)?.let { p ->
                    val h = p / max * size.height
                    drawRoundRect(c.chartPlanned, Offset(x, size.height - h), Size(w, h), r, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                }
                done.getOrNull(i)?.takeIf { it > 0 }?.let { d ->
                    val h = d / max * size.height
                    drawRoundRect(c.chartCompleted, Offset(x, size.height - h), Size(w, h), r)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) { labels.forEach { Text(it, style = Theme.type.caption, color = c.chartAxisLabel, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) } }
    }
}

enum class WorkoutCardStatus { Planned, Completed, Missed, Swapped }

/** Figma: Workout Card. */
@Composable
fun WorkoutCard(
    title: String,
    detail: String,
    fueling: String?,
    status: WorkoutCardStatus,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = Theme.colors
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().clip(Shapes.xl).background(c.bgSurface)
            .then(if (status == WorkoutCardStatus.Planned) Modifier.border(1.dp, c.borderSubtle, Shapes.xl) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Dimens.spacingLg),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(Shapes.md).background(if (status == WorkoutCardStatus.Completed) c.bgAccent else c.bgNeutral), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (status == WorkoutCardStatus.Completed) c.textOnAccent else c.iconPrimary, modifier = Modifier.size(20.dp))
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
                Text(title, style = Theme.type.labelL, color = c.textPrimary)
                Text(detail, style = Theme.type.bodyS, color = c.textSecondary)
            }
            when (status) {
                WorkoutCardStatus.Completed -> Tag("Done", TagType.InRange)
                WorkoutCardStatus.Missed -> Tag("Missed", TagType.Low)
                WorkoutCardStatus.Swapped -> Tag("Adjusted", TagType.Brand)
                WorkoutCardStatus.Planned -> Tag("Planned", TagType.Neutral)
            }
        }
        if (!fueling.isNullOrBlank()) {
            Row(
                Modifier.fillMaxWidth().clip(Shapes.md).background(c.bgSurfaceSunken).padding(horizontal = Dimens.spacingMd, vertical = Dimens.spacingSm),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.bolt, null, tint = c.chartFuelMark, modifier = Modifier.size(16.dp))
                Text(fueling, style = Theme.type.bodyS, color = c.textPrimary)
            }
        }
    }
}

/** Phase strip for plan review (Base / Build / Peak / Taper). */
@Composable
fun PhaseBar(phases: List<Phase>) {
    val c = Theme.colors
    val groups = phases.fold(mutableListOf<Pair<Phase, Int>>()) { acc, p -> if (acc.lastOrNull()?.first == p) acc[acc.lastIndex] = p to acc.last().second + 1 else acc += p to 1; acc }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.forEach { (p, n) ->
            val (bg, fg) = when (p) {
                Phase.Base -> c.bgNeutral to c.textPrimary
                Phase.Build -> c.bgAccentSubtle to c.textPrimary
                Phase.Peak -> c.bgAccent to c.textOnAccent
                Phase.Taper -> c.bgAi to c.textPrimary
                else -> c.bgSuccessSubtle to c.textPrimary
            }
            Column(
                Modifier.weight(n.toFloat()).clip(Shapes.sm).background(bg).padding(vertical = Dimens.spacingSm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (p == Phase.Race) "Race" else p.name, style = Theme.type.labelS, color = fg, maxLines = 1)
                Text("$n wk", style = Theme.type.caption, color = fg)
            }
        }
    }
}
