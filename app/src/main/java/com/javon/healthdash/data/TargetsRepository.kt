package com.javon.healthdash.data

import com.javon.healthdash.calc.Targets
import com.javon.healthdash.data.db.AppDao
import com.javon.healthdash.data.db.TargetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One editable target (row in the targets table). */
data class TargetDef(val key: String, val label: String, val unit: String, val default: Double, val group: String)

/** Targets live in the database so they can be edited without code changes (SPEC §5). */
class TargetsRepository(private val dao: AppDao) {

    val defs = listOf(
        TargetDef("tightLow", "Tight range · low", "mg/dL", 70.0, "Glucose"),
        TargetDef("tightHigh", "Tight range · high", "mg/dL", 140.0, "Glucose"),
        TargetDef("veryHigh", "High threshold", "mg/dL", 180.0, "Glucose"),
        TargetDef("tirGoal", "Time in range goal", "%", 85.0, "Glucose"),
        TargetDef("a1cGoal", "A1c / GMI goal", "%", 5.7, "Glucose"),
        TargetDef("cvGoal", "Variability (CV) goal", "% max", 20.0, "Glucose"),
        TargetDef("lowGoal", "Time below 70 goal", "% max", 4.0, "Glucose"),
        TargetDef("fastingGoal", "Fasting glucose goal", "mg/dL max", 100.0, "Glucose"),
        TargetDef("fastingStart", "Fasting window start", "hour (24 h)", 5.0, "Windows"),
        TargetDef("fastingEnd", "Fasting window end", "hour (24 h)", 7.0, "Windows"),
        TargetDef("goodSleepHours", "Good sleep", "hours min", 7.0, "Windows"),
        TargetDef("trainingAfterMin", "Training window after workout", "min", 60.0, "Windows"),
        TargetDef("baselineMin", "Meal baseline", "min before", 15.0, "Windows"),
        TargetDef("minCoveragePct", "Data sufficiency", "% coverage", 70.0, "Detection"),
        TargetDef("walkSteps", "Walk after meal", "steps", 1000.0, "Detection"),
        TargetDef("walkWindowMin", "Walk window", "min after meal", 45.0, "Detection"),
        TargetDef("unloggedRise", "Unlogged meal rise", "mg/dL in 30 min", 30.0, "Detection"),
        TargetDef("stepGoal", "Daily steps goal", "steps", 10000.0, "Detection"),
    )

    val values: Flow<Map<String, Double>> = dao.targets().map { rows ->
        val stored = rows.associate { it.key to it.value }
        defs.associate { it.key to (stored[it.key] ?: it.default) }
    }

    val targets: Flow<Targets> = values.map { toTargets(it) }

    suspend fun set(key: String, value: Double) = dao.upsertTarget(TargetEntity(key, value))

    companion object {
        fun toTargets(v: Map<String, Double>) = Targets(
            tightLow = v["tightLow"] ?: 70.0,
            tightHigh = v["tightHigh"] ?: 140.0,
            veryHigh = v["veryHigh"] ?: 180.0,
            minCoverage = (v["minCoveragePct"] ?: 70.0) / 100.0,
            trainingAfterMin = (v["trainingAfterMin"] ?: 60.0).toInt(),
            baselineMin = (v["baselineMin"] ?: 15.0).toInt(),
            walkSteps = (v["walkSteps"] ?: 1000.0).toLong(),
            walkWindowMin = (v["walkWindowMin"] ?: 45.0).toInt(),
            unloggedRiseMgdl = v["unloggedRise"] ?: 30.0,
            fastingStartMin = ((v["fastingStart"] ?: 5.0) * 60).toInt(),
            fastingEndMin = ((v["fastingEnd"] ?: 7.0) * 60).toInt(),
            goodSleepMin = ((v["goodSleepHours"] ?: 7.0) * 60).toInt(),
        )
    }
}
