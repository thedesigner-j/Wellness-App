package com.javon.healthdash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.javon.healthdash.calc.DAY_MS
import com.javon.healthdash.calc.GlucoseContext
import com.javon.healthdash.calc.HOUR_MS
import com.javon.healthdash.calc.Metric
import com.javon.healthdash.calc.SleepFilter
import com.javon.healthdash.calc.SleepQuality
import com.javon.healthdash.calc.SplitFilter
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.domain.DayContextModel
import com.javon.healthdash.domain.MetricDetailModel
import com.javon.healthdash.domain.TrendMetric
import com.javon.healthdash.domain.TrendsModel
import com.javon.healthdash.domain.buildDayContext
import com.javon.healthdash.domain.buildMetricDetail
import com.javon.healthdash.domain.buildTrends
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.BottomNav
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.FilterChip
import com.javon.healthdash.ui.components.GlucoseChart
import com.javon.healthdash.ui.components.InsightRow
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SegmentedControl
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.StatCard
import com.javon.healthdash.ui.components.Tab
import com.javon.healthdash.ui.components.TrendLineChart
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val PERIODS = listOf(7, 30, 90)

@Composable
private fun Loading() = CircularProgressIndicator(Modifier.size(24.dp), color = Theme.colors.textAccent)

@Composable
private fun CardRow(content: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
        content(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
fun TrendsScreen(state: DashboardState, onTab: (Tab) -> Unit, onBack: () -> Unit, onMetric: (TrendMetric, Int) -> Unit, onDay: (LocalDate) -> Unit) {
    val zone = ZoneId.systemDefault()
    var period by rememberSaveable { mutableIntStateOf(1) }
    var split by rememberSaveable { mutableIntStateOf(0) }
    var sleep by rememberSaveable { mutableIntStateOf(0) }
    val days = PERIODS[period]
    val model by produceState<TrendsModel?>(null, state, period, split, sleep) {
        value = withContext(Dispatchers.Default) { buildTrends(state, zone, days, SplitFilter.entries[split], SleepFilter.entries[sleep]) }
    }
    val v = state.targetValues
    ScreenFrame(topBar = { SmallTopBar("Trends", onBack) }, bottomBar = { BottomNav(Tab.More) { onTab(it) } }) {
        SegmentedControl(PERIODS.map { "${it}D" }, period, { period = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            listOf("All", "Everyday", "Training", "Overnight").forEachIndexed { i, l -> FilterChip(l, split == i, { split = i }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm), verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            listOf("Any sleep", "Good sleep", "Poor sleep").forEachIndexed { i, l -> FilterChip(l, sleep == i, { sleep = i }) }
        }
        val m = model ?: run { Loading(); return@ScreenFrame }
        val s = m.summary.valueOrNull()
        val cov = (m.summary as? Metric.NotEnoughData)?.coverage
        val nd = "Not enough data · ${Fmt.int((cov ?: 0.0) * 100)}% coverage"
        SectionHeader("Glucose · ${listOf("all", "everyday", "training", "overnight")[split]}")
        CardRow { mod ->
            StatCard("Tight range", AppIcons.trends, s?.let { Fmt.int(it.pctTight) }, "%", if (s != null) "Target ≥ ${v["tirGoal"]?.toInt() ?: 85}%" else nd, mod.clickable { onMetric(TrendMetric.TightRange, days) })
            StatCard("Mean", AppIcons.glucose, s?.let { "${it.mean.toInt()}" }, "mg/dL", if (s != null) "GMI ${Fmt.two(s.gmi)}% est." else nd, mod.clickable { onMetric(TrendMetric.Mean, days) })
        }
        CardRow { mod ->
            StatCard("Above 140", AppIcons.arrowUp, s?.let { Fmt.int(it.pctAboveTight) }, "%", if (s != null) "Above 180: ${Fmt.one(s.pctAboveVeryHigh)}%" else nd, mod.clickable { onMetric(TrendMetric.Above140, days) })
            StatCard("Below 70", AppIcons.remove, s?.let { Fmt.one(it.pctBelowLow) }, "%", if (s != null) "Target < ${v["lowGoal"]?.toInt() ?: 4}%" else nd, mod.clickable { onMetric(TrendMetric.Below70, days) })
        }
        CardRow { mod ->
            StatCard("Variability", AppIcons.trends, s?.let { Fmt.int(it.cv) }, "% CV", if (s != null) "SD ${s.sd.toInt()} mg/dL · target < ${v["cvGoal"]?.toInt() ?: 20}%" else nd, mod.clickable { onMetric(TrendMetric.Variability, days) })
            StatCard("Fasting", AppIcons.sleep, m.fastingAvg?.let { "${it.toInt()}" }, "mg/dL", if (m.fastingAvg != null) "${m.fastingDays} mornings · target < ${v["fastingGoal"]?.toInt() ?: 100}" else "No mornings with enough data", mod.clickable { onMetric(TrendMetric.Fasting, days) })
        }
        SectionHeader("Context")
        CardRow { mod ->
            StatCard("Sleep", AppIcons.sleep, m.sleepAvgMin?.let { hm(it.toInt()) }, "h avg", if (m.sleepAvgMin != null) "${m.nights} nights · good ≥ ${Fmt.amount(state.targets.goodSleepMin / 60.0)} h" else "No sleep data", mod)
            StatCard("HRV", AppIcons.heart, m.hrvAvg?.let { "${it.toInt()}" }, "ms", if (m.hrvAvg != null) "Morning average" else "No HRV data", mod)
        }
        CardRow { mod ->
            StatCard("Resting HR", AppIcons.heart, m.restingHrAvg?.let { "${it.toInt()}" }, "bpm", if (m.restingHrAvg != null) "Morning average" else "No data", mod)
            StatCard("Steps", AppIcons.walk, Fmt.int(m.stepsPerDay), "/day", "Goal ${Fmt.int(v["stepGoal"] ?: 10000.0)}", mod)
        }
        SectionHeader("Days")
        RowGroup {
            m.days.forEach { d ->
                val ms = d.date.atStartOfDay(zone).toInstant().toEpochMilli()
                ListRow(
                    Fmt.dayLabel(ms + 12 * HOUR_MS, state.nowMs),
                    subtitle = (d.sleepMin?.let { "Slept ${hoursMin(it)} · ${if (d.quality == SleepQuality.Good) "good" else "short"} night" } ?: "No sleep recorded"),
                    trailing = d.tir?.let { "${it.toInt()}% TIR" } ?: "—", icon = AppIcons.calendar, onClick = { onDay(d.date) },
                )
            }
        }
        Caption("Percentages are time in each range. Every number needs ${Fmt.int(state.targets.minCoverage * 100)}% CGM coverage of the filtered time. Training = workouts + ${state.targets.trainingAfterMin} min after; Overnight = Garmin sleep or ${hm(state.targets.overnightStartMin)}–${hm(state.targets.overnightEndMin)}.")
    }
}

private fun fmtMetric(m: TrendMetric, v: Double) = when (m) {
    TrendMetric.Mean, TrendMetric.Fasting -> "${v.toInt()} mg/dL"
    TrendMetric.Below70 -> "${Fmt.one(v)}%"
    else -> "${v.toInt()}%"
}

@Composable
fun MetricDetailScreen(state: DashboardState, metric: TrendMetric, initialDays: Int, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    var period by rememberSaveable { mutableIntStateOf(PERIODS.indexOf(initialDays).coerceAtLeast(0)) }
    val days = PERIODS[period]
    val model by produceState<MetricDetailModel?>(null, state, period) { value = withContext(Dispatchers.Default) { buildMetricDetail(state, zone, metric, days) } }
    val goal = metric.goal(state.targetValues)
    ScreenFrame(topBar = { SmallTopBar(metric.label, onBack) }) {
        SegmentedControl(PERIODS.map { "${it}D" }, period, { period = it })
        val m = model ?: run { Loading(); return@ScreenFrame }
        AppCard {
            Text("${days}-day ${metric.label.lowercase()}", style = Theme.type.labelM, color = Theme.colors.textSecondary)
            Text(m.period.valueOrNull()?.let { fmtMetric(metric, it) } ?: "Not enough data", style = Theme.type.metricL, color = Theme.colors.textPrimary)
            goal?.let { Caption("Target ${if (metric.goalAbove) "≥" else "<"} ${fmtMetric(metric, it)}") }
        }
        TrendLineChart(
            "Daily ${metric.label.lowercase()}", m.series, target = goal,
            targetLabel = goal?.let { "target ${fmtMetric(metric, it)}" } ?: "", goalAbove = metric.goalAbove,
            ticks = dateTicks(state.nowMs, days), seriesLabel = "Daily", format = { "${it.toInt()}" },
        )
        if (m.byContext.isNotEmpty()) {
            SectionHeader("By context")
            RowGroup {
                m.byContext.forEach { (ctx, value, _) ->
                    ListRow(
                        ctx.name,
                        subtitle = when (ctx) {
                            GlucoseContext.Everyday -> "Waking hours outside training"
                            GlucoseContext.Overnight -> "Garmin sleep window"
                            GlucoseContext.Training -> "Workouts + ${state.targets.trainingAfterMin} min after"
                        },
                        trailing = value.valueOrNull()?.let { fmtMetric(metric, it) } ?: "—", chevron = false,
                        icon = when (ctx) { GlucoseContext.Everyday -> AppIcons.home; GlucoseContext.Overnight -> AppIcons.sleep; GlucoseContext.Training -> AppIcons.run },
                    )
                }
            }
            Caption("Training rises are expected while fueling with carbs and are kept out of everyday numbers. \"—\" means not enough data.")
        }
        AppCard {
            Text("Good vs poor sleep nights", style = Theme.type.labelL, color = Theme.colors.textPrimary)
            MetricTiles(
                listOf(
                    "After good sleep · ${m.good.second} days" to (m.good.first.valueOrNull()?.let { fmtMetric(metric, it) } ?: "—"),
                    "After poor sleep · ${m.poor.second} days" to (m.poor.first.valueOrNull()?.let { fmtMetric(metric, it) } ?: "—"),
                ),
                columns = 2,
            )
        }
        val g = m.good.first.valueOrNull()
        val p = m.poor.first.valueOrNull()
        if (g != null && p != null) {
            val diff = g - p
            val better = if (metric.goalAbove) diff > 0 else diff < 0
            RowGroup {
                InsightRow(
                    "Days after good sleep ${if (better) "were better" else "were not better"}: ${fmtMetric(metric, g)} vs ${fmtMetric(metric, p)}.",
                    "${m.good.second + m.poor.second} days",
                )
            }
        }
    }
}

@Composable
fun DayContextScreen(state: DashboardState, date: LocalDate, onBack: () -> Unit, onMeal: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val model by produceState<DayContextModel?>(null, state, date) { value = withContext(Dispatchers.Default) { buildDayContext(state, zone, date) } }
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    ScreenFrame(topBar = { SmallTopBar(Fmt.dayLong(start), onBack) }) {
        val m = model ?: run { Loading(); return@ScreenFrame }
        val s = m.summary.valueOrNull()
        MetricTiles(
            listOf(
                "Tight range" to (s?.let { "${it.pctTight.toInt()}%" } ?: "—"),
                "Mean" to (s?.let { "${it.mean.toInt()}" } ?: "—"),
                "CV" to (s?.let { "${it.cv.toInt()}%" } ?: "—"),
            ),
        )
        if (s == null) Caption("Not enough glucose data for this day (${Fmt.int(((m.summary as Metric.NotEnoughData).coverage) * 100)}% coverage).")
        GlucoseChart(
            "Glucose · ${Fmt.dayShort(start)}", state.glucose, m.window,
            ticks = listOf("12a", "6a", "12p", "6p", "12a"),
            meals = state.meals.map { it.event.timeMs }.filter { it in m.window.startMs until m.window.endMs },
            workouts = state.workoutWindows, targets = state.targets,
        )
        AppCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                androidx.compose.material3.Icon(AppIcons.sleep, null, tint = Theme.colors.chartSplitOvernight, modifier = Modifier.size(18.dp))
                Text("Previous night", style = Theme.type.labelL, color = Theme.colors.textPrimary)
            }
            MetricTiles(
                listOf(
                    "Sleep" to (m.sleepMin?.let { hoursMin(it) } ?: "—"),
                    "Deep" to (m.deepMin?.let { hoursMin(it) } ?: "—"),
                    "Quality" to when (m.quality) { SleepQuality.Good -> "Good"; SleepQuality.Poor -> "Short"; null -> "—" },
                    "HRV" to (m.hrv?.let { "${it.toInt()} ms" } ?: "—"),
                    "HRV baseline" to (m.hrvBaseline?.let { "${it.toInt()} ms" } ?: "—"),
                    "Resting HR" to (m.restingHr?.let { "${it.toInt()}" } ?: "—"),
                ),
            )
            Caption("Fasting: ${m.fasting.valueOrNull()?.let { "${it.toInt()} mg/dL" } ?: "not enough data"}. Baseline = your average morning HRV over the previous 28 days.")
        }
        val dayWorkouts = state.workouts.filter { it.startMs in m.window.startMs until m.window.endMs }
        SectionHeader("Workouts")
        if (dayWorkouts.isEmpty()) Caption("No workouts this day.") else RowGroup {
            dayWorkouts.forEach { w -> ListRow(w.title ?: "Workout", subtitle = "${Fmt.time(w.startMs)} · ${(w.endMs - w.startMs) / 60_000} min", icon = AppIcons.run, chevron = false) }
        }
        val dayMeals = state.meals.filter { it.event.timeMs in m.window.startMs until m.window.endMs }.sortedBy { it.event.timeMs }
        SectionHeader("Meals")
        if (dayMeals.isEmpty()) Caption("No meals logged this day.") else RowGroup { dayMeals.forEach { MealRow(it, onMeal) } }
        m.hrvSplit?.let { (above, below) ->
            RowGroup {
                InsightRow(
                    "After mornings with HRV at or above your baseline, time in range averaged ${above.first.toInt()}% vs ${below.first.toInt()}% below it.",
                    "${above.second + below.second} days",
                )
            }
        }
    }
}
