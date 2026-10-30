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
                // Every session that day: "4.5 + Str", so a second session is never hidden.
                val labels = d.sessions.sortedBy { if (it.sport == com.javon.healthdash.calc.Sport.Strength) 1 else 0 }.map { s ->
                    if (s.sport == com.javon.healthdash.calc.Sport.Strength) "Str" else s.distanceMi?.let { com.javon.healthdash.calc.TodaySummary.mi(it) } ?: "${s.durationMin}′"
                }
                Text(
                    labels.joinToString("+"),
                    style = Theme.type.caption, color = if (labels.size > 1) c.textAccent else c.textTertiary, textAlign = TextAlign.Center, maxLines = 2,
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

/** Goal likelihood: big %, outlook, projected vs goal, and how the number was built. */
@Composable
fun GoalOutlookCard(o: com.javon.healthdash.calc.GoalOutlook, hasGoal: Boolean, sport: com.javon.healthdash.calc.Sport) {
    val c = Theme.colors
    fun t(sec: Int) = com.javon.healthdash.calc.PaceMath.fmtTime(sec)
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (hasGoal) "Chance of hitting your goal" else "Projected finish", style = Theme.type.labelL, color = c.textPrimary)
            Tag("Estimate", TagType.Derived)
        }
        if (hasGoal) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
                Text("${o.percent}%", style = Theme.type.displayM, color = c.textPrimary)
                Tag(
                    o.outlook.label,
                    when (o.outlook) {
                        com.javon.healthdash.calc.Outlook.VeryLikely, com.javon.healthdash.calc.Outlook.Likely -> TagType.InRange
                        com.javon.healthdash.calc.Outlook.TossUp -> TagType.Neutral
                        com.javon.healthdash.calc.Outlook.Stretch -> TagType.High
                        com.javon.healthdash.calc.Outlook.Unlikely -> TagType.VeryHigh
                    },
                    Modifier.padding(bottom = Dimens.spacingSm),
                )
            }
            MetricTiles(listOf("Projected" to t(o.projectedSec), "Goal" to t(o.goalSec), "Fitness today" to t(o.currentSec)))
        } else {
            Text(t(o.projectedSec), style = Theme.type.displayM, color = c.textPrimary)
            MetricTiles(listOf("Fitness today" to t(o.currentSec), "Expected gain" to "${"%.1f".format(o.gainPct * 100)}%"), columns = 2)
        }
        val unit = if (sport == com.javon.healthdash.calc.Sport.Ride) "ride" else "run"
        Text(
            buildString {
                append("Fitness today: ")
                val parts = mutableListOf<String>()
                if (o.fromTypicalPace) parts += "your typical run pace (marathon pace ≈ easy pace − 1:15/mi) scaled to race distance = ${t(o.effortSec!!)}"
                else o.bestEffort?.let { e -> parts += "your fastest recent $unit (${"%.1f".format(e.distanceMi)} mi in ${t(e.durationSec)}) scaled to race distance = ${t(o.effortSec!!)}" }
                o.vo2Sec?.let { parts += "VO2 max prediction = ${t(it)}" }
                append(parts.joinToString(", averaged with "))
                append(". Expected gain by race day ${"%.1f".format(o.gainPct * 100)}% (0.6% per training week left × ${(o.consistency * 100).toInt()}% of recent sessions done).")
                if (hasGoal) append(" The % allows for normal race-day variation, which is wider the further out the race is.")
            },
            style = Theme.type.bodyS, color = c.textSecondary,
        )
        if (hasGoal) Caption(
            when (o.outlook) {
                com.javon.healthdash.calc.Outlook.VeryLikely, com.javon.healthdash.calc.Outlook.Likely -> "Keep the long runs and quality sessions coming — consistency is what moves this number."
                com.javon.healthdash.calc.Outlook.TossUp -> "Close call. Hitting every long run and quality session is what tips it."
                else -> "Your goal is ahead of your current trend. Keep training — or consider adjusting the goal in Race plans."
            },
        )
    }
}

/**
 * Today's training as a checklist: every session (runs, rides, strength, club), done or open, total time, and a
 * late-day warning when something is still open. [compact] drops the warning for the Today tab.
 */
@Composable
fun TodayTrainingCard(
    s: com.javon.healthdash.calc.TodaySummary,
    hour: Int,
    compact: Boolean = false,
    onItem: (com.javon.healthdash.calc.TodayItem) -> Unit = {},
    onMove: ((com.javon.healthdash.calc.TodayItem) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Theme.colors
    AppCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (compact) "Training today" else "Today's training", style = Theme.type.labelM, color = c.textSecondary)
                Text(s.headline, style = Theme.type.headingH2, color = c.textPrimary)
            }
            if (!s.rest) Column(horizontalAlignment = Alignment.End) {
                Text("≈ ${com.javon.healthdash.calc.TodaySummary.duration(if (s.allDone) s.totalMin else s.openMin)}", style = Theme.type.labelL, color = c.textPrimary)
                Text(if (s.allDone) "total" else "left today", style = Theme.type.caption, color = c.textTertiary)
            }
        }
        if (s.rest) Text("Nothing planned — recover well.", style = Theme.type.bodyM, color = c.textSecondary)
        s.items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clip(Shapes.md).background(if (item.done) c.bgSurfaceSunken else c.bgAccentSubtle).clickable { onItem(item) }
                    .padding(horizontal = Dimens.spacingMd, vertical = Dimens.spacingMd),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    if (item.done) Modifier.size(26.dp).clip(CircleShape).background(c.bgAccent) else Modifier.size(26.dp).border(2.dp, c.borderAccent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (item.done) Icon(AppIcons.check, "Done", tint = c.textOnAccent, modifier = Modifier.size(16.dp)) }
                Icon(
                    when (item.sport) { com.javon.healthdash.calc.Sport.Strength -> AppIcons.strength; com.javon.healthdash.calc.Sport.Ride -> AppIcons.bike; else -> AppIcons.run },
                    null, tint = if (item.done) c.iconSecondary else c.iconPrimary, modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(item.short, style = Theme.type.labelL, color = if (item.done) c.textSecondary else c.textPrimary)
                    Text(if (item.done) "Done" else "≈ ${com.javon.healthdash.calc.TodaySummary.duration(item.minutes)}", style = Theme.type.caption, color = c.textTertiary)
                }
                if (!item.done && onMove != null && !compact) Text("Move", style = Theme.type.labelM, color = c.textAccent, modifier = Modifier.clickable { onMove(item) }.padding(Dimens.spacingXs))
            }
        }
        if (!compact) s.lateWarning(hour)?.let { Notice(NoticeType.Gentle, "Still to do today", it) }
    }
}
