package com.javon.healthdash.calc

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

// Rule-based training plans (SPEC §8). No AI anywhere in this file.

enum class Sport { Run, Ride, Strength }

enum class Intensity { Easy, Moderate, Hard }

enum class WorkoutKind(val label: String, val intensity: Intensity) {
    Easy("Easy", Intensity.Easy),
    Recovery("Recovery", Intensity.Easy),
    Long("Long", Intensity.Moderate),
    Tempo("Tempo", Intensity.Hard),
    Intervals("Intervals", Intensity.Hard),
    BackToBack("Back-to-back", Intensity.Moderate),
    DoubleDay("Double day", Intensity.Moderate),
    Strength("Strength", Intensity.Easy),
    Race("Race", Intensity.Hard),
    /** A recurring session the user already does (e.g. a weekly run club). */
    ClubEasy("Recurring", Intensity.Easy),
    ClubSteady("Recurring", Intensity.Moderate),
    ClubHard("Recurring", Intensity.Hard);

    val recurring get() = this == ClubEasy || this == ClubSteady || this == ClubHard

    companion object {
        fun recurring(i: Intensity) = when (i) { Intensity.Easy -> ClubEasy; Intensity.Moderate -> ClubSteady; Intensity.Hard -> ClubHard }
    }
}

/**
 * A session the user already does every week at a set distance (SPEC-adjacent: the plan is built
 * around it). It stays on its day, counts toward weekly volume, and a hard one counts as a hard day.
 */
data class FixedSession(
    val day: DayOfWeek,
    val sport: Sport,
    val name: String,
    val distanceMi: Double?,
    val durationMin: Int,
    val intensity: Intensity,
    /** Keep attending during taper and race week (at an easy effort). */
    val keepInTaper: Boolean = true,
)

enum class Phase { Base, Build, Peak, Taper, Race, Recovery }

/**
 * Race catalog. [defaultMi] is the race distance (for the relay: your total leg miles);
 * custom races take their distance from [PlanInputs.distanceMi]. Names are stored in the
 * database, so existing entries must never be renamed.
 */
enum class RaceType(
    val label: String,
    val sport: Sport,
    val defaultMi: Double,
    val defaultGoalMin: Int,
    val custom: Boolean = false,
    val relay: Boolean = false,
) {
    FiveK("5K", Sport.Run, FIVE_K_MI, 28),
    TenK("10K", Sport.Run, TEN_K_MI, 58),
    FifteenK("15K", Sport.Run, 9.32057, 90),
    TenMile("10 miles", Sport.Run, 10.0, 97),
    Half("Half marathon", Sport.Run, HALF_MI, 120),
    Marathon("Marathon", Sport.Run, MARATHON_MI, 240),
    FiftyK("50K ultra", Sport.Run, 31.0686, 330),
    FiftyMile("50-mile ultra", Sport.Run, 50.0, 660),
    Relay200("200-mile relay", Sport.Run, 18.0, 180, relay = true),
    CustomRun("Custom run distance", Sport.Run, 0.0, 0, custom = true),
    Ride25("25-mile ride", Sport.Ride, 25.0, 100),
    Ride50("50-mile ride", Sport.Ride, 50.0, 200),
    MetricCentury("Metric century · 62 mi", Sport.Ride, 62.1371, 250),
    Bike80("80-mile ride", Sport.Ride, 80.0, 330),
    Century("Century · 100 mi", Sport.Ride, 100.0, 420),
    CustomRide("Custom ride distance", Sport.Ride, 0.0, 0, custom = true);

    // Time-based (minutes) builder parameters.
    val peakWeeklyMin get() = if (sport == Sport.Ride) 600 else 480
    val longShare get() = when { sport == Sport.Ride -> 0.40; relay -> 0.25; else -> 0.30 }
    val defaultRaceMin get() = defaultGoalMin.takeIf { it > 0 } ?: 120
}

/** Every rule value is editable in Settings › Training rules. */
data class TrainingRules(
    val maxWeeklyIncrease: Double = 0.10,
    /** Every Nth week is lighter (3 or 4). */
    val lighterEvery: Int = 4,
    val lighterFactor: Double = 0.75,
    /** Max minutes the long session may grow from one full week to the next. */
    val longIncreaseMaxMin: Int = 20,
    val strengthPerWeek: Int = 2,
    val strengthMin: Int = 40,
    val taperWeeksMarathon: Int = 3,
    val taperWeeksBike: Int = 2,
    val taperWeeksRelay: Int = 2,
    val taperWeeksShort: Int = 1,
    val taperWeeksHalf: Int = 2,
    val recoveryWeeks: Int = 2,
) {
    /** Taper length by distance: ≤ 10 mi run or ≤ 50 mi ride → short; half → 2; marathon and up → marathon taper. */
    fun taperWeeks(race: RaceType, distanceMi: Double = race.defaultMi): Int = when {
        race.relay -> taperWeeksRelay
        race.sport == Sport.Ride -> if (distanceMi <= 50.0) taperWeeksShort else taperWeeksBike
        distanceMi <= 10.0 -> taperWeeksShort
        distanceMi <= 13.2 -> taperWeeksHalf
        else -> taperWeeksMarathon
    }
}

data class PlanInputs(
    val race: RaceType,
    val raceDate: LocalDate,
    val startDate: LocalDate,
    /** Goal time in minutes; null = finish. */
    val goalMin: Int?,
    /** Current weekly volume in minutes (auto-filled from recent workouts). */
    val currentWeeklyMin: Int,
    val days: Set<DayOfWeek>,
    val longDay: DayOfWeek,
    val strengthDays: Set<DayOfWeek>,
    val fixed: List<FixedSession> = emptyList(),
    /** Race distance for custom races (otherwise the catalog distance is used). */
    val distanceMi: Double? = null,
) {
    val raceMi: Double get() = distanceMi?.takeIf { it > 0 } ?: race.defaultMi
}

data class PlannedSession(
    val date: LocalDate,
    val sport: Sport,
    val kind: WorkoutKind,
    val durationMin: Int,
    val note: String = "",
    /** Set for recurring sessions ("Run club"). */
    val name: String? = null,
    val distanceMi: Double? = null,
    /** Workout structure and target paces, e.g. "1.5 mi easy · 6 × 800 m @ 7:33/mi · 1 mi easy". */
    val detail: String? = null,
) {
    val intensity get() = kind.intensity
}

data class PlanWeek(val index: Int, val start: LocalDate, val phase: Phase, val targetMin: Int, val lighter: Boolean, val sessions: List<PlannedSession>) {
    /** Endurance minutes (strength excluded). */
    val enduranceMin get() = sessions.filter { it.sport != Sport.Strength }.sumOf { it.durationMin }
}

sealed interface PlanResult {
    data class Ok(val weeks: List<PlanWeek>) : PlanResult
    data class Invalid(val reason: String) : PlanResult
}

object PlanBuilder {

    private val hardPreference = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Phases from the first week to race week: base → build → peak → taper → race. */
    fun phases(weeks: Int, taper: Int): List<Phase> {
        val race = 1
        val peak = minOf(2, maxOf(0, weeks - taper - race - 1))
        val rest = weeks - taper - race - peak
        val base = (rest * 0.4).roundToInt().coerceAtLeast(if (rest > 0) 1 else 0)
        val build = rest - base
        return List(base) { Phase.Base } + List(build) { Phase.Build } + List(peak) { Phase.Peak } + List(taper) { Phase.Taper } + Phase.Race
    }

    /**
     * Weekly volume: grows by at most [TrainingRules.maxWeeklyIncrease] per full week up to the race's
     * peak volume; every [TrainingRules.lighterEvery]th week is [TrainingRules.lighterFactor] of the last
     * full week; taper steps down; race week is light.
     */
    fun volumes(phases: List<Phase>, startMin: Int, race: RaceType, rules: TrainingRules): List<Pair<Int, Boolean>> {
        var lastFull = startMin.coerceAtLeast(60).toDouble()
        val out = mutableListOf<Pair<Int, Boolean>>()
        val taperFactors = when (phases.count { it == Phase.Taper }) {
            0 -> emptyList()
            1 -> listOf(0.6)
            2 -> listOf(0.7, 0.5)
            else -> listOf(0.75, 0.6, 0.45)
        }
        var taperIdx = 0
        phases.forEachIndexed { i, ph ->
            when (ph) {
                Phase.Taper -> out += (lastFull * taperFactors.getOrElse(taperIdx++) { 0.45 }).roundToInt() to false
                Phase.Race -> out += (lastFull * 0.35).roundToInt() to false
                else -> {
                    val lighter = (i + 1) % rules.lighterEvery == 0
                    if (lighter) {
                        out += (lastFull * rules.lighterFactor).roundToInt() to true
                    } else {
                        if (i > 0) lastFull = minOf(race.peakWeeklyMin.toDouble().coerceAtLeast(lastFull), lastFull * (1 + rules.maxWeeklyIncrease))
                        out += lastFull.roundToInt() to false
                    }
                }
            }
        }
        return out
    }

    /** Hard days: not the long day, not next to the long day or [avoidNear] (e.g. a hard run club), not next to each other. */
    fun hardDays(available: Set<DayOfWeek>, longDay: DayOfWeek, count: Int, avoidNear: Set<DayOfWeek> = emptySet()): List<DayOfWeek> {
        val chosen = mutableListOf<DayOfWeek>()
        fun adjacent(a: DayOfWeek, b: DayOfWeek) = a == b || a.plus(1) == b || a.minus(1) == b
        for (d in hardPreference) {
            if (chosen.size >= count) break
            if (d !in available || adjacent(d, longDay) || avoidNear.any { adjacent(it, d) }) continue
            if (chosen.any { adjacent(it, d) }) continue
            chosen += d
        }
        return chosen
    }

    fun generate(inputs: PlanInputs, rules: TrainingRules = TrainingRules(), fuel: FuelTable = FuelTable.DEFAULT): PlanResult {
        val first = monday(inputs.startDate)
        val raceWeek = monday(inputs.raceDate)
        val weeks = ChronoUnit.WEEKS.between(first, raceWeek).toInt() + 1
        val taper = rules.taperWeeks(inputs.race, inputs.raceMi)
        if (inputs.raceDate.isBefore(inputs.startDate)) return PlanResult.Invalid("Race date is before the plan start")
        if (weeks < taper + 3) return PlanResult.Invalid("Need at least ${taper + 3} weeks before the race for base, taper and race week")
        val fixedDays = inputs.fixed.map { it.day }.toSet()
        val days = inputs.days + fixedDays
        if (days.size < 3) return PlanResult.Invalid("Pick at least 3 training days")
        if ((days - fixedDays).isEmpty()) return PlanResult.Invalid("Leave at least one day free for the long session")

        val phases = phases(weeks, taper)
        val vols = volumes(phases, inputs.currentWeeklyMin, inputs.race, rules)
        val sport = inputs.race.sport
        var prevLong = 0
        val out = mutableListOf<PlanWeek>()

        for (w in 0 until weeks) {
            val start = first.plusWeeks(w.toLong())
            val phase = phases[w]
            val (vol, lighter) = vols[w]
            fun date(d: DayOfWeek) = start.plusDays((d.value - 1).toLong())
            val sessions = mutableListOf<PlannedSession>()
            fun add(d: DayOfWeek, kind: WorkoutKind, min: Int, s: Sport = sport, name: String? = null, distanceMi: Double? = null) {
                val dt = date(d)
                if (dt.isBefore(inputs.startDate)) return
                sessions += PlannedSession(dt, s, kind, min, if (s == Sport.Strength) "" else fuel.note(min, kind.intensity), name, distanceMi)
            }
            /** Recurring sessions; in taper and race week they stay at an easy effort (or are skipped if the user said so). */
            fun addFixed(onlyBefore: java.time.LocalDate? = null): List<FixedSession> {
                val easyOnly = phase == Phase.Taper || phase == Phase.Race
                val kept = inputs.fixed.filter { f ->
                    (!easyOnly || f.keepInTaper) && (onlyBefore == null || !date(f.day).isAfter(onlyBefore))
                }
                kept.forEach { f ->
                    add(f.day, WorkoutKind.recurring(if (easyOnly) Intensity.Easy else f.intensity), f.durationMin, f.sport, f.name, f.distanceMi)
                }
                return kept
            }

            if (phase == Phase.Race) {
                val rd = inputs.raceDate.dayOfWeek
                // Recurring sessions only if at least 2 days before the race.
                val club = addFixed(onlyBefore = inputs.raceDate.minusDays(2))
                val shake = days.filter { it != rd && it.value < rd.value && it !in fixedDays }.sortedBy { it.value }.takeLast(2 - club.size.coerceAtMost(1))
                shake.forEach { add(it, WorkoutKind.Easy, (vol / 2.0).roundToInt().coerceIn(20, 45)) }
                sessions += PlannedSession(inputs.raceDate, sport, WorkoutKind.Race, inputs.goalMin ?: inputs.race.defaultRaceMin, fuel.note(inputs.goalMin ?: inputs.race.defaultRaceMin, Intensity.Hard))
                out += PlanWeek(w, start, phase, vol, false, sessions.sortedBy { it.date })
                continue
            }

            val club = addFixed()
            val clubMin = club.filter { it.sport != Sport.Strength }.sumOf { it.durationMin }
            val hardClubDays = if (phase == Phase.Taper) emptySet() else club.filter { it.intensity == Intensity.Hard }.map { it.day }.toSet()
            val free = days - fixedDays
            val longDay = if (inputs.longDay in free) inputs.longDay else free.maxBy { it.value }
            val longTarget = (vol * inputs.race.longShare).roundToInt()
            val long = when {
                lighter || phase == Phase.Taper || prevLong == 0 -> longTarget
                else -> minOf(longTarget, prevLong + rules.longIncreaseMaxMin)
            }
            if (!lighter && phase != Phase.Taper) prevLong = long
            add(longDay, WorkoutKind.Long, long)

            val hardCount = when (phase) { Phase.Base -> 1; Phase.Build, Phase.Peak -> if (lighter) 1 else 2; else -> 1 }
            // A hard recurring session counts as one of the week's hard days.
            val hard = hardDays(free - longDay, longDay, (hardCount - hardClubDays.size).coerceAtLeast(0), hardClubDays)
            var used = long + clubMin
            hard.forEachIndexed { i, d ->
                val kind = if (i == 0) WorkoutKind.Tempo else WorkoutKind.Intervals
                val min = (vol * (if (kind == WorkoutKind.Tempo) 0.15 else 0.12)).roundToInt().coerceIn(25, 75)
                add(d, kind, min)
                used += min
            }

            var easyDays = (free - longDay - hard.toSet()).sortedBy { it.value }
            // Relay: back-to-back long sessions and one double day in peak weeks.
            if (inputs.race.relay && phase == Phase.Peak) {
                val next = longDay.plus(1)
                if (next in easyDays) {
                    val b2b = (long * 0.6).roundToInt()
                    add(next, WorkoutKind.BackToBack, b2b)
                    used += b2b
                    easyDays = easyDays - next
                }
                easyDays.firstOrNull()?.let { d ->
                    val dd = (vol * 0.12).roundToInt().coerceIn(40, 90)
                    add(d, WorkoutKind.DoubleDay, dd)
                    used += dd
                    easyDays = easyDays - d
                }
            }
            val remaining = vol - used
            val perEasy = if (easyDays.isEmpty()) 0 else remaining / easyDays.size
            if (perEasy >= 20) {
                easyDays.forEach { add(it, if (phase == Phase.Taper) WorkoutKind.Recovery else WorkoutKind.Easy, perEasy.coerceAtMost(90)) }
            } else if (remaining >= 20 && easyDays.isNotEmpty()) {
                add(easyDays.first(), WorkoutKind.Easy, remaining.coerceAtMost(90))
            }

            val lastTaperWeek = phase == Phase.Taper && phases.getOrNull(w + 1) == Phase.Race
            if (!lastTaperWeek) {
                inputs.strengthDays.sortedBy { it.value }.take(rules.strengthPerWeek).forEach { add(it, WorkoutKind.Strength, rules.strengthMin, Sport.Strength) }
            }
            out += PlanWeek(w, start, phase, vol, lighter, sessions.sortedWith(compareBy({ it.date }, { it.sport == Sport.Strength })))
        }
        return PlanResult.Ok(out)
    }

    /** Start date for the next race in a chain: the Monday after the previous race plus recovery weeks. */
    fun nextStart(previousRace: LocalDate, rules: TrainingRules = TrainingRules()): LocalDate =
        monday(previousRace).plusWeeks(1L + rules.recoveryWeeks)
}

// ---------------- Adjustments ----------------

enum class AdjustmentKind { SwapToEasy, Dropped, Moved }

data class Adjustment(val kind: AdjustmentKind, val from: PlannedSession, val to: PlannedSession?, val reason: String)

object Adjustments {

    /**
     * Poor sleep or HRV below baseline → swap that day's hard session for an easy one
     * at 75% of the duration (SPEC §8).
     */
    fun readiness(session: PlannedSession, sleepMin: Int?, hrv: Double?, hrvBaseline: Double?, targets: Targets = Targets(), fuel: FuelTable = FuelTable.DEFAULT): Adjustment? {
        if (session.intensity != Intensity.Hard || session.kind == WorkoutKind.Race) return null
        val shortSleep = sleepMin != null && sleepMin < targets.goodSleepMin
        val lowHrv = hrv != null && hrvBaseline != null && hrv < hrvBaseline
        if (!shortSleep && !lowHrv) return null
        val reasons = buildList {
            if (lowHrv) add("HRV ${hrv!!.roundToInt()} ms is below your ${hrvBaseline!!.roundToInt()} ms baseline")
            if (shortSleep) add("sleep was ${sleepMin!! / 60} h ${sleepMin % 60} m")
        }
        if (session.kind.recurring) {
            // A set-distance session with a group: still go, at an easy effort.
            return Adjustment(
                AdjustmentKind.SwapToEasy, session,
                session.copy(kind = WorkoutKind.ClubEasy, note = fuel.note(session.durationMin, Intensity.Easy)),
                reasons.joinToString(" and ").replaceFirstChar(Char::uppercase) + " — go, but run it at an easy effort",
            )
        }
        val easyMin = (session.durationMin * 0.75).roundToInt()
        return Adjustment(
            AdjustmentKind.SwapToEasy, session,
            session.copy(kind = WorkoutKind.Easy, durationMin = easyMin, note = fuel.note(easyMin, Intensity.Easy)),
            reasons.joinToString(" and ").replaceFirstChar(Char::uppercase),
        )
    }

    /**
     * Missed sessions (SPEC §8, fixed rules):
     *  - easy, recovery and strength sessions are dropped (not made up);
     *  - a hard session is dropped if another hard session is within 2 days, otherwise it replaces
     *    the next easy session within 3 days that is not next to a hard or long day;
     *  - a long session moves to the next easy day within 2 days if neither neighbour is hard, else it is dropped.
     */
    fun missed(missed: PlannedSession, upcoming: List<PlannedSession>): Adjustment {
        val after = upcoming.filter { it.date.isAfter(missed.date) }.sortedBy { it.date }
        fun within(days: Long) = after.filter { !it.date.isAfter(missed.date.plusDays(days)) && it.sport != Sport.Strength }
        fun hardNear(d: LocalDate) = after.any { (it.intensity == Intensity.Hard || it.kind == WorkoutKind.Long) && kotlin.math.abs(ChronoUnit.DAYS.between(it.date, d)) <= 1 && it.date != d }
        return when {
            missed.kind.recurring ->
                Adjustment(AdjustmentKind.Dropped, missed, null, "Recurring sessions keep their own day — nothing is moved")
            missed.sport == Sport.Strength || missed.intensity == Intensity.Easy ->
                Adjustment(AdjustmentKind.Dropped, missed, null, "Easy and strength sessions are not made up")
            missed.kind == WorkoutKind.Long -> {
                val target = within(2).firstOrNull { it.intensity == Intensity.Easy && !hardNear(it.date) }
                if (target == null) Adjustment(AdjustmentKind.Dropped, missed, null, "No easy day in the next 2 days without a hard day next to it")
                else Adjustment(AdjustmentKind.Moved, missed, missed.copy(date = target.date), "Moved to ${target.date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}, replacing an easy session")
            }
            within(2).any { it.intensity == Intensity.Hard } ->
                Adjustment(AdjustmentKind.Dropped, missed, null, "Another hard session is within 2 days — hard days are not stacked")
            else -> {
                val target = within(3).firstOrNull { it.intensity == Intensity.Easy && !hardNear(it.date) }
                if (target == null) Adjustment(AdjustmentKind.Dropped, missed, null, "No easy day in the next 3 days to swap with")
                else Adjustment(AdjustmentKind.Moved, missed, missed.copy(date = target.date), "Moved to ${target.date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}, replacing an easy session")
            }
        }
    }
}

// ---------------- Fueling ----------------

enum class DurationBucket(val label: String) { Under60("< 60 min"), To90("60–90 min"), To150("90–150 min"), Over150("> 150 min") }

data class FuelRange(val lo: Int, val hi: Int)

/** Carbs-per-hour targets by duration and intensity — an editable table (SPEC §8). */
data class FuelTable(val rows: Map<DurationBucket, Map<Intensity, FuelRange>>) {

    fun bucket(min: Int) = when {
        min < 60 -> DurationBucket.Under60
        min < 90 -> DurationBucket.To90
        min <= 150 -> DurationBucket.To150
        else -> DurationBucket.Over150
    }

    fun range(min: Int, intensity: Intensity): FuelRange = rows.getValue(bucket(min)).getValue(intensity)

    /** Total grams for the session; the first 45 minutes are unfueled for sessions of 90 min or more. */
    fun total(min: Int, intensity: Intensity): FuelRange {
        val r = range(min, intensity)
        val hours = (if (min >= 90) min - 45 else min) / 60.0
        return FuelRange((r.lo * hours).roundToInt(), (r.hi * hours).roundToInt())
    }

    fun note(min: Int, intensity: Intensity): String {
        val r = range(min, intensity)
        if (r.hi == 0) return "Under ${if (min < 60) 60 else min} min: water only."
        val rate = if (r.lo == r.hi) "${r.hi} g" else "${r.lo}–${r.hi} g"
        return "Fuel $rate carbs/h" + if (min >= 90) " after the first 45 min." else "."
    }

    companion object {
        val DEFAULT = FuelTable(
            mapOf(
                DurationBucket.Under60 to mapOf(Intensity.Easy to FuelRange(0, 0), Intensity.Moderate to FuelRange(0, 30), Intensity.Hard to FuelRange(30, 30)),
                DurationBucket.To90 to mapOf(Intensity.Easy to FuelRange(30, 30), Intensity.Moderate to FuelRange(30, 60), Intensity.Hard to FuelRange(60, 60)),
                DurationBucket.To150 to mapOf(Intensity.Easy to FuelRange(45, 45), Intensity.Moderate to FuelRange(60, 80), Intensity.Hard to FuelRange(80, 80)),
                DurationBucket.Over150 to mapOf(Intensity.Easy to FuelRange(60, 60), Intensity.Moderate to FuelRange(80, 90), Intensity.Hard to FuelRange(90, 90)),
            ),
        )
    }
}

enum class FuelStatus { Below, Within, Above }

data class FuelReview(
    val target: FuelRange,
    val takenG: Double,
    val status: FuelStatus,
    val lowest: Double?,
    val lowsBelow70: Int,
    val coverage: Double,
)

object Fueling {
    /** After a workout: fuel taken vs target, and the glucose curve during the workout (SPEC §8). */
    fun review(window: TimeWindow, intensity: Intensity, takenG: Double, points: List<GlucosePoint>, table: FuelTable = FuelTable.DEFAULT, targets: Targets = Targets()): FuelReview {
        val min = (window.durationMs / MINUTE_MS).toInt()
        val t = table.total(min, intensity)
        val inW = points.filter { it.timeMs in window }
        val status = when {
            takenG < t.lo -> FuelStatus.Below
            takenG > t.hi * 1.15 -> FuelStatus.Above
            else -> FuelStatus.Within
        }
        return FuelReview(t, takenG, status, inW.minOfOrNull { it.mgdl }, inW.count { it.mgdl < targets.tightLow }, GlucoseStats.coverage(inW, window, targets.cgmIntervalMin))
    }
}

// ---------------- Zones & completion ----------------

data class HrZone(val name: String, val low: Int, val high: Int)

object Zones {
    /** Heart-rate reserve (Karvonen) zones from resting and max HR observed in recent data. */
    fun heartRate(resting: Double, max: Double): List<HrZone> {
        val reserve = max - resting
        fun at(p: Double) = (resting + reserve * p).roundToInt()
        return listOf(
            HrZone("Z1", at(0.5), at(0.6)), HrZone("Z2", at(0.6), at(0.7)), HrZone("Z3", at(0.7), at(0.8)),
            HrZone("Z4", at(0.8), at(0.9)), HrZone("Z5", at(0.9), max.roundToInt()),
        )
    }

    fun forKind(kind: WorkoutKind, zones: List<HrZone>): String? {
        if (zones.size < 5) return null
        return when (kind) {
            WorkoutKind.Recovery -> "Z1 · HR ${zones[0].low}–${zones[0].high}"
            WorkoutKind.Easy, WorkoutKind.Long, WorkoutKind.BackToBack, WorkoutKind.DoubleDay -> "Z2 · HR ${zones[1].low}–${zones[1].high}"
            WorkoutKind.Tempo -> "Z3–Z4 · HR ${zones[2].low}–${zones[3].high}"
            WorkoutKind.Intervals -> "Z4–Z5 · HR ${zones[3].low}–${zones[4].high}"
            WorkoutKind.Race -> "Z3 · HR ${zones[2].low}–${zones[2].high}"
            WorkoutKind.ClubEasy -> "Z2 · HR ${zones[1].low}–${zones[1].high}"
            WorkoutKind.ClubSteady -> "Z3 · HR ${zones[2].low}–${zones[2].high}"
            WorkoutKind.ClubHard -> "Z3–Z4 · HR ${zones[2].low}–${zones[3].high}"
            WorkoutKind.Strength -> null
        }
    }
}

data class DoneWorkout(val window: TimeWindow, val sport: Sport?)

/** Rides as cross-training for run plans. */
object CrossTraining {
    /** Only easy and recovery runs can be swapped for a ride; long runs and quality sessions stay run-only. */
    val SWAPPABLE = setOf(WorkoutKind.Easy, WorkoutKind.Recovery)

    fun canSwap(sport: Sport, kind: WorkoutKind) = sport == Sport.Run && kind in SWAPPABLE

    /**
     * Same sport and at least half the planned time; or, for an easy/recovery run, a ride at least as long
     * as the planned run (minute for minute a ride is easier, so it needs the full time).
     */
    fun counts(sport: Sport, kind: WorkoutKind, plannedMin: Int, doneSport: Sport?, doneMin: Int): Boolean =
        if (doneSport == null || doneSport == sport) doneMin * 2 >= plannedMin
        else doneSport == Sport.Ride && canSwap(sport, kind) && doneMin >= plannedMin
}

object Completion {
    /** A planned session counts as done per [CrossTraining.counts] for a workout that day. */
    fun matches(session: PlannedSession, done: List<DoneWorkout>, zone: java.time.ZoneId): DoneWorkout? =
        done.firstOrNull { w ->
            localDateOf(w.window.startMs, zone) == session.date &&
                CrossTraining.counts(session.sport, session.kind, session.durationMin, w.sport, (w.window.durationMs / MINUTE_MS).toInt())
        }

    /** Weekly endurance minutes actually done, by Monday of each week. */
    fun weeklyMinutes(done: List<DoneWorkout>, zone: java.time.ZoneId): Map<LocalDate, Int> =
        done.filter { it.sport != Sport.Strength }
            .groupBy { PlanBuilder.monday(localDateOf(it.window.startMs, zone)) }
            .mapValues { (_, l) -> (l.sumOf { it.window.durationMs } / MINUTE_MS).toInt() }
}
