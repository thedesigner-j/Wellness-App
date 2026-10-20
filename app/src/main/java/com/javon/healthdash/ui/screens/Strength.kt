package com.javon.healthdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.Phase
import com.javon.healthdash.calc.PlanBuilder
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.calc.StrengthPlan
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.ExerciseFigure
import com.javon.healthdash.ui.components.Heading
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.Tag
import com.javon.healthdash.ui.components.TagType
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/** A planned strength session: dumbbell routine for the phase, with an animated demo of every exercise. */
@Composable
fun StrengthScreen(container: AppContainer, state: DashboardState, sessionId: Long, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val data = rememberTrainingData(container)
    val scope = rememberCoroutineScope()
    val e = data.planned.firstOrNull { it.id == sessionId }
    ScreenFrame(
        topBar = { SmallTopBar("Strength", onBack) },
        footer = {
            if (e != null) {
                if (e.status == "Done") AppButton("Undo complete", { scope.launch { container.training.undoDone(e.id, zone) } }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
                else if (!LocalDate.ofEpochDay(e.date).isAfter(java.time.LocalDate.now(zone))) AppButton("Mark complete", { scope.launch { container.training.markDone(e.id, null, null); onBack() } }, Modifier.fillMaxWidth())
            }
        },
    ) {
        if (e == null) { EmptyState("Session not found", "It may have been moved or rebuilt."); return@ScreenFrame }
        val date = LocalDate.ofEpochDay(e.date)
        val phase = runCatching { Phase.valueOf(e.phase) }.getOrDefault(Phase.Base)
        // Second strength day of the week → routine B.
        val second = data.planned.any { it.planId == e.planId && it.sport == Sport.Strength.name && it.id != e.id && PlanBuilder.monday(LocalDate.ofEpochDay(it.date)) == PlanBuilder.monday(date) && (it.date < e.date || (it.date == e.date && it.id < e.id)) }
        val w = StrengthPlan.workout(phase, second)
        Heading(w.name, "${Fmt.dayShort(date.atStartOfDay(zone).toInstant().toEpochMilli())} · ${phase.name} phase")
        if (e.status == "Done") Tag("Done", TagType.InRange)
        MetricTiles(listOf("Exercises" to "${w.sets.size}", "Time" to "≈ ${w.minutes} min", "Rest" to "${w.restSec} s"))
        Caption("Warm up 5 min first: easy jog in place, leg swings, bodyweight squats. ${w.note}")
        Text("Equipment: a pair of dumbbells. A chair or couch for split squats.", style = Theme.type.bodyS, color = Theme.colors.textSecondary)
        w.sets.forEachIndexed { i, s ->
            AppCard {
                ExerciseFigure(s.exercise)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${i + 1}. ${s.exercise.label}", style = Theme.type.headingH3, color = Theme.colors.textPrimary)
                    Text("${s.sets} × ${s.reps}", style = Theme.type.labelL, color = Theme.colors.textAccent)
                }
                Text("${s.exercise.target} · ${s.load}", style = Theme.type.bodyS, color = Theme.colors.textSecondary)
                s.exercise.cues.forEachIndexed { n, cue ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                        Text("${n + 1}", style = Theme.type.labelM, color = Theme.colors.textTertiary, modifier = Modifier.width(16.dp))
                        Text(cue, style = Theme.type.bodyM, color = Theme.colors.textPrimary)
                    }
                }
            }
        }
        Caption("Stop any exercise that causes sharp pain. Sore the next day is normal in base; heavy legs before a key run means go lighter.")
    }
}
