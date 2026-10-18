package com.javon.healthdash.calc

import java.time.DayOfWeek
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Distance-and-speed cycling plans. Fixed rules, no AI.

/** Where the rider is today: the plan starts from this. */
data class RideFitness(
    val weeklyMiles: Double,
    val longestRideMi: Double,
    /** Typical (median) moving speed, mph. */
    val speedMph: Double,
    val rides: Int,
    val days: Int,
)

object RideFitnessCalc {
    const val DEFAULT_SPEED_MPH = 14.0

    /** Last [days] days of rides with distance → weekly miles, longest ride, median speed. */
    fun fromRides(rides: List<RideSample>, nowMs: Long, days: Int = 30, historyDays: Int = days): RideFitness? {
        val recent = rides.filter { it.timeMs >= nowMs - days * DAY_MS && (it.distanceMi ?: 0.0) >= 3.0 && it.durationSec >= 10 * 60 }
        if (recent.isEmpty()) return null
        val span = historyDays.coerceIn(7, days)
        val speeds = recent.map { it.distanceMi!! / (it.durationSec / 3600.0) }.sorted()
        return RideFitness(
            weeklyMiles = recent.sumOf { it.distanceMi!! } * 7.0 / span,
            longestRideMi = recent.maxOf { it.distanceMi!! },
            speedMph = speeds[speeds.size / 2],
            rides = recent.size,
            days = span,
        )
    }
}

/** Training speeds in mph. */
data class Speeds(val race: Double, val endurance: ClosedFloatingPointRange<Double>, val tempo: Double, val recovery: Double)

data class RidePlanSummary(val speeds: Speeds, val goalSec: Int, val peakLongMi: Double, val peakWeeklyMi: Double, val warnings: List<String>)

object RidePlanBuilder {
    private fun r5(x: Double) = (x / 5).roundToInt() * 5.0  // rides in 5-mile steps
    private fun fmtSpeed(x: Double) = "%.1f".format(java.util.Locale.US, x)
    private fun fmtRange(r: ClosedFloatingPointRange<Double>) = "${fmtSpeed(r.start)}–${fmtSpeed(r.endInclusive)}"

    /** Speeds anchored on the goal: endurance 85–92%, tempo/sweet spot 105%, recovery 75% of race speed. */
    fun speedsFor(raceMi: Double, goalSec: Int): Speeds {
        val race = raceMi / (goalSec / 3600.0)
        return Speeds(race, (race * 0.85)..(race * 0.92), race * 1.05, race * 0.75)
    }

    /** Peak long ride: the full distance for ≤ 50 mi, otherwise ~80% of it (at least 50). */
    fun peakLongFor(raceMi: Double): Double = if (raceMi <= 50) r5(raceMi) else max(50.0, r5(raceMi * 0.8))

    fun generate(inputs: PlanInputs, fitness: RideFitness, rules: TrainingRules = TrainingRules(), fuel: FuelTable = FuelTable.DEFAULT): Pair<PlanResult, RidePlanSummary?> {
        val first = PlanBuilder.monday(inputs.startDate)
        val weeks = ChronoUnit.WEEKS.between(first, PlanBuilder.monday(inputs.raceDate)).toInt() + 1
        val raceMi = inputs.raceMi
        val taper = rules.taperWeeks(inputs.race, raceMi)
        if (inputs.raceDate.isBefore(inputs.startDate)) return PlanResult.Invalid("Race date is before the plan start") to null
        if (weeks < taper + 3) return PlanResult.Invalid("Need at least ${taper + 3} weeks before the race for base, taper and race week") to null
        val fixedDays = inputs.fixed.map { it.day }.toSet()
        val days = inputs.days + fixedDays
        val free = days - fixedDays
        if (days.size < 3) return PlanResult.Invalid("Pick at least 3 riding days") to null
        if (free.isEmpty()) return PlanResult.Invalid("Leave at least one day free for the long ride") to null

        val goalSec = inputs.goalMin?.times(60) ?: (raceMi / (fitness.speedMph * 0.95) * 3600).roundToInt()
        val sp = speedsFor(raceMi, goalSec)
        val endurance = (sp.endurance.start + sp.endurance.endInclusive) / 2
        val phases = PlanBuilder.phases(weeks, taper)
        val warnings = mutableListOf<String>()
        if (inputs.goalMin != null && sp.race > fitness.speedMph * 1.15) {
            warnings += "Ambitious goal: ${fmtSpeed(sp.race)} mph average vs your typical ${fmtSpeed(fitness.speedMph)} mph. The plan trains at your goal speeds — re-check after a few weeks."
        }

        // Long-ride ladder: +10 mi/week max, cutback weeks, peak in the last build/peak week.
        val peakTarget = peakLongFor(raceMi)
        val start = r5(fitness.longestRideMi.coerceIn(15.0, max(15.0, peakTarget - 10)))
        val peakWeek = phases.indexOfLast { it == Phase.Peak || it == Phase.Build }.coerceAtLeast(0)
        val longs = DoubleArray(weeks)
        var last = start
        for (w in 0..peakWeek) {
            val lighter = (w + 1) % rules.lighterEvery == 0 && w != peakWeek
            if (w == 0) { longs[w] = start; continue }
            if (lighter) { longs[w] = r5(last * 0.7); continue }
            val fullLeft = (w..peakWeek).count { (it + 1) % rules.lighterEvery != 0 || it == peakWeek }
            last = r5(min(peakTarget, last + min(10.0, max(5.0, (peakTarget - last) / fullLeft))))
            longs[w] = last
        }
        val peakLong = longs.maxOrNull() ?: start
        if (peakLong < peakTarget - 10) warnings += "With $weeks weeks, the long ride can only safely reach ${peakLong.toInt()} mi (plans for this distance usually peak around ${peakTarget.toInt()} mi)."
        val taperLongs = when (taper) { 1 -> listOf(0.6); 2 -> listOf(0.65, 0.45); else -> listOf(0.8, 0.6, 0.45) }
        phases.forEachIndexed { w, p -> if (p == Phase.Taper) longs[w] = r5(peakLong * taperLongs[phases.subList(0, w).count { it == Phase.Taper }]) }

        // Weekly miles: from current, +10%/week, cutbacks; taper from the mileage reached.
        val peakWeekly = max(fitness.weeklyMiles, peakLong / 0.45)
        var weekly = max(fitness.weeklyMiles, start / 0.45 * 0.8)
        val targets = DoubleArray(weeks)
        for (w in 0 until weeks) {
            targets[w] = when (phases[w]) {
                Phase.Taper -> weekly * taperLongs[phases.subList(0, w).count { it == Phase.Taper }]
                Phase.Race -> weekly * 0.25
                else -> {
                    val lighter = (w + 1) % rules.lighterEvery == 0 && w != peakWeek
                    if (lighter) weekly * 0.75 else { if (w > 0) weekly = min(peakWeekly, weekly * (1 + rules.maxWeeklyIncrease)); weekly }
                }
            }
        }

        val out = mutableListOf<PlanWeek>()
        for (w in 0 until weeks) {
            val phase = phases[w]
            val wkStart = first.plusWeeks(w.toLong())
            fun date(d: DayOfWeek) = wkStart.plusDays((d.value - 1).toLong())
            val sessions = mutableListOf<PlannedSession>()
            fun ride(d: DayOfWeek, kind: WorkoutKind, miles: Double, mph: Double, detail: String): Double {
                val dt = date(d)
                if (dt.isBefore(inputs.startDate)) return 0.0
                val min = (miles / mph * 60).roundToInt()
                sessions += PlannedSession(dt, Sport.Ride, kind, min, fuel.note(min, kind.intensity), null, miles, detail)
                return miles
            }
            val easyOnly = phase == Phase.Taper || phase == Phase.Race
            var used = 0.0
            val clubs = inputs.fixed.filter { (!easyOnly || it.keepInTaper) && (phase != Phase.Race || !date(it.day).isAfter(inputs.raceDate.minusDays(2))) }
            clubs.forEach { f ->
                val kind = WorkoutKind.recurring(if (easyOnly) Intensity.Easy else f.intensity)
                val dt = date(f.day)
                if (!dt.isBefore(inputs.startDate)) sessions += PlannedSession(dt, f.sport, kind, f.durationMin, fuel.note(f.durationMin, kind.intensity), f.name, f.distanceMi, "Your ${f.name.lowercase()}" + if (easyOnly) " · keep it easy this week" else "")
                if (f.sport == Sport.Ride) used += f.distanceMi ?: (f.durationMin / 60.0 * endurance)
            }
            val hardClubDays = if (easyOnly) emptySet() else clubs.filter { it.intensity == Intensity.Hard }.map { it.day }.toSet()

            if (phase == Phase.Race) {
                val rd = inputs.raceDate.dayOfWeek
                free.filter { it.value < rd.value }.sortedBy { it.value }.takeLast(2).forEachIndexed { i, d ->
                    ride(d, WorkoutKind.Recovery, if (i == 0) 15.0 else 10.0, sp.recovery, "Easy spin with 3 × 1 min at race effort")
                }
                val label = if (inputs.race.custom) "${raceMi.roundToInt()}-mile ride" else inputs.race.label
                sessions += PlannedSession(inputs.raceDate, Sport.Ride, WorkoutKind.Race, (goalSec / 60.0).roundToInt(), fuel.note(goalSec / 60, Intensity.Hard), null, raceMi,
                    "$label · goal ${PaceMath.fmtTime(goalSec)} · ${fmtSpeed(sp.race)} mph average")
                out += PlanWeek(w, wkStart, phase, sessions.filter { it.sport == Sport.Ride }.sumOf { it.durationMin }, false, sessions.sortedBy { it.date })
                continue
            }

            val lighter = (w + 1) % rules.lighterEvery == 0 && w != peakWeek && !easyOnly
            val longDay = if (inputs.longDay in free) inputs.longDay else free.maxBy { it.value }
            used += ride(longDay, WorkoutKind.Long, longs[w], endurance, "${longs[w].toInt()} mi @ ${fmtRange(sp.endurance)} mph · steady, fuel from the first hour")

            val hardCount = when (phase) { Phase.Base -> 1; Phase.Build, Phase.Peak -> if (lighter) 1 else 2; else -> 1 } - hardClubDays.size
            val hard = PlanBuilder.hardDays(free - longDay, longDay, hardCount.coerceAtLeast(0), hardClubDays)
            hard.forEachIndexed { i, d ->
                if (i == 0) {
                    val blocks = if (phase == Phase.Taper) 2 else if (targets[w] > 80) 3 else 2
                    val work = if (phase == Phase.Taper) 10 else 15
                    val minutes = 25 + blocks * work + (blocks - 1) * 5
                    val miles = r5(minutes / 60.0 * endurance).coerceAtLeast(10.0)
                    used += ride(d, WorkoutKind.Tempo, miles, endurance, "15 min easy · $blocks × $work min @ ${fmtSpeed(sp.tempo)} mph (sweet spot, comfortably hard) with 5 min easy · 10 min easy")
                } else {
                    val reps = if (lighter) 4 else 5
                    val minutes = 25 + reps * 8
                    val miles = r5(minutes / 60.0 * endurance).coerceAtLeast(10.0)
                    used += ride(d, WorkoutKind.Intervals, miles, endurance, "15 min easy · $reps × 4 min hard (VO2 effort) with 4 min easy · 10 min easy")
                }
            }
            val easyDays = (free - longDay - hard.toSet()).sortedBy { it.value }
            if (easyDays.isNotEmpty()) {
                val each = r5(((targets[w] - used) / easyDays.size).coerceIn(10.0, 35.0))
                easyDays.forEach { ride(it, if (phase == Phase.Taper) WorkoutKind.Recovery else WorkoutKind.Easy, each, if (phase == Phase.Taper) sp.recovery else sp.endurance.start, "$each mi easy spin @ ${fmtSpeed(sp.endurance.start)} mph or slower") }
            }
            val lastTaperWeek = phase == Phase.Taper && phases.getOrNull(w + 1) == Phase.Race
            if (!lastTaperWeek) inputs.strengthDays.sortedBy { it.value }.take(rules.strengthPerWeek).forEach {
                val dt = date(it)
                if (!dt.isBefore(inputs.startDate)) sessions += PlannedSession(dt, Sport.Strength, WorkoutKind.Strength, rules.strengthMin, "", detail = "Strength & mobility · ${rules.strengthMin} min")
            }
            out += PlanWeek(w, wkStart, phase, sessions.filter { it.sport == Sport.Ride }.sumOf { it.durationMin }, lighter, sessions.sortedWith(compareBy({ it.date }, { it.sport == Sport.Strength })))
        }
        val peakMiles = out.maxOf { wk -> weekMiles(wk) }
        return PlanResult.Ok(out) to RidePlanSummary(sp, goalSec, peakLong, peakMiles, warnings)
    }

    fun weekMiles(w: PlanWeek) = w.sessions.filter { it.sport == Sport.Ride && it.kind != WorkoutKind.Race }.sumOf { it.distanceMi ?: 0.0 }
}
