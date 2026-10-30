package com.javon.healthdash.data

import com.javon.healthdash.calc.AdjustmentKind
import com.javon.healthdash.calc.Adjustments
import com.javon.healthdash.calc.Completion
import com.javon.healthdash.calc.DoneWorkout
import com.javon.healthdash.calc.FuelTable
import com.javon.healthdash.calc.Intensity
import com.javon.healthdash.calc.PlanBuilder
import com.javon.healthdash.calc.PlanInputs
import com.javon.healthdash.calc.PlanResult
import com.javon.healthdash.calc.PlannedSession
import com.javon.healthdash.calc.RaceType
import com.javon.healthdash.calc.Reading
import com.javon.healthdash.calc.SleepContext
import com.javon.healthdash.calc.SleepSession
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.calc.Targets
import com.javon.healthdash.calc.TimeWindow
import com.javon.healthdash.calc.TrainingRules
import com.javon.healthdash.calc.WorkoutKind
import com.javon.healthdash.data.db.AdjustmentEntity
import com.javon.healthdash.data.db.AppDao
import com.javon.healthdash.data.db.FuelEntity
import com.javon.healthdash.data.db.PlanEntity
import com.javon.healthdash.data.db.PlannedEntity
import com.javon.healthdash.data.db.ReadingEntity
import com.javon.healthdash.data.db.WorkoutEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun PlannedEntity.toSession() = PlannedSession(LocalDate.ofEpochDay(date), Sport.valueOf(sport), WorkoutKind.valueOf(kind), durationMin, note, name, distanceMi, detail)

fun PlanEntity.inputs() = PlanInputs(
    race = RaceType.valueOf(raceType),
    raceDate = LocalDate.ofEpochDay(raceDate),
    startDate = LocalDate.ofEpochDay(startDate),
    goalMin = goalMin,
    currentWeeklyMin = weeklyMin,
    days = days.split(",").filter { it.isNotBlank() }.map { DayOfWeek.of(it.toInt()) }.toSet(),
    longDay = DayOfWeek.of(longDay),
    strengthDays = strengthDays.split(",").filter { it.isNotBlank() }.map { DayOfWeek.of(it.toInt()) }.toSet(),
    distanceMi = distanceMi,
)

/** "Half marathon", or "8.0-mile race" for a custom distance. */
fun PlanEntity.label(): String = RaceType.valueOf(raceType).let { t ->
    if (t.custom && distanceMi != null) "${com.javon.healthdash.ui.Fmt.one(distanceMi)}-mile ${if (t.sport == Sport.Ride) "ride" else "race"}" else t.label
}

fun WorkoutEntity.done() = DoneWorkout(TimeWindow(startMs, endMs), sportOf(exerciseType))

/** Today's planned sessions (any plan, not skipped) and whether each is done — marked by hand or matched to a watch workout. */
fun todaySummary(planned: List<PlannedEntity>, workouts: List<WorkoutEntity>, today: LocalDate, zone: ZoneId): com.javon.healthdash.calc.TodaySummary {
    val done = workouts.map { it.done() }
    return com.javon.healthdash.calc.TodaySummary.of(
        planned.filter { it.date == today.toEpochDay() && it.status != "Dropped" }.map { e ->
            com.javon.healthdash.calc.TodayItem(
                e.id, Sport.valueOf(e.sport), WorkoutKind.valueOf(e.kind), e.durationMin, e.distanceMi, e.name,
                done = e.status == "Done" || Completion.matches(e.toSession(), done, zone) != null,
            )
        },
    )
}

/** Race plans, planned sessions, rule-based adjustments and the fuel log (SPEC §8). */
class TrainingRepository(private val dao: AppDao) {
    val plans = dao.plans()
    val planned = dao.planned()
    val adjustments = dao.adjustments()
    val fuel = dao.fuel()
    private val lock = Mutex()

    /** Saves a generated plan (from [PlanBuilder] or the distance-based [com.javon.healthdash.calc.RunPlanBuilder]). */
    suspend fun create(inputs: PlanInputs, result: PlanResult): PlanResult {
        if (result !is PlanResult.Ok) return result
        val id = dao.insertPlan(
            PlanEntity(
                raceType = inputs.race.name, raceDate = inputs.raceDate.toEpochDay(), startDate = inputs.startDate.toEpochDay(), goalMin = inputs.goalMin,
                days = inputs.days.joinToString(",") { it.value.toString() }, longDay = inputs.longDay.value,
                strengthDays = inputs.strengthDays.joinToString(",") { it.value.toString() }, weeklyMin = inputs.currentWeeklyMin, createdAt = System.currentTimeMillis(), distanceMi = inputs.distanceMi,
            ),
        )
        dao.insertPlanned(result.weeks.flatMap { w ->
            w.sessions.map { s -> PlannedEntity(planId = id, date = s.date.toEpochDay(), sport = s.sport.name, kind = s.kind.name, durationMin = s.durationMin, note = s.note, phase = w.phase.name, week = w.index, name = s.name, distanceMi = s.distanceMi, detail = s.detail) }
        })
        return result
    }

    suspend fun deletePlan(id: Long) = dao.deletePlan(id)

    suspend fun addFuel(workoutId: String, timeMs: Long, label: String, carbsG: Double) = dao.insertFuel(FuelEntity(workoutId = workoutId, timeMs = timeMs, label = label, carbsG = carbsG))
    suspend fun deleteFuel(id: Long) = dao.deleteFuel(id)

    suspend fun addVo2(timeMs: Long, value: Double) =
        dao.upsertReadings(listOf(ReadingEntity("manual-vo2-$timeMs", timeMs, ReadingEntity.VO2_MAX, value, "manual")))

    /**
     * Applies the fixed rules once per session:
     *  - past sessions with no matching workout are marked missed and dropped or moved;
     *  - today's hard session is swapped for easy after short sleep or HRV below baseline.
     * Runs whenever the training screens open; already-adjusted sessions are skipped.
     */
    suspend fun reconcile(
        zone: ZoneId,
        workouts: List<WorkoutEntity>,
        sleep: List<SleepSession>,
        hrv: List<Reading>,
        targets: Targets,
        fuel: FuelTable,
    ): Unit = lock.withLock {
        val today = LocalDate.now(zone)
        val all = dao.plannedNow()
        val handled = dao.adjustmentsNow().filter { it.kind in RULE_KINDS }.map { it.sessionId }.toSet()
        val done = workouts.map { it.done() }
        val now = System.currentTimeMillis()

        for (e in all) {
            if (e.status != "Planned" || e.id in handled) continue
            val s = e.toSession()
            if (!s.date.isBefore(today) || s.date.isBefore(today.minusDays(14))) continue
            if (Completion.matches(s, done, zone) != null) continue
            val upcoming = all.filter { it.planId == e.planId && it.status == "Planned" && it.id != e.id }
            val adj = Adjustments.missed(s, upcoming.map { it.toSession() })
            dao.updatePlanned(e.copy(status = "Missed"))
            when (adj.kind) {
                AdjustmentKind.Moved -> {
                    val target = upcoming.firstOrNull { LocalDate.ofEpochDay(it.date) == adj.to!!.date && WorkoutKind.valueOf(it.kind).intensity == Intensity.Easy && it.sport != Sport.Strength.name }
                    if (target != null) {
                        dao.updatePlanned(target.copy(kind = e.kind, durationMin = e.durationMin, note = e.note))
                        dao.insertAdjustment(AdjustmentEntity(sessionId = e.id, timeMs = now, kind = "Moved", summary = "${s.kind.label} moved to ${dayName(adj.to!!.date)}", reason = adj.reason, originalKind = target.kind, originalDurationMin = target.durationMin, originalNote = target.note))
                    }
                }
                else -> dao.insertAdjustment(AdjustmentEntity(sessionId = e.id, timeMs = now, kind = "Dropped", summary = "Missed: ${s.kind.label} · ${dayName(s.date)}", reason = adj.reason, originalKind = null, originalDurationMin = null, originalNote = null))
            }
        }

        val night = SleepContext.previousNight(today, zone, sleep)
        val sleepMin = SleepContext.totalMinutes(night).takeIf { it > 0 }
        val hrvToday = SleepContext.morningValue(today, zone, hrv)
        val baseline = SleepContext.baseline(today, zone, hrv)
        for (e in all.filter { LocalDate.ofEpochDay(it.date) == today && it.status == "Planned" && it.id !in handled }) {
            val adj = Adjustments.readiness(e.toSession(), sleepMin, hrvToday, baseline, targets, fuel) ?: continue
            val to = adj.to!!
            dao.updatePlanned(e.copy(kind = to.kind.name, durationMin = to.durationMin, note = to.note, status = "Swapped"))
            dao.insertAdjustment(AdjustmentEntity(sessionId = e.id, timeMs = now, kind = "Swapped", summary = "Today's ${WorkoutKind.valueOf(e.kind).label.lowercase()} swapped to easy", reason = adj.reason, originalKind = e.kind, originalDurationMin = e.durationMin, originalNote = e.note))
        }
    }

    /** Marks a session complete by hand (worked out without the watch). Null minutes/miles = as planned. */
    suspend fun markDone(id: Long, minutes: Int?, miles: Double?) {
        val e = dao.plannedNow().firstOrNull { it.id == id } ?: return
        dao.updatePlanned(e.copy(status = "Done", doneMin = minutes, doneMi = miles))
    }

    /** Undoes a manual completion: back to Missed if the missed rule already handled it, else Planned. */
    suspend fun undoDone(id: Long, zone: ZoneId) {
        val e = dao.plannedNow().firstOrNull { it.id == id && it.status == "Done" } ?: return
        val handled = dao.adjustmentsNow().any { it.sessionId == id && (it.kind == "Moved" || it.kind == "Dropped") }
        val past = LocalDate.ofEpochDay(e.date).isBefore(LocalDate.now(zone))
        dao.updatePlanned(e.copy(status = if (handled && past) "Missed" else "Planned", doneMin = null, doneMi = null))
    }

    /** How a completed session felt (RPE 1–10). */
    suspend fun setRpe(id: Long, rpe: Int?) {
        val e = dao.plannedNow().firstOrNull { it.id == id } ?: return
        dao.updatePlanned(e.copy(rpe = rpe))
    }

    /** Eases the next 7 days of runs/rides (not strength, races or recurring sessions) by [factor]. */
    suspend fun ease(planId: Long, from: LocalDate, factor: Double, reason: String) {
        val now = System.currentTimeMillis()
        val rows = dao.plannedNow().filter {
            it.planId == planId && it.status == "Planned" && LocalDate.ofEpochDay(it.date) in from..from.plusDays(6) &&
                it.sport != Sport.Strength.name && WorkoutKind.valueOf(it.kind).let { k -> k != WorkoutKind.Race && !k.recurring }
        }
        rows.forEach { e ->
            dao.updatePlanned(e.copy(distanceMi = e.distanceMi?.let { com.javon.healthdash.calc.Adaptation.ease(it, factor) }, durationMin = (e.durationMin * factor).toInt().coerceAtLeast(10)))
        }
        if (rows.isNotEmpty()) dao.insertAdjustment(AdjustmentEntity(sessionId = rows.first().id, timeMs = now, kind = "Eased", summary = "Next 7 days eased by ${((1 - factor) * 100).toInt()}%", reason = reason, originalKind = null, originalDurationMin = null, originalNote = null))
    }

    /** Moves a session to another day. */
    suspend fun move(id: Long, to: LocalDate) {
        val e = dao.plannedNow().firstOrNull { it.id == id } ?: return
        val from = LocalDate.ofEpochDay(e.date)
        dao.updatePlanned(e.copy(date = to.toEpochDay()))
        dao.insertAdjustment(AdjustmentEntity(sessionId = id, timeMs = System.currentTimeMillis(), kind = "Rescheduled", summary = "${WorkoutKind.valueOf(e.kind).label} moved ${dayName(from)} → ${dayName(to)}", reason = "Moved by you", originalKind = null, originalDurationMin = null, originalNote = null))
    }

    /** Travel / time off: drops planned sessions in [from]..[to]. Returns how many. */
    suspend fun skip(planId: Long, from: LocalDate, to: LocalDate): Int {
        val rows = dao.plannedNow().filter { it.planId == planId && it.status == "Planned" && LocalDate.ofEpochDay(it.date) in from..to }
        rows.forEach { dao.updatePlanned(it.copy(status = "Dropped")) }
        if (rows.isNotEmpty()) dao.insertAdjustment(AdjustmentEntity(sessionId = rows.first().id, timeMs = System.currentTimeMillis(), kind = "Travel", summary = "Time off ${dayName(from)} – ${dayName(to)}", reason = "${rows.size} sessions skipped", originalKind = null, originalDurationMin = null, originalNote = null))
        return rows.size
    }

    /**
     * Rebuilds the rest of a plan from [from] (e.g. after travel) with a freshly generated [result] that starts there.
     * Keeps everything before [from]; week numbers continue from the original start.
     */
    suspend fun rebuildFrom(plan: PlanEntity, from: LocalDate, result: PlanResult.Ok) {
        val offset = java.time.temporal.ChronoUnit.WEEKS.between(PlanBuilder.monday(LocalDate.ofEpochDay(plan.startDate)), PlanBuilder.monday(from)).toInt()
        dao.deletePlannedFrom(plan.id, from.toEpochDay())
        dao.insertPlanned(result.weeks.flatMap { w ->
            w.sessions.filter { !it.date.isBefore(from) }.map { s -> PlannedEntity(planId = plan.id, date = s.date.toEpochDay(), sport = s.sport.name, kind = s.kind.name, durationMin = s.durationMin, note = s.note, phase = w.phase.name, week = w.index + offset, name = s.name, distanceMi = s.distanceMi, detail = s.detail) }
        })
        dao.insertAdjustment(AdjustmentEntity(sessionId = 0, timeMs = System.currentTimeMillis(), kind = "Rebuilt", summary = "Plan rebuilt from ${dayName(from)}", reason = "Regenerated from your current fitness", originalKind = null, originalDurationMin = null, originalNote = null))
    }

    /** "Keep original plan": restores a swapped session. */
    suspend fun undoSwap(adj: AdjustmentEntity) {
        if (adj.kind != "Swapped" || adj.reverted) return
        val e = dao.plannedNow().firstOrNull { it.id == adj.sessionId } ?: return
        dao.updatePlanned(e.copy(kind = adj.originalKind!!, durationMin = adj.originalDurationMin!!, note = adj.originalNote ?: "", status = "Planned"))
        dao.updateAdjustment(adj.copy(reverted = true))
    }

    companion object {
        /** Adjustments made by the missed-session and readiness rules (others — eased, moved by you, travel — don't block them). */
        val RULE_KINDS = setOf("Moved", "Dropped", "Swapped")
        fun dayName(d: LocalDate) = d.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase).take(3) + " " + d.monthValue + "/" + d.dayOfMonth
    }
}
