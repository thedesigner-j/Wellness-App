package com.javon.healthdash.calc

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Distance-and-pace running plans. Everything here is fixed rules and published formulas — no AI.

const val MARATHON_MI = 26.2188
const val HALF_MI = 13.1094
const val TEN_K_MI = 6.21371
const val FIVE_K_MI = 3.10686
const val METERS_PER_MI = 1609.344

/** One completed run (from Garmin via Health Connect). */
data class RunSample(val timeMs: Long, val distanceMi: Double, val durationSec: Int) {
    val paceSec get() = durationSec / distanceMi
}

/** One completed ride (cross-training toward running fitness). */
data class RideSample(val timeMs: Long, val durationSec: Int, val distanceMi: Double?)

/** Where the runner is today: the plan starts from this. */
data class RunFitness(
    /** Weekly base the plan starts from: run miles + ride credit. */
    val weeklyMiles: Double,
    val longestRunMi: Double,
    /** Typical easy pace, seconds per mile. */
    val easyPaceSec: Int,
    val runs: Int,
    val days: Int,
    val runMilesPerWeek: Double = weeklyMiles,
    val rides: Int = 0,
    val rideMinPerWeek: Double = 0.0,
    /** Run-equivalent miles per week credited from rides. */
    val rideCreditMi: Double = 0.0,
)

object Fitness {
    /** Share of ride time that counts as running time for aerobic base (cycling is lower-impact, less specific). */
    const val RIDE_TO_RUN_TIME = 0.4
    const val DEFAULT_EASY_PACE_SEC = 660 // 11:00/mi when there are no runs to measure

    /**
     * Last [days] days of runs and rides → weekly base, longest run and typical (median) run pace.
     * Weekly numbers are scaled to the days of history that exist ([historyDays]) so a new
     * connection isn't averaged down.
     *
     * Ride credit (run-equivalent miles) = ride minutes × 0.4 ÷ easy pace, capped at
     * max(run miles, 10) per week — rides build the engine but not running durability.
     */
    fun fromActivities(runs: List<RunSample>, rides: List<RideSample>, nowMs: Long, days: Int = 30, historyDays: Int = days): RunFitness? {
        val from = nowMs - days * DAY_MS
        val recentRuns = runs.filter { it.timeMs >= from && it.distanceMi >= 1.0 && it.durationSec > 0 }
        val recentRides = rides.filter { it.timeMs >= from && it.durationSec >= 10 * 60 }
        if (recentRuns.isEmpty() && recentRides.isEmpty()) return null
        val span = historyDays.coerceIn(7, days)
        val paces = recentRuns.map { it.paceSec }.sorted()
        val easy = if (paces.isEmpty()) DEFAULT_EASY_PACE_SEC else paces[paces.size / 2].roundToInt()
        val runMi = recentRuns.sumOf { it.distanceMi } * 7.0 / span
        val rideMin = recentRides.sumOf { it.durationSec } / 60.0 * 7.0 / span
        val credit = min(rideMin * RIDE_TO_RUN_TIME * 60 / easy, max(runMi, 10.0))
        return RunFitness(
            weeklyMiles = runMi + credit,
            longestRunMi = recentRuns.maxOfOrNull { it.distanceMi } ?: 0.0,
            easyPaceSec = easy,
            runs = recentRuns.size,
            days = span,
            runMilesPerWeek = runMi,
            rides = recentRides.size,
            rideMinPerWeek = rideMin,
            rideCreditMi = credit,
        )
    }

    fun fromRuns(runs: List<RunSample>, nowMs: Long, days: Int = 30, historyDays: Int = days): RunFitness? =
        fromActivities(runs, emptyList(), nowMs, days, historyDays)?.takeIf { it.runs > 0 }
}

/** Training paces in seconds per mile; ranges are (fast, slow). */
data class Paces(
    val race: Int,
    val easy: IntRange,
    val long: IntRange,
    val tempo: Int,
    val interval: Int,
    val recovery: IntRange,
)

object PaceMath {
    /** Riegel: T2 = T1 × (D2 / D1)^1.06. */
    fun riegel(timeSec: Double, fromMi: Double, toMi: Double): Double = timeSec * exp(1.06 * ln(toMi / fromMi))

    /** Paces anchored on the goal: race pace, half-marathon (tempo) and 5K (interval) equivalents. */
    fun forGoal(raceMi: Double, goalSec: Int): Paces {
        val racePace = (goalSec / raceMi).roundToInt()
        val tempo = (riegel(goalSec.toDouble(), raceMi, HALF_MI) / HALF_MI).roundToInt()
        val interval = (riegel(goalSec.toDouble(), raceMi, FIVE_K_MI) / FIVE_K_MI).roundToInt()
        // Easy and long-run paces sit relative to marathon-equivalent pace.
        val mp = (riegel(goalSec.toDouble(), raceMi, MARATHON_MI) / MARATHON_MI).roundToInt()
        return Paces(
            race = racePace,
            easy = (mp + 60)..(mp + 90),
            long = (mp + 30)..(mp + 60),
            tempo = tempo,
            interval = interval,
            recovery = (mp + 90)..(mp + 120),
        )
    }

    /** Marathon time a runner's current easy pace typically supports (easy ≈ MP + 75 s/mi). */
    fun predictedMarathonSec(f: RunFitness): Int = ((f.easyPaceSec - 75) * MARATHON_MI).roundToInt()

    fun fmt(sec: Int): String = "${sec / 60}:${"%02d".format(sec % 60)}"
    fun fmtRange(r: IntRange) = "${fmt(r.first)}–${fmt(r.last)}"
    fun fmtTime(sec: Int): String = if (sec >= 3600) "${sec / 3600}:${"%02d".format(sec % 3600 / 60)}:${"%02d".format(sec % 60)}" else fmt(sec)
}

data class RunRules(
    val maxWeeklyIncrease: Double = 0.10,
    val lighterEvery: Int = 4,
    val lighterFactor: Double = 0.80,
    /** Long run grows by at most this many miles per week. */
    val longIncreaseMaxMi: Double = 2.0,
    val peakLongMi: Double = 20.0,
    val relayPeakLongMi: Double = 14.0,
)

data class RunPlanSummary(
    val paces: Paces,
    val goalSec: Int,
    val peakLongMi: Double,
    val peakWeeklyMi: Double,
    val warnings: List<String>,
)

object RunPlanBuilder {

    private fun r1(x: Double) = (x * 2).roundToInt() / 2.0 // round to 0.5 mi

    /**
     * Builds a distance-based plan for a marathon or the 200-mile relay.
     * Phases, taper, strength and recurring sessions follow the same rules as [PlanBuilder].
     */
    fun generate(
        inputs: PlanInputs,
        fitness: RunFitness,
        rules: TrainingRules = TrainingRules(),
        runRules: RunRules = RunRules(),
        fuel: FuelTable = FuelTable.DEFAULT,
    ): Pair<PlanResult, RunPlanSummary?> {
        val first = PlanBuilder.monday(inputs.startDate)
        val weeks = ChronoUnit.WEEKS.between(first, PlanBuilder.monday(inputs.raceDate)).toInt() + 1
        val taper = rules.taperWeeks(inputs.race, inputs.raceMi)
        if (inputs.raceDate.isBefore(inputs.startDate)) return PlanResult.Invalid("Race date is before the plan start") to null
        if (weeks < taper + 3) return PlanResult.Invalid("Need at least ${taper + 3} weeks before the race for base, taper and race week") to null
        val fixedDays = inputs.fixed.map { it.day }.toSet()
        val days = inputs.days + fixedDays
        val free = days - fixedDays
        if (days.size < 3) return PlanResult.Invalid("Pick at least 3 running days") to null
        if (free.isEmpty()) return PlanResult.Invalid("Leave at least one day free for the long run") to null

        val relay = inputs.race.relay
        val raceMi = inputs.raceMi
        // Finish goal: what current easy pace supports at this distance (Riegel from the marathon equivalent).
        val predictedSec = PaceMath.riegel(PaceMath.predictedMarathonSec(fitness).toDouble(), MARATHON_MI, if (relay) raceMi / 3 else raceMi) * (if (relay) 3 else 1)
        val goalSec = inputs.goalMin?.times(60) ?: predictedSec.roundToInt()
        val paces = if (relay) PaceMath.forGoal(raceMi / 3, (goalSec / 3.0).roundToInt()) else PaceMath.forGoal(raceMi, goalSec)
        val raceLabel = if (inputs.race.custom) "${r1(raceMi)}-mile race" else inputs.race.label
        val phases = PlanBuilder.phases(weeks, taper)
        val warnings = mutableListOf<String>()

        // ---- Long-run ladder: from current longest up to the peak, +2 mi/week max, cutback weeks. ----
        val peakTarget = peakLongFor(raceMi, relay, runRules)
        val start = r1(fitness.longestRunMi.coerceIn(4.0, peakTarget - 2))
        val peakWeek = phases.indexOfLast { it == Phase.Peak || it == Phase.Build }.coerceAtLeast(0)
        val longs = DoubleArray(weeks)
        var last = start
        for (w in 0..peakWeek) {
            val lighter = (w + 1) % rules.lighterEvery == 0 && w != peakWeek
            if (w == 0) { longs[w] = start; continue }
            if (lighter) { longs[w] = r1(last * 0.7); continue }
            // Spread the remaining climb over the remaining full weeks, never more than the cap.
            val fullLeft = (w..peakWeek).count { (it + 1) % rules.lighterEvery != 0 || it == peakWeek }
            val step = min(runRules.longIncreaseMaxMi, max(0.5, (peakTarget - last) / fullLeft))
            last = r1(min(peakTarget, last + step))
            longs[w] = last
        }
        val peakLong = longs.maxOrNull() ?: start
        if (peakLong < peakTarget - 2) warnings += "With ${weeks} weeks, the long run can only safely reach ${r1(peakLong)} mi (plans for this distance usually peak around ${r1(peakTarget)} mi). Consider a later race or a finish goal."
        // Taper long runs step down to race day.
        val taperLongs = when (taper) { 1 -> listOf(0.6); 2 -> listOf(0.65, 0.45); else -> listOf(0.8, 0.6, 0.45) }
        phases.forEachIndexed { w, p -> if (p == Phase.Taper) longs[w] = r1(peakLong * taperLongs[phases.subList(0, w).count { it == Phase.Taper }]) }

        // ---- Weekly mileage: from current, +10%/week, cutbacks; at least what the week's runs need. ----
        val runDays = free.size + inputs.fixed.count { it.sport == Sport.Run }
        val longShare = when { runDays <= 3 -> 0.45; runDays == 4 -> 0.40; runDays == 5 -> 0.35; else -> 0.30 }
        val peakWeekly = r1(max(fitness.weeklyMiles, peakLong / longShare).coerceAtMost(max(peakLong * 3, fitness.weeklyMiles * 1.3)))
        var weekly = max(fitness.weeklyMiles, start / longShare * 0.8)
        val targets = DoubleArray(weeks)
        for (w in 0 until weeks) {
            targets[w] = when (phases[w]) {
                // Taper steps down from the mileage actually reached at peak.
                Phase.Taper -> weekly * taperLongs[phases.subList(0, w).count { it == Phase.Taper }]
                Phase.Race -> weekly * 0.3
                else -> {
                    val lighter = (w + 1) % rules.lighterEvery == 0 && w != peakWeek
                    if (lighter) weekly * runRules.lighterFactor
                    else { if (w > 0) weekly = min(peakWeekly, weekly * (1 + rules.maxWeeklyIncrease)); weekly }
                }
            }
        }
        if (inputs.goalMin != null) {
            val predicted = predictedSec
            if (goalSec < predicted * 0.93) warnings += "Ambitious goal: your current easy pace (${PaceMath.fmt(fitness.easyPaceSec)}/mi) usually supports about ${PaceMath.fmtTime(predicted.roundToInt())}. The plan trains at your goal paces — re-check after a few weeks."
        }

        // ---- Sessions ----
        val out = mutableListOf<PlanWeek>()
        var mpFinish = 0.0
        for (w in 0 until weeks) {
            val phase = phases[w]
            val wkStart = first.plusWeeks(w.toLong())
            fun date(d: DayOfWeek) = wkStart.plusDays((d.value - 1).toLong())
            val sessions = mutableListOf<PlannedSession>()
            /** Adds a run and returns the miles actually scheduled (0 if the day is before the plan starts). */
            fun run(d: DayOfWeek, kind: WorkoutKind, miles: Double, sec: Int, detail: String, name: String? = null): Double {
                val dt = date(d)
                if (dt.isBefore(inputs.startDate)) return 0.0
                val min = (sec / 60.0).roundToInt()
                sessions += PlannedSession(dt, Sport.Run, kind, min, fuel.note(min, kind.intensity), name, r1(miles), detail)
                return r1(miles)
            }
            fun easyRun(d: DayOfWeek, miles: Double, kind: WorkoutKind = WorkoutKind.Easy) {
                val p = if (kind == WorkoutKind.Recovery) paces.recovery else paces.easy
                val mid = (p.first + p.last) / 2
                run(d, kind, miles, (miles * mid).roundToInt(), "${r1(miles)} mi easy @ ${PaceMath.fmtRange(p)}/mi")
            }
            val easyOnly = phase == Phase.Taper || phase == Phase.Race
            val clubs = inputs.fixed.filter { (!easyOnly || it.keepInTaper) && (phase != Phase.Race || !date(it.day).isAfter(inputs.raceDate.minusDays(2))) }
            var used = 0.0
            clubs.forEach { f ->
                val kind = WorkoutKind.recurring(if (easyOnly) Intensity.Easy else f.intensity)
                val dt = date(f.day)
                if (!dt.isBefore(inputs.startDate)) {
                    sessions += PlannedSession(dt, f.sport, kind, f.durationMin, fuel.note(f.durationMin, kind.intensity), f.name, f.distanceMi,
                        "Your ${f.name.lowercase()}" + (f.distanceMi?.let { " · ${it} mi" } ?: "") + if (easyOnly) " · keep it easy this week" else "")
                }
                if (f.sport == Sport.Run) used += f.distanceMi ?: (f.durationMin * 60.0 / ((paces.easy.first + paces.easy.last) / 2))
            }
            val hardClubDays = if (easyOnly) emptySet() else clubs.filter { it.intensity == Intensity.Hard }.map { it.day }.toSet()

            if (phase == Phase.Race) {
                val rd = inputs.raceDate.dayOfWeek
                val shake = free.filter { it.value < rd.value }.sortedBy { it.value }.takeLast(2 - clubs.size.coerceAtMost(1))
                shake.forEachIndexed { i, d -> easyRun(d, if (i == shake.lastIndex) 2.0 else 4.0, WorkoutKind.Recovery) }
                sessions += PlannedSession(inputs.raceDate, Sport.Run, WorkoutKind.Race, (goalSec / 60.0).roundToInt(), fuel.note(goalSec / 60, Intensity.Hard), null, raceMi,
                    if (relay) "Relay legs · goal ${PaceMath.fmtTime(goalSec)} total · ~${PaceMath.fmt(paces.race)}/mi" else "$raceLabel · goal ${PaceMath.fmtTime(goalSec)} · ${PaceMath.fmt(paces.race)}/mi")
                out += PlanWeek(w, wkStart, phase, (sessions.sumOf { it.durationMin }), false, sessions.sortedBy { it.date })
                continue
            }

            val lighter = (w + 1) % rules.lighterEvery == 0 && w != peakWeek && !easyOnly
            val longDay = if (inputs.longDay in free) inputs.longDay else free.maxBy { it.value }
            val longMi = longs[w]
            // Marathon-pace finish on alternate full build/peak long runs.
            // Race-pace finishes on alternate long runs for half marathon and longer.
            val mpWeek = !relay && raceMi >= 13.0 && (phase == Phase.Build || phase == Phase.Peak) && !lighter && w % 2 == 1
            if (mpWeek) mpFinish = min(10.0, max(mpFinish + 2, 4.0))
            val mp = if (mpWeek) min(mpFinish, longMi - 4) else 0.0
            val longPace = (paces.long.first + paces.long.last) / 2
            val longSec = ((longMi - mp) * longPace + mp * paces.race).roundToInt()
            used += run(longDay, WorkoutKind.Long, longMi, longSec,
                if (mp > 0) "${r1(longMi)} mi · first ${r1(longMi - mp)} @ ${PaceMath.fmtRange(paces.long)}/mi, last ${r1(mp)} @ ${PaceMath.fmt(paces.race)}/mi (goal pace)"
                else "${r1(longMi)} mi @ ${PaceMath.fmtRange(paces.long)}/mi · conversational")

            val hardCount = when (phase) { Phase.Base -> 1; Phase.Build, Phase.Peak -> if (lighter) 1 else 2; else -> 1 } - hardClubDays.size
            val hard = PlanBuilder.hardDays(free - longDay, longDay, hardCount.coerceAtLeast(0), hardClubDays)
            // Quality sessions scale with the week's mileage (about 15–18% each), so they grow as the plan grows.
            val target = targets[w]
            hard.forEachIndexed { i, d ->
                val warm = 1.5
                val cool = 1.0
                if (i == 0) {
                    val t = r1((target * 0.18 - warm - cool).coerceIn(2.0, 6.0)).let { if (phase == Phase.Taper) min(it, 3.0) else it }
                    val miles = warm + t + cool
                    val sec = ((warm + cool) * paces.easy.last + t * paces.tempo).roundToInt()
                    used += run(d, WorkoutKind.Tempo, miles, sec, "${warm} mi easy · ${r1(t)} mi @ ${PaceMath.fmt(paces.tempo)}/mi (tempo) · ${cool} mi easy")
                } else {
                    // Each rep: 800 m fast + 400 m jog = 0.75 mi.
                    val reps = ((target * 0.16 - warm - cool) / 0.75).roundToInt().coerceIn(4, 8)
                    val miles = warm + cool + reps * 0.75
                    val sec = ((warm + cool + reps * 0.25) * paces.easy.last + reps * 0.5 * paces.interval).roundToInt()
                    used += run(d, WorkoutKind.Intervals, miles, sec, "${warm} mi easy · ${reps} × 800 m @ ${PaceMath.fmt(paces.interval)}/mi with 400 m jog · ${cool} mi easy")
                }
            }

            var easyDays = (free - longDay - hard.toSet()).sortedBy { it.value }
            // Relay and ultras: back-to-back long days in peak weeks (relay also gets a double day).
            if ((relay || raceMi > 27.0) && phase == Phase.Peak) {
                val next = listOf(longDay.plus(1), longDay.minus(1)).firstOrNull { it in easyDays }
                if (next != null) {
                    val b2b = r1(longMi * 0.6)
                    used += run(next, WorkoutKind.BackToBack, b2b, (b2b * paces.long.last).roundToInt(), "${b2b} mi back-to-back with the long run @ ${PaceMath.fmtRange(paces.long)}/mi — ${if (relay) "relay" else "ultra"} practice")
                    easyDays = easyDays - next
                }
                if (relay) easyDays.firstOrNull()?.let { d ->
                    used += run(d, WorkoutKind.DoubleDay, 8.0, 8 * paces.easy.last, "Double: 4 mi AM + 4 mi PM easy — relay practice")
                    easyDays = easyDays - d
                }
            }
            val remaining = targets[w] - used
            if (easyDays.isNotEmpty()) {
                val each = r1((remaining / easyDays.size).coerceIn(3.0, 10.0))
                easyDays.forEach { easyRun(it, each, if (phase == Phase.Taper) WorkoutKind.Recovery else WorkoutKind.Easy) }
            }
            val lastTaperWeek = phase == Phase.Taper && phases.getOrNull(w + 1) == Phase.Race
            if (!lastTaperWeek) inputs.strengthDays.sortedBy { it.value }.take(rules.strengthPerWeek).forEach {
                val dt = date(it)
                if (!dt.isBefore(inputs.startDate)) sessions += PlannedSession(dt, Sport.Strength, WorkoutKind.Strength, rules.strengthMin, "", detail = "Strength & mobility · ${rules.strengthMin} min")
            }
            out += PlanWeek(w, wkStart, phase, sessions.filter { it.sport == Sport.Run }.sumOf { it.durationMin }, lighter, sessions.sortedWith(compareBy({ it.date }, { it.sport == Sport.Strength })))
        }
        val peakMiles = out.maxOf { wk -> wk.sessions.filter { it.sport == Sport.Run && it.kind != WorkoutKind.Race }.sumOf { it.distanceMi ?: 0.0 } }
        return PlanResult.Ok(out) to RunPlanSummary(paces, goalSec, peakLong, peakMiles, warnings)
    }

    fun weekMiles(w: PlanWeek) = w.sessions.filter { it.sport == Sport.Run }.sumOf { it.distanceMi ?: 0.0 }
}

/** Typical peak long run for a race distance (miles). */
fun peakLongFor(raceMi: Double, relay: Boolean, rules: RunRules = RunRules()): Double = when {
    relay -> rules.relayPeakLongMi
    raceMi <= 3.2 -> 7.0
    raceMi <= 6.3 -> 9.0
    raceMi <= 10.0 -> 12.0
    raceMi <= 13.2 -> 14.0
    raceMi <= 26.3 -> rules.peakLongMi
    raceMi <= 31.1 -> 22.0
    else -> 26.0
}
