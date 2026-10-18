package com.javon.healthdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.javon.healthdash.calc.GlucoseStatus
import com.javon.healthdash.calc.HOUR_MS
import com.javon.healthdash.calc.MINUTE_MS
import com.javon.healthdash.calc.Metric
import com.javon.healthdash.calc.TimeWindow
import com.javon.healthdash.calc.statusOf
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.AppIconButton
import com.javon.healthdash.ui.components.BottomNav
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.GlucoseChart
import com.javon.healthdash.ui.components.LargeTopBar
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.Notice
import com.javon.healthdash.ui.components.NoticeType
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.StatCard
import com.javon.healthdash.ui.components.Tab
import com.javon.healthdash.ui.components.Tag
import com.javon.healthdash.ui.components.TagType
import com.javon.healthdash.ui.components.TrendArrow
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme

@Composable
fun TodayScreen(
    state: DashboardState,
    onTab: (Tab) -> Unit,
    onSync: () -> Unit,
    onLogMeal: () -> Unit,
    onMeal: (Long) -> Unit,
    onSources: () -> Unit,
) {
    val now = state.nowMs
    ScreenFrame(
        topBar = {
            LargeTopBar("Today", Fmt.dayLong(now)) {
                AppIconButton(AppIcons.sync, "Sync now", onSync, size = 36.dp, iconSize = 20.dp)
                AppIconButton(AppIcons.camera, "Log meal", onLogMeal, size = 36.dp, iconSize = 20.dp)
            }
        },
        bottomBar = { BottomNav(Tab.Today) { onTab(it) } },
    ) {
        HeroGlucose(state)

        val today = state.today
        val todaySummary = today.valueOrNull()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
            StatCard(
                "Time in range", AppIcons.trends,
                value = todaySummary?.let { Fmt.int(it.pctTight) }, unit = "% today",
                footnote = if (todaySummary != null) "Target ≥ ${state.targetValues["tirGoal"]?.toInt() ?: 85}% · ${state.targets.tightLow.toInt()}–${state.targets.tightHigh.toInt()}"
                else "Not enough data · ${Fmt.int((today as Metric.NotEnoughData).coverage * 100)}% coverage",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatCard(
                "Steps", AppIcons.walk, value = Fmt.int(state.stepsToday), unit = "",
                footnote = "Goal ${Fmt.int(state.targetValues["stepGoal"] ?: 10000.0)}",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
            StatCard(
                "Weight", AppIcons.strength, value = state.weightAvg7?.let { Fmt.one(it) }, unit = "lb",
                footnote = if (state.weightAvg7 == null) "No weigh-ins this week" else "7-day avg" + (state.weightDelta7?.let { " · ${if (it >= 0) "+" else "−"}${Fmt.one(kotlin.math.abs(it))} lb" } ?: ""),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatCard(
                "Body fat", AppIcons.strength, value = state.bodyFatAvg7?.let { Fmt.one(it) }, unit = "%",
                footnote = if (state.bodyFatAvg7 == null) "No readings this week" else "7-day avg · ${Fmt.app(state.bodyFatSource ?: "")}",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        state.unlogged?.let { rise ->
            Notice(
                NoticeType.Prompt,
                "Did you eat around ${Fmt.time(rise.riseStartMs)}?",
                "Glucose rose ${rise.rise.toInt()} mg/dL with no meal logged within 30 min.",
                action = "Log meal", onAction = onLogMeal,
            )
        }

        val latestReading = state.latest
        if (latestReading == null || now - latestReading.timeMs > 2 * HOUR_MS) {
            Notice(NoticeType.Info, "Glucose data is delayed", "Some CGM apps sync to Health Connect about 3 hours late. Meal responses recompute automatically when data arrives.", action = "Data sources", onAction = onSources)
        }

        val window = TimeWindow(now - 24 * HOUR_MS, now)
        val coverage = com.javon.healthdash.calc.GlucoseStats.coverage(state.glucose, window)
        if (coverage < 0.05) {
            EmptyState("Not enough data yet", "Glucose coverage for the last 24 hours is ${Fmt.int(coverage * 100)}%. The chart appears once readings arrive.")
        } else {
            GlucoseChart(
                "Last 24 hours", state.glucose, window,
                ticks = (0..4).map { i -> if (i == 4) "Now" else Fmt.hourTick(window.startMs + i * 6 * HOUR_MS) },
                meals = state.meals.map { it.event.timeMs },
                workouts = state.workoutWindows,
                targets = state.targets,
            )
        }

        val todayMeals = state.meals.filter { it.event.timeMs >= now - 24 * HOUR_MS }
        if (todayMeals.isNotEmpty()) {
            SectionHeader("Recent meals")
            RowGroup {
                todayMeals.take(4).forEach { m ->
                    ListRow(
                        m.title, subtitle = "${Fmt.time(m.event.timeMs)} · ${Fmt.int(m.nutrients.carbsG)} g carbs" + if (m.walk.walked) " · walked" else "",
                        trailing = m.response?.let { Fmt.signed(it.peakRise) } ?: "—", icon = AppIcons.meal, onClick = { onMeal(m.id) },
                    )
                }
            }
        }

        SectionHeader("Recent workout")
        val last = state.workouts.maxByOrNull { it.startMs }
        if (last == null) {
            AppCard { Text("No workouts in Health Connect yet. Training plans arrive in Phase 3.", style = Theme.type.bodyM, color = Theme.colors.textSecondary) }
        } else {
            RowGroup {
                ListRow(
                    last.title ?: "Workout", icon = AppIcons.run, chevron = false,
                    subtitle = "${Fmt.dayLabel(last.startMs, now)} · ${Fmt.time(last.startMs)} · ${(last.endMs - last.startMs) / MINUTE_MS} min",
                )
            }
        }
    }
}

@Composable
private fun HeroGlucose(state: DashboardState) {
    val c = Theme.colors
    val p = state.latest
    val fresh = p != null && state.nowMs - p.timeMs <= 15 * MINUTE_MS
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.glucose, null, tint = c.iconAccent, modifier = Modifier.size(16.dp))
                Text(
                    if (p == null) "Glucose · no recent reading" else "Glucose · ${Fmt.ago(p.timeMs, state.nowMs)}",
                    style = Theme.type.labelM, color = c.textSecondary,
                )
            }
            if (p != null && fresh) {
                when (statusOf(p.mgdl, state.targets)) {
                    GlucoseStatus.InRange -> Tag("In range", TagType.InRange)
                    GlucoseStatus.High -> Tag("Above ${state.targets.tightHigh.toInt()}", TagType.High)
                    GlucoseStatus.VeryHigh -> Tag("Above ${state.targets.veryHigh.toInt()}", TagType.VeryHigh)
                    GlucoseStatus.Low -> Tag("Below ${state.targets.tightLow.toInt()}", TagType.Low)
                }
            } else {
                Tag("Delayed", TagType.Neutral)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalAlignment = Alignment.Bottom) {
            Text(if (p != null && fresh) "${p.mgdl.toInt()}" else "—", style = Theme.type.displayGlucose, color = c.textPrimary)
            Text("mg/dL", style = Theme.type.bodyL, color = c.textSecondary, modifier = Modifier.padding(bottom = 10.dp))
            if (fresh) state.trend?.let { TrendArrow(it, Modifier.padding(bottom = 12.dp).size(28.dp)) }
        }
        val gmi = state.gmi14.valueOrNull()
        Text(
            if (gmi != null) "14-day GMI ${Fmt.two(gmi.gmi)}% (estimated A1c) · mean ${gmi.mean.toInt()} mg/dL"
            else "14-day GMI: not enough data (${Fmt.int((state.gmi14 as Metric.NotEnoughData).coverage * 100)}% coverage)",
            style = Theme.type.bodyS, color = c.textTertiary,
        )
    }
}
