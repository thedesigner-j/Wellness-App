package com.javon.healthdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.PaceMath
import com.javon.healthdash.calc.RaceDay
import com.javon.healthdash.calc.RaceType
import com.javon.healthdash.calc.SplitStrategy
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.data.TargetsRepository
import com.javon.healthdash.data.inputs
import com.javon.healthdash.data.label
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.Heading
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SegmentedControl
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Race-day plan: pacing splits, fueling schedule, carb loading and a race-week checklist. */
@Composable
fun RaceDayScreen(container: AppContainer, state: DashboardState, planId: Long, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    val plan = data.plans.firstOrNull { it.id == planId }
    var strategy by remember { mutableIntStateOf(0) }
    var goalH by remember { mutableStateOf("") }
    var goalM by remember { mutableStateOf("") }
    var weightLb by remember { mutableStateOf("") }
    ScreenFrame(topBar = { SmallTopBar("Race-day plan", onBack) }) {
        if (plan == null) { EmptyState("No plan", "Create a race plan first."); return@ScreenFrame }
        val type = RaceType.valueOf(plan.raceType)
        val sport = type.sport
        val raceMi = plan.inputs().raceMi
        val race = LocalDate.ofEpochDay(plan.raceDate)
        val typed = goalH.toIntOrNull()?.let { h -> h * 60 + (goalM.toIntOrNull() ?: 0) }?.takeIf { it > 0 }
        val goalMin = plan.goalMin ?: typed
        Heading(plan.label(), "${Fmt.dayLong(race.atStartOfDay(zone).toInstant().toEpochMilli())} · ${Fmt.one(raceMi)} mi")
        if (plan.goalMin == null) {
            Text("This plan has no goal time. Enter the time you're aiming for to see splits and fueling.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
                AppTextField("Hours", goalH, { goalH = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), suffix = "h", keyboardType = KeyboardType.Number)
                AppTextField("Minutes", goalM, { goalM = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), suffix = "min", keyboardType = KeyboardType.Number)
            }
        }
        if (goalMin == null) return@ScreenFrame
        val goalSec = goalMin * 60
        val stateKg = state.weightAvg7?.let { it / 2.20462 }
        val kg = stateKg ?: weightLb.toDoubleOrNull()?.takeIf { it in 80.0..500.0 }?.let { it / 2.20462 }
        val p = RaceDay.plan(raceMi, goalSec, SplitStrategy.entries[strategy], sport, kg, TargetsRepository.toFuelTable(state.targetValues))
        val half = p.splits.firstOrNull { it.atMi >= raceMi / 2 - 1e-9 }

        SectionHeader("Pacing")
        SegmentedControl(SplitStrategy.entries.map { it.label }, strategy, { strategy = it })
        MetricTiles(
            listOfNotNull(
                if (sport == Sport.Ride) "Goal speed" to "${Fmt.one(raceMi / (goalSec / 3600.0))} mph" else "Goal pace" to "${PaceMath.fmt((goalSec / raceMi).roundToInt())}/mi",
                half?.let { "Halfway" to PaceMath.fmtTime((it.elapsedSec * (raceMi / 2) / it.atMi).roundToInt()) },
                "Finish" to PaceMath.fmtTime(goalSec),
            ),
        )
        Caption(
            if (strategy == 1) "Negative split: first half 1% slower than goal pace, second half 1% faster. Starting controlled is the most reliable way to hit a time."
            else "Even pace the whole way. Expect the first miles to feel too easy — hold back.",
        )
        RowGroup {
            p.splits.forEach { s ->
                val label = if (sport == Sport.Ride) "${Fmt.one(s.atMi)} mi" else if (s.atMi % 1.0 == 0.0) "Mile ${s.atMi.toInt()}" else "Finish · ${Fmt.one(s.atMi)}"
                val pace = if (sport == Sport.Ride) "${Fmt.one(3600.0 / s.paceSec)} mph" else "${PaceMath.fmt(s.paceSec)}/mi"
                ListRow(label, subtitle = pace, trailing = PaceMath.fmtTime(s.elapsedSec), chevron = false)
            }
        }

        SectionHeader("Fueling")
        if (p.fuel.isEmpty()) AppCard { Text("Under 75 minutes: no fuel needed. Water at aid stations if it's warm.", style = Theme.type.bodyM, color = Theme.colors.textSecondary) }
        else {
            MetricTiles(listOf("Target" to "${p.carbsPerHour} g/h", "Servings" to "${p.fuel.size} × ${RaceDay.SERVING_G} g", "Total" to "${p.totalCarbs} g"))
            RowGroup {
                p.fuel.forEachIndexed { i, f ->
                    ListRow("Gel ${i + 1} · ${RaceDay.SERVING_G} g", subtitle = "Around mile ${Fmt.one(f.atMi)}", trailing = PaceMath.fmtTime(f.atSec), icon = AppIcons.bolt, chevron = false)
                }
            }
            Caption("From your fueling table (Settings › Targets). Take each gel with water. Practise this exact schedule on your last 2–3 long runs — and watch your glucose on those runs to check it keeps you steady.")
        }

        p.carbLoad?.let { c ->
            SectionHeader("Carb loading · ${c.days} days before")
            AppCard {
                if (c.gramsLow != null) Text("${c.gramsLow}–${c.gramsHigh} g carbs per day", style = Theme.type.headingH3, color = Theme.colors.textPrimary)
                Text("${c.gPerKgLow}–${c.gPerKgHigh} g per kg body weight per day${stateKg?.let { " (${Fmt.one(it * 2.20462)} lb, 7-day average)" } ?: ""}.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
                if (stateKg == null) AppTextField("Body weight", weightLb, { weightLb = it }, suffix = "lb", keyboardType = KeyboardType.Decimal, helper = "No scale data yet — enter it to get grams.")
                listOf(
                    "Spread carbs over 5–6 meals and snacks rather than a few big ones — gentler on glucose.",
                    "Pick low-fiber, familiar foods: rice, pasta, bagels, potatoes, oats, juice.",
                    "A 10–15 minute easy walk after the bigger meals helps keep glucose in range.",
                    "Cut back fat and fiber so the extra carbs don't add stomach trouble.",
                    "The night before: an early, normal-size dinner you've eaten before.",
                ).forEach { Text("• $it", style = Theme.type.bodyS, color = Theme.colors.textPrimary) }
            }
        }

        SectionHeader("Race week")
        AppCard {
            listOf(
                "Keep the easy runs from the plan — short and relaxed. Don't add miles to test fitness.",
                "Sleep matters most two nights before the race.",
                "Lay out kit, gels and bib the night before. Nothing new on race day.",
                "Breakfast 2.5–3 hours before: 1–2 g carbs per kg, the same one you used before long runs.",
                "Warm up 5–10 minutes of easy jogging, then start at goal pace or slower.",
            ).forEach { Text("• $it", style = Theme.type.bodyS, color = Theme.colors.textPrimary) }
        }
    }
}
