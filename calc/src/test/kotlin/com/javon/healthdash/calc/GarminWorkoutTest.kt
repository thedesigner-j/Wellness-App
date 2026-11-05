package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class GarminWorkoutTest {
    /** 4:30 goal, projected 4:32 (80% range 4:10–4:54), threshold pace 9:40/mi, forecast 60°F (no heat) unless set. */
    private val t = TargetInputs(goalSec = 16200, projectedSec = 16316, lowSec = 15000, highSec = 17600, thresholdPaceSec = 580, forecastF = 60.0)
    private val day = LocalDate.of(2026, 11, 10)

    private fun steps(o: JsonObject) = o["workoutSegments"]!!.jsonArray[0].jsonObject["workoutSteps"]!!.jsonArray.map { it.jsonObject }

    @Test fun easyTargetsAreMpPlus75To105() {
        // MP = 16200 / 26.2188 = 618 s → 693–723 s/mi (11:33–12:03)
        assertThat(WatchTargets.easy(t)).isEqualTo(PaceWindow(693, 723))
        // Heat: 70°F → 2% slower
        assertThat(WatchTargets.easy(t.copy(forecastF = 70.0))).isEqualTo(PaceWindow(707, 737))
        // MP target: goal is inside the range → goal pace ± 8
        assertThat(WatchTargets.mp(t)).isEqualTo(PaceWindow(610, 626))
        // Goal outside the 80% range → projected pace
        assertThat(WatchTargets.mpPace(t.copy(lowSec = 16250))).isEqualTo(622)
        assertThat(WatchTargets.tempo(t)).isEqualTo(PaceWindow(572, 588))
        assertThat(WatchTargets.tempo(t.copy(thresholdPaceSec = null))).isNull()
    }

    @Test fun targetValuesSitOnTheStepNotInsideTargetType() {
        val w = WatchTargets.build(1, day, WorkoutKind.Easy, 3.0, 4, t)!!
        val main = steps(GarminConnectJson.json(w)).single { it["description"]!!.jsonPrimitive.content == "Easy" }
        val type = main["targetType"]!!.jsonObject
        // targetType only names the type …
        assertThat(type.keys).containsExactly("workoutTargetTypeId", "workoutTargetTypeKey")
        assertThat(type["workoutTargetTypeKey"]!!.jsonPrimitive.content).isEqualTo("pace.zone")
        // … and the values are on the step, in m/s (1609.344 / s per mile).
        assertThat(main["targetValueOne"]!!.jsonPrimitive.double).isWithin(0.001).of(1609.344 / 693)
        assertThat(main["targetValueTwo"]!!.jsonPrimitive.double).isWithin(0.001).of(1609.344 / 723)
        // Distances in meters.
        assertThat(main["endCondition"]!!.jsonObject["conditionTypeKey"]!!.jsonPrimitive.content).isEqualTo("distance")
        assertThat(main["endConditionValue"]!!.jsonPrimitive.double).isWithin(0.1).of(2.5 * 1609.344)
    }

    @Test fun longRunGetsMpFinishFromWeek7() {
        val early = WatchTargets.build(2, day, WorkoutKind.Long, 12.5, 4, t)!!
        assertThat(early.steps.filterIsInstance<RunStep>().none { it.name == "Marathon pace" }).isTrue()
        val late = WatchTargets.build(3, day, WorkoutKind.Long, 16.0, 6, t)!!
        val mp = late.steps.filterIsInstance<RunStep>().single { it.name == "Marathon pace" }
        assertThat(mp.meters!! / METERS_PER_MI).isWithin(1e-9).of(3.0)
        assertThat(mp.pace).isEqualTo(PaceWindow(610, 626))
        // Distances add up to the planned run.
        assertThat(late.steps.filterIsInstance<RunStep>().sumOf { it.meters!! } / METERS_PER_MI).isWithin(1e-9).of(16.0)
    }

    @Test fun intervalsUseARepeatGroupAndTestIsTimeBasedWithNoTarget() {
        val w = WatchTargets.build(4, day, WorkoutKind.Intervals, 6.25, 8, t)!!
        val group = steps(GarminConnectJson.json(w))[1]
        assertThat(group["type"]!!.jsonPrimitive.content).isEqualTo("RepeatGroupDTO")
        assertThat(group["numberOfIterations"]!!.jsonPrimitive.int).isEqualTo(5)
        assertThat(group["workoutSteps"]!!.jsonArray).hasSize(2)
        val test = WatchTargets.build(5, day, WorkoutKind.Tempo, 5.0, 8, t, threshold30Test = true)!!
        val hard = steps(GarminConnectJson.json(test)).filter { it["description"]!!.jsonPrimitive.content.startsWith("Hard") }
        assertThat(hard.map { it["endConditionValue"]!!.jsonPrimitive.int }).containsExactly(600, 1200).inOrder()
        hard.forEach {
            assertThat(it["targetType"]!!.jsonObject["workoutTargetTypeKey"]!!.jsonPrimitive.content).isEqualTo("no.target")
            assertThat(it["targetValueOne"]).isEqualTo(JsonNull)
        }
    }

    @Test fun fitFileIsWellFormed() {
        val w = WatchTargets.build(6, day, WorkoutKind.Intervals, 6.25, 8, t)!!
        val bytes = FitWorkout.encode(w, 1_793_000_000_000)
        assertThat(String(bytes, 8, 4)).isEqualTo(".FIT")
        assertThat(FitWorkout.crcOk(bytes)).isTrue()
        val steps = FitWorkout.steps(w)
        // warm-up, 800, jog, repeat ×5 (back to step 1), cool-down
        assertThat(steps).hasSize(5)
        assertThat(steps[3].durationType).isEqualTo(6)
        assertThat(steps[3].durationValue).isEqualTo(1)
        assertThat(steps[3].targetValue).isEqualTo(5)
        // Speed targets in mm/s: low = slower pace.
        assertThat(steps[1].low).isLessThan(steps[1].high)
    }

    /** Dry run: writes the JSON for one easy run and one long run (calc/build/dryrun) without uploading anything. */
    @Test fun dryRunOutput() {
        val hot = t.copy(forecastF = 68.0)
        val out = File("build/dryrun").apply { mkdirs() }
        File(out, "easy.json").writeText(GarminConnectJson.pretty(WatchTargets.build(101, LocalDate.of(2026, 11, 6), WorkoutKind.Easy, 3.0, 4, hot)!!))
        File(out, "long.json").writeText(GarminConnectJson.pretty(WatchTargets.build(102, LocalDate.of(2026, 11, 7), WorkoutKind.Long, 12.5, 4, hot)!!))
        assertThat(File(out, "long.json").readText()).contains("\"targetValueOne\"")
    }
}
