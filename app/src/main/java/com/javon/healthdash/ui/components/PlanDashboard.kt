package com.javon.healthdash.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.javon.healthdash.calc.DayMark
import com.javon.healthdash.calc.Phase
import com.javon.healthdash.calc.PlanStatus
import com.javon.healthdash.calc.Verdict
import com.javon.healthdash.calc.WeekDay
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme
import java.time.format.TextStyle
import java.util.Locale

@Composable
private fun phaseColor(p: Phase): Color = Theme.colors.let { c ->
    when (p) {
        Phase.Base -> c.textTertiary
        Phase.Build -> c.borderAccent
        Phase.Peak -> c.bgAccent
        Phase.Taper -> c.textAi
        else -> c.textSuccess
    }
}

@Composable
fun VerdictTag(v: Verdict) = Tag(
    v.label,
    when (v) {
        Verdict.OnTrack, Verdict.Finished -> TagType.InRange
        Verdict.SlightlyBehind -> TagType.High
        Verdict.OffTrack -> TagType.VeryHigh
        Verdict.EarlyDays -> TagType.Neutral
    },
)

/** Hero: race, countdown, week X of N, phase, a week-by-week track with "you are here", and the on-track verdict. */
@Composable
fun PlanHeroCard(p: PlanStatus, raceName: String, raceDate: String, onClick: () -> Unit) {
    val c = Theme.colors
    AppCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
                Text("$raceName · $raceDate", style = Theme.type.labelM, color = c.textSecondary)
                Text("Week ${p.weekIndex + 1} of ${p.totalWeeks}", style = Theme.type.headingH1, color = c.textPrimary)
                Text("${if (p.phase == Phase.Race) "Race" else p.phase.name} phase", style = Theme.type.labelL, color = phaseColor(p.phase))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${p.daysToRace}", style = Theme.type.metricL, color = c.textPrimary)
                Text(if (p.daysToRace == 1) "day to go" else "days to go", style = Theme.type.caption, color = c.textTertiary)
            }
        }
        WeekTrack(p)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            VerdictTag(p.verdict)
            Text("${(p.elapsed * 100).toInt()}% of the plan done", style = Theme.type.caption, color = c.textTertiary)
        }
        Text(p.reason, style = Theme.type.bodyS, color = c.textSecondary)
    }
}

/** One bar per plan week, colored by phase; past weeks solid, the current week tall with a marker. */
@Composable
fun WeekTrack(p: PlanStatus) {
    val c = Theme.colors
    val phases = (0 until p.totalWeeks).map { w -> p.weeks.firstOrNull { it.week == w }?.phase ?: Phase.Race }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
        Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            phases.forEachIndexed { i, ph ->
                val color = phaseColor(ph)
                Box(
                    Modifier.weight(1f).height(if (i == p.weekIndex) 18.dp else 8.dp).clip(Shapes.sm)
                        .background(if (i < p.weekIndex) color else if (i == p.weekIndex) c.bgAccent else color.copy(alpha = 0.25f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Start", style = Theme.type.caption, color = c.textTertiary)
            Text("Base · Build · Peak · Taper", style = Theme.type.caption, color = c.textTertiary)
            Text("Race", style = Theme.type.caption, color = c.textTertiary)
        }
    }
}

/** Mon–Sun strip for this week: done ✓, missed, today ring, upcoming hollow, rest dash. */
@Composable
fun WeekStrip(days: List<WeekDay>, onDay: (WeekDay) -> Unit) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { d ->
            Column(Modifier.weight(1f).clip(Shapes.md).clickable { onDay(d) }.padding(vertical = Dimens.spacingXs), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
                Text(d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.US), style = Theme.type.labelS, color = if (d.mark == DayMark.Today) c.textAccent else c.textTertiary)
                val base = Modifier.size(30.dp).clip(CircleShape)
                Box(
                    when (d.mark) {
                        DayMark.Done -> base.background(c.bgAccent)
                        DayMark.Missed -> base.background(c.statusVeryHigh.copy(alpha = 0.2f))
                        DayMark.Today -> base.border(2.dp, c.borderAccent, CircleShape)
                        DayMark.Upcoming -> base.border(1.dp, c.borderStrong, CircleShape)
                        DayMark.Rest -> base
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    when (d.mark) {
                        DayMark.Done -> Icon(AppIcons.check, "Done", tint = c.textOnAccent, modifier = Modifier.size(16.dp))
                        DayMark.Missed -> Text("✕", style = Theme.type.labelS, color = c.statusVeryHigh)
                        DayMark.Rest -> Text("–", style = Theme.type.labelS, color = c.textTertiary)
                        else -> Text("${d.date.dayOfMonth}", style = Theme.type.labelS, color = c.textPrimary)
                    }
                }
                Text(
                    d.sessions.firstOrNull { it.sport != com.javon.healthdash.calc.Sport.Strength }?.let { s -> s.distanceMi?.let { "${if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it)}" } ?: "${s.durationMin}′" }
                        ?: if (d.sessions.isNotEmpty()) "Str" else "",
                    style = Theme.type.caption, color = c.textTertiary, textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}

/** Thin labelled progress bar: done / planned. */
@Composable
fun ProgressLine(label: String, value: String, fraction: Double) {
    val c = Theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXxs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = Theme.type.labelM, color = c.textSecondary)
            Text(value, style = Theme.type.labelM, color = c.textPrimary)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(Shapes.sm).background(c.bgNeutral)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat()).height(8.dp).clip(Shapes.sm).background(c.bgAccent))
        }
    }
}
