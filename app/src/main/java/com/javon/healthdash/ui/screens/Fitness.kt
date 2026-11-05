package com.javon.healthdash.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.AdherenceItem
import com.javon.healthdash.calc.EffortRun
import com.javon.healthdash.calc.GoalLikelihood
import com.javon.healthdash.calc.HeartRate
import com.javon.healthdash.calc.HrActivity
import com.javon.healthdash.calc.HrStats
import com.javon.healthdash.calc.HrZone
import com.javon.healthdash.calc.Lthr
import com.javon.healthdash.calc.METERS_PER_MI
import com.javon.healthdash.calc.MaxHr
import com.javon.healthdash.calc.PredictionConfig
import com.javon.healthdash.calc.RaceForecast
import com.javon.healthdash.calc.RacePrediction
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.calc.TestPrompt
import com.javon.healthdash.calc.Weather
import com.javon.healthdash.calc.WorkoutKind
import com.javon.healthdash.calc.localDateOf
import com.javon.healthdash.data.db.PlanEntity
import com.javon.healthdash.data.db.PlannedEntity
import com.javon.healthdash.data.db.WorkoutEntity
import com.javon.healthdash.data.done
import com.javon.healthdash.data.inputs
import com.javon.healthdash.data.toSession
import com.javon.healthdash.data.sportOf
import com.javon.healthdash.domain.DashboardState
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

// Wires workouts, plan sessions and weather into the race forecast, heart-rate profile and test prompts.
// All numbers come from the calc module (RacePrediction, HeartRate).

private const val FT_PER_M = 3.28084

/** Hourly Las Vegas temperatures (past 92 days + forecast), as (epoch ms, °F). */
@Composable
fun rememberWeather(container: AppContainer): List<Pair<Long, Double>> {
    val since = remember { System.currentTimeMillis() - 100L * 24 * 3_600_000L }
    val rows by container.dao.weather(since).collectAsStateWithLifecycle(emptyList())
    return remember(rows) { rows.map { it.hourMs to it.tempF } }
}

/** The planned session a workout completed (watch-matched), for effort ratings and "all-out" marks. */
private fun plannedFor(w: WorkoutEntity, planned: List<PlannedEntity>, zone: ZoneId): PlannedEntity? =
    planned.firstOrNull { e -> e.status != "Done" && com.javon.healthdash.calc.Completion.matches(e.toSession(), listOf(w.done()), zone) != null }

data class HeartProfile(val maxHr: MaxHr?, val lthr: Lthr?, val zones: List<HrZone>, val easyCeiling: Int?)

private fun runs(s: DashboardState) = s.workouts.filter { sportOf(it.exerciseType) == Sport.Run }

/** Max HR (highest reliable reading), LTHR (latest qualifying effort) and LTHR-based zones. */
fun heartProfile(s: DashboardState, planned: List<PlannedEntity>, zone: ZoneId, weather: List<Pair<Long, Double>> = emptyList()): HeartProfile {
    val today = localDateOf(s.nowMs, zone)
    val rated = planned.filter { it.rpe != null }
    fun acts(maxForHard: Int?) = runs(s).map { w ->
        val p = plannedFor(w, rated, zone)
        val effort = EffortRun(w.startMs, (w.distanceM ?: 0.0) / METERS_PER_MI, w.elapsedSec ?: ((w.endMs - w.startMs) / 1000).toInt(), w.avgHr, p?.rpe)
        HrActivity(
            localDateOf(w.startMs, zone), effort.elapsedSec, w.maxHr?.roundToInt(),
            HrStats(w.hrMaxSustained, w.hrAvgLast20, w.hrAvgAfter10), w.avgHr, Weather.average(weather, w.startMs, w.endMs),
            hard = RacePrediction.isHard(effort, null, maxForHard), allOut = p?.rpe == 10,
            label = (w.title ?: "Run") + " · " + "%.1f".format(effort.distanceMi) + " mi", distanceMi = effort.distanceMi,
        )
    }
    val max = HeartRate.maxHr(acts(null), today)
    val lthr = HeartRate.latestLthr(acts(max?.bpm), max?.bpm)
    val zones = lthr?.let { l -> HeartRate.zones(l.bpm).map { HrZone(it.name, it.low ?: (l.bpm * 0.7).roundToInt(), it.high ?: (max?.bpm ?: l.bpm + 15)) } } ?: emptyList()
    return HeartProfile(max, lthr, zones, lthr?.let { HeartRate.easyCeiling(it.bpm) })
}

data class ForecastBundle(
    val forecast: RaceForecast?,
    /** Garmin VO2 max race prediction, shown separately as "Potential (watch estimate)". */
    val watchPotentialSec: Int?,
    val heart: HeartProfile,
    val prompts: List<TestPrompt>,
    val longestRecentMi: Double,
)

/** Race forecast for [plan] (marathon or any run distance). */
fun forecastFor(s: DashboardState, plan: PlanEntity, planned: List<PlannedEntity>, zone: ZoneId, weather: List<Pair<Long, Double>>, firstMarathon: Boolean): ForecastBundle {
    val today = localDateOf(s.nowMs, zone)
    val heart = heartProfile(s, planned, zone, weather)
    val corrected = correctedRuns(planned, s, zone)
    val rated = planned.filter { it.rpe != null }
    val efforts = runs(s).filter { (it.distanceM ?: 0.0) > 0 || it.id in corrected }.map { w ->
        val fix = corrected[w.id]
        EffortRun(
            w.startMs, fix?.doneMi ?: (w.distanceM!! / METERS_PER_MI), fix?.doneMin?.times(60) ?: w.elapsedSec ?: ((w.endMs - w.startMs) / 1000).toInt(),
            w.avgHr, plannedFor(w, rated, zone)?.rpe, Weather.average(weather, w.startMs, w.endMs),
            w.ascentM?.times(FT_PER_M), w.descentM?.times(FT_PER_M), label = w.title,
        )
    }
    // Endurance: longest run in the last 4 weeks (watch, corrected or marked done by hand).
    val fourWeeks = s.nowMs - 28L * 24 * 3_600_000L
    val manualLongest = planned.filter { it.status == "Done" && it.sport == Sport.Run.name && LocalDate.ofEpochDay(it.date) >= localDateOf(fourWeeks, zone) }.maxOfOrNull { it.doneMi ?: it.distanceMi ?: 0.0 } ?: 0.0
    val longest = maxOf(efforts.filter { it.startMs >= fourWeeks }.maxOfOrNull { it.distanceMi } ?: 0.0, manualLongest)
    // Average weekly running miles over the last 4 weeks (watch, corrected and hand-logged runs).
    val manualMiles = planned.filter { it.status == "Done" && it.sport == Sport.Run.name && LocalDate.ofEpochDay(it.date) >= localDateOf(fourWeeks, zone) }.sumOf { it.doneMi ?: it.distanceMi ?: 0.0 }
    val weeklyMiles = (efforts.filter { it.startMs >= fourWeeks }.sumOf { it.distanceMi } + manualMiles) / 4.0
    val mine = planned.filter { it.planId == plan.id }
    val adherence = RacePrediction.adherence(mine.filter { it.status != "Dropped" }.map { e ->
        AdherenceItem(LocalDate.ofEpochDay(e.date), WorkoutKind.valueOf(e.kind) == WorkoutKind.Long, e.status == "Done" || matchedWorkout(e, s, zone) != null)
    }, today)
    val taper = mine.filter { it.phase == "Taper" }.map { it.week }.distinct().size.takeIf { it > 0 }
    val race = LocalDate.ofEpochDay(plan.raceDate)
    val raceMi = plan.inputs().raceMi
    val f = RacePrediction.forecast(
        efforts, s.nowMs, today, race, raceMi, plan.goalMin?.times(60), longest, adherence, heart.lthr?.bpm, heart.maxHr?.bpm,
        firstMarathon, taper, PredictionConfig(), weeklyMiles = weeklyMiles,
    )
    val vo2 = s.vo2.filter { s.nowMs - it.timeMs < 90L * 24 * 3_600_000L }.maxByOrNull { it.timeMs }?.value
    val potential = vo2?.takeIf { it in 25.0..90.0 }?.let { GoalLikelihood.vdotTimeSec(it, raceMi) }
    // Test prompts: replace the next quality session.
    val nextQuality = mine.filter { it.status == "Planned" && LocalDate.ofEpochDay(it.date) > today && WorkoutKind.valueOf(it.kind).let { k -> k == WorkoutKind.Tempo || k == WorkoutKind.Intervals || k == WorkoutKind.ClubHard || k == WorkoutKind.ClubSteady } }
        .minByOrNull { it.date }?.let { LocalDate.ofEpochDay(it.date) }
    val pace = pace145Trend(s, zone)
    val paceNow = pace.takeLast(3).map { it.second }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val paceAtTest = heart.lthr?.let { l -> pace.lastOrNull { !it.first.isAfter(l.date) }?.second }
    val tested = heart.lthr != null
    val planWeek = mine.firstOrNull { LocalDate.ofEpochDay(it.date) == today }?.week ?: mine.filter { LocalDate.ofEpochDay(it.date) <= today }.maxOfOrNull { it.week } ?: 0
    val prompts = HeartRate.prompts(today, race, nextQuality, heart.lthr, heart.maxHr, paceNow, paceAtTest, planWeek, tested)
    return ForecastBundle(f, potential, heart, prompts, longest)
}

/** Easy-run fitness marker: pace normalised to 145 bpm for runs averaging 140–150, oldest first. */
fun pace145Trend(s: DashboardState, zone: ZoneId): List<Pair<LocalDate, Int>> =
    runs(s).sortedBy { it.startMs }.mapNotNull { w ->
        val mi = (w.distanceM ?: return@mapNotNull null) / METERS_PER_MI
        HeartRate.paceAt145(mi, w.elapsedSec ?: ((w.endMs - w.startMs) / 1000).toInt(), w.avgHr)?.let { localDateOf(w.startMs, zone) to it }
    }

/** Forecast °F at the usual run time on [date]. */
fun forecastAt(weather: List<Pair<Long, Double>>, date: LocalDate, runTimeMin: Int, zone: ZoneId): Double? =
    Weather.at(weather, date, LocalTime.of(runTimeMin / 60, runTimeMin % 60), zone)
