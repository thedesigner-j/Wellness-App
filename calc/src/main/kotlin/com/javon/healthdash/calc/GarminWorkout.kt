package com.javon.healthdash.calc

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Watch workouts from planned sessions: pace targets, Garmin Connect workout JSON (dry-run) and a .FIT workout
// file to copy onto the watch. Fixed rules, no AI.
//
// JSON field names follow Garmin Connect's workout-service format as used by open-source clients
// (python-garminconnect workout.py, fitlek). Pace targets go ON THE STEP as targetValueOne/targetValueTwo in
// m/s — never inside targetType (Garmin silently drops nested values).

/** A pace window in seconds per mile; [fast] < [slow]. */
data class PaceWindow(val fast: Int, val slow: Int) {
    fun widen(sec: Int) = PaceWindow(fast - sec, slow + sec)
    fun heat(fraction: Double) = PaceWindow((fast * (1 + fraction)).roundToInt(), (slow * (1 + fraction)).roundToInt())
}

enum class StepKind(val connectKey: String, val connectId: Int, val fitIntensity: Int) {
    Warmup("warmup", 1, 2), Cooldown("cooldown", 2, 3), Interval("interval", 3, 0), Recovery("recovery", 4, 4), Main("interval", 3, 0)
}

sealed interface WatchStep
/** A step that ends after [meters] or [seconds]; no target when [pace] is null. */
data class RunStep(val kind: StepKind, val name: String, val meters: Double? = null, val seconds: Int? = null, val pace: PaceWindow? = null) : WatchStep
data class RepeatGroup(val times: Int, val steps: List<RunStep>) : WatchStep

data class WatchWorkout(val name: String, val date: LocalDate, val steps: List<WatchStep>, val sessionId: Long? = null)

data class TargetInputs(
    val goalSec: Int?,
    val projectedSec: Int?,
    val lowSec: Int?,
    val highSec: Int?,
    /** Threshold pace from the latest 30-min test, s/mi. */
    val thresholdPaceSec: Int?,
    /** Forecast temperature at the usual run time. */
    val forecastF: Double?,
)

object WatchTargets {
    const val EASY_FROM_MP = 75
    const val EASY_TO_MP = 105
    const val EASY_HALF_WIDTH = 15
    const val QUALITY_HALF_WIDTH = 8
    /** Long runs get a marathon-pace finish from plan week 7. */
    const val MP_FINISH_FROM_WEEK = 7

    fun mps(secPerMile: Int) = METERS_PER_MI / secPerMile
    fun meters(mi: Double) = mi * METERS_PER_MI

    /** Goal marathon pace if the goal is inside the 80% range, otherwise the projected pace. */
    fun mpPace(t: TargetInputs, raceMi: Double = MARATHON_MI): Int? {
        val goalIn = t.goalSec != null && t.lowSec != null && t.highSec != null && t.goalSec in t.lowSec..t.highSec
        val sec = if (goalIn) t.goalSec else t.projectedSec ?: t.goalSec
        return sec?.let { (it / raceMi).roundToInt() }
    }

    /** Easy / long: goal MP + 75 to + 105 s/mi (a ±15 s window), slowed for heat. */
    fun easy(t: TargetInputs): PaceWindow? = t.goalSec?.let { g ->
        val mp = (g / MARATHON_MI).roundToInt()
        PaceWindow(mp + EASY_FROM_MP, mp + EASY_TO_MP).heat(RacePrediction.heatFraction(t.forecastF))
    }

    fun mp(t: TargetInputs): PaceWindow? = mpPace(t)?.let { PaceWindow(it - QUALITY_HALF_WIDTH, it + QUALITY_HALF_WIDTH).heat(RacePrediction.heatFraction(t.forecastF)) }

    fun tempo(t: TargetInputs): PaceWindow? = t.thresholdPaceSec?.let { PaceWindow(it - QUALITY_HALF_WIDTH, it + QUALITY_HALF_WIDTH).heat(RacePrediction.heatFraction(t.forecastF)) }

    /** 800 m repeats at the 5K equivalent of the projection (Riegel 1.06). */
    fun interval(t: TargetInputs): PaceWindow? = (t.projectedSec ?: t.goalSec)?.let {
        val p = (RacePrediction.riegel(it.toDouble(), MARATHON_MI, FIVE_K_MI, 1.06) / FIVE_K_MI).roundToInt()
        PaceWindow(p - QUALITY_HALF_WIDTH, p + QUALITY_HALF_WIDTH).heat(RacePrediction.heatFraction(t.forecastF))
    }

    /** Final 2–4 mi at MP for long runs from week 7 (about 20% of the run). */
    fun mpFinishMi(distanceMi: Double, planWeek: Int) = if (planWeek + 1 < MP_FINISH_FROM_WEEK) 0.0 else (distanceMi * 0.2).roundToInt().coerceIn(2, 4).toDouble()

    /** Watch workout for a planned run; null for strength or races. */
    fun build(sessionId: Long?, date: LocalDate, kind: WorkoutKind, distanceMi: Double?, planWeek: Int, t: TargetInputs, threshold30Test: Boolean = false): WatchWorkout? {
        val easy = easy(t)
        if (threshold30Test) return WatchWorkout("Threshold test 30 min", date, listOf(
            RunStep(StepKind.Warmup, "Warm up easy", seconds = 15 * 60, pace = easy),
            RunStep(StepKind.Interval, "Hard 10 min", seconds = 10 * 60),
            RunStep(StepKind.Interval, "Hard 20 min", seconds = 20 * 60),
            RunStep(StepKind.Cooldown, "Cool down", seconds = 10 * 60),
        ), sessionId)
        val mi = distanceMi ?: return null
        val warm = 1.5; val cool = 1.0
        return when (kind) {
            WorkoutKind.Easy, WorkoutKind.Recovery, WorkoutKind.DoubleDay, WorkoutKind.ClubEasy -> WatchWorkout("Easy ${TodaySummary.mi(mi)} mi", date, listOf(
                RunStep(StepKind.Warmup, "Ease in", meters = meters(minOf(0.5, mi / 4))),
                RunStep(StepKind.Main, "Easy", meters = meters(mi - minOf(0.5, mi / 4)), pace = easy),
            ), sessionId)
            WorkoutKind.Long, WorkoutKind.BackToBack -> {
                val mpMi = if (kind == WorkoutKind.Long) mpFinishMi(mi, planWeek) else 0.0
                val steps = mutableListOf<RunStep>(
                    RunStep(StepKind.Warmup, "Ease in", meters = meters(1.0)),
                    RunStep(StepKind.Main, "Long easy", meters = meters(mi - 1.0 - mpMi), pace = easy),
                )
                if (mpMi > 0) steps += RunStep(StepKind.Interval, "Marathon pace", meters = meters(mpMi), pace = mp(t))
                WatchWorkout("Long ${TodaySummary.mi(mi)} mi", date, steps, sessionId)
            }
            WorkoutKind.Tempo -> WatchWorkout("Tempo ${TodaySummary.mi(mi)} mi", date, listOf(
                RunStep(StepKind.Warmup, "Warm up", meters = meters(warm), pace = easy),
                RunStep(StepKind.Interval, "Tempo", meters = meters((mi - warm - cool).coerceAtLeast(1.0)), pace = tempo(t)),
                RunStep(StepKind.Cooldown, "Cool down", meters = meters(cool)),
            ), sessionId)
            WorkoutKind.Intervals -> {
                val reps = ((mi - warm - cool) / 0.75).roundToInt().coerceIn(3, 10)
                WatchWorkout("$reps × 800 m", date, listOf(
                    RunStep(StepKind.Warmup, "Warm up", meters = meters(warm), pace = easy),
                    RepeatGroup(reps, listOf(RunStep(StepKind.Interval, "800 m", meters = 800.0, pace = interval(t)), RunStep(StepKind.Recovery, "Jog 400 m", meters = 400.0))),
                    RunStep(StepKind.Cooldown, "Cool down", meters = meters(cool)),
                ), sessionId)
            }
            else -> null
        }
    }
}

object GarminConnectJson {
    private fun sport() = buildJsonObject { put("sportTypeId", 1); put("sportTypeKey", "running") }

    private fun step(s: RunStep, order: Int): JsonObject = buildJsonObject {
        put("type", "ExecutableStepDTO")
        put("stepOrder", order)
        put("stepType", buildJsonObject { put("stepTypeId", s.kind.connectId); put("stepTypeKey", s.kind.connectKey) })
        put("description", s.name)
        if (s.meters != null) {
            put("endCondition", buildJsonObject { put("conditionTypeId", 3); put("conditionTypeKey", "distance") })
            put("endConditionValue", (s.meters * 10).roundToLong() / 10.0)
        } else {
            put("endCondition", buildJsonObject { put("conditionTypeId", 2); put("conditionTypeKey", "time") })
            put("endConditionValue", s.seconds ?: 0)
        }
        // Target VALUES live on the step; targetType only names the type.
        if (s.pace != null) {
            put("targetType", buildJsonObject { put("workoutTargetTypeId", 6); put("workoutTargetTypeKey", "pace.zone") })
            // Garmin's own UI payload lists the faster pace (higher m/s) first.
            put("targetValueOne", round3(WatchTargets.mps(s.pace.fast)))
            put("targetValueTwo", round3(WatchTargets.mps(s.pace.slow)))
        } else {
            put("targetType", buildJsonObject { put("workoutTargetTypeId", 1); put("workoutTargetTypeKey", "no.target") })
            put("targetValueOne", JsonNull)
            put("targetValueTwo", JsonNull)
        }
    }

    private fun round3(x: Double) = (x * 1000).roundToLong() / 1000.0

    fun json(w: WatchWorkout): JsonObject {
        var order = 0
        val steps = w.steps.map { s ->
            when (s) {
                is RunStep -> step(s, ++order)
                is RepeatGroup -> {
                    val groupOrder = ++order
                    buildJsonObject {
                        put("type", "RepeatGroupDTO")
                        put("stepOrder", groupOrder)
                        put("stepType", buildJsonObject { put("stepTypeId", 6); put("stepTypeKey", "repeat") })
                        put("numberOfIterations", s.times)
                        put("endCondition", buildJsonObject { put("conditionTypeId", 7); put("conditionTypeKey", "iterations") })
                        put("endConditionValue", s.times.toDouble())
                        put("workoutSteps", JsonArray(s.steps.map { step(it, ++order) }))
                    }
                }
            }
        }
        return buildJsonObject {
            put("workoutName", w.name)
            put("description", "Planned ${w.date}")
            put("sportType", sport())
            put("workoutSegments", JsonArray(listOf(buildJsonObject {
                put("segmentOrder", 1)
                put("sportType", sport())
                put("workoutSteps", JsonArray(steps))
            })))
        }
    }

    private val pretty = kotlinx.serialization.json.Json { prettyPrint = true }
    fun pretty(w: WatchWorkout): String = pretty.encodeToString(JsonElement.serializer(), json(w))
}

/** Encodes a workout as a FIT workout file (copy to GARMIN/NewFiles on the watch). */
object FitWorkout {
    private const val FIT_EPOCH_S = 631065600L // 1989-12-31T00:00:00Z

    private class Field(val num: Int, val size: Int, val baseType: Int)

    private fun crc(bytes: ByteArray): Int {
        val table = intArrayOf(0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401, 0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400)
        var c = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            var tmp = table[c and 0xF]; c = (c shr 4) and 0x0FFF; c = c xor tmp xor table[v and 0xF]
            tmp = table[c and 0xF]; c = (c shr 4) and 0x0FFF; c = c xor tmp xor table[(v shr 4) and 0xF]
        }
        return c
    }

    private class Out {
        val b = ByteArrayOutputStream()
        fun u8(v: Int) = b.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
        fun str(s: String, size: Int) { val bytes = s.toByteArray(Charsets.UTF_8).take(size - 1); bytes.forEach { u8(it.toInt()) }; repeat(size - bytes.size) { u8(0) } }
    }

    private fun Out.define(local: Int, global: Int, fields: List<Field>) {
        u8(0x40 or local); u8(0); u8(0); u16(global); u8(fields.size)
        fields.forEach { u8(it.num); u8(it.size); u8(it.baseType) }
    }

    private const val ENUM = 0x00
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C
    private const val STRING = 0x07
    private const val INVALID32 = 0xFFFFFFFFL

    /** Flattened FIT steps (repeat groups become a "repeat until steps complete" step). */
    data class FitStep(val name: String, val durationType: Int, val durationValue: Long, val targetType: Int, val targetValue: Long, val low: Long, val high: Long, val intensity: Int)

    fun steps(w: WatchWorkout): List<FitStep> {
        val out = mutableListOf<FitStep>()
        fun run(s: RunStep) {
            val (dType, dVal) = when {
                s.meters != null -> 1 to (s.meters * 100).roundToLong()       // distance, cm
                s.seconds != null -> 0 to s.seconds * 1000L                   // time, ms
                else -> 5 to INVALID32                                        // open
            }
            out += if (s.pace != null) FitStep(s.name, dType, dVal, 0, 0, (WatchTargets.mps(s.pace.slow) * 1000).roundToLong(), (WatchTargets.mps(s.pace.fast) * 1000).roundToLong(), s.kind.fitIntensity)
            else FitStep(s.name, dType, dVal, 2, INVALID32, INVALID32, INVALID32, s.kind.fitIntensity)
        }
        w.steps.forEach { s ->
            when (s) {
                is RunStep -> run(s)
                is RepeatGroup -> {
                    val from = out.size
                    s.steps.forEach(::run)
                    out += FitStep("Repeat", 6, from.toLong(), 2, s.times.toLong(), INVALID32, INVALID32, 0)
                }
            }
        }
        return out
    }

    fun encode(w: WatchWorkout, createdMs: Long): ByteArray {
        val d = Out()
        val steps = steps(w)
        d.define(0, 0, listOf(Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16), Field(3, 4, UINT32Z), Field(4, 4, UINT32)))
        d.u8(0); d.u8(5); d.u16(255); d.u16(0); d.u32(1); d.u32(createdMs / 1000 - FIT_EPOCH_S)
        d.define(1, 26, listOf(Field(8, 24, STRING), Field(4, 1, ENUM), Field(6, 2, UINT16)))
        d.u8(1); d.str(w.name, 24); d.u8(1); d.u16(steps.size)
        d.define(2, 27, listOf(Field(254, 2, UINT16), Field(0, 16, STRING), Field(1, 1, ENUM), Field(2, 4, UINT32), Field(3, 1, ENUM), Field(4, 4, UINT32), Field(5, 4, UINT32), Field(6, 4, UINT32), Field(7, 1, ENUM)))
        steps.forEachIndexed { i, s ->
            d.u8(2); d.u16(i); d.str(s.name, 16); d.u8(s.durationType); d.u32(s.durationValue); d.u8(s.targetType); d.u32(s.targetValue); d.u32(s.low); d.u32(s.high); d.u8(s.intensity)
        }
        val data = d.b.toByteArray()
        val h = Out()
        h.u8(14); h.u8(0x20); h.u16(2132); h.u32(data.size.toLong()); ".FIT".forEach { h.u8(it.code) }
        val header = h.b.toByteArray()
        h.u16(crc(header))
        val full = h.b.toByteArray() + data
        val tail = Out(); tail.u16(crc(full))
        return full + tail.b.toByteArray()
    }

    /** True when the trailing CRC matches (a FIT reader's integrity check). */
    fun crcOk(bytes: ByteArray) = crc(bytes) == 0
}
