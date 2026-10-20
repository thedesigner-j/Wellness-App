package com.javon.healthdash.calc

// Runner strength with dumbbells only (no gym). Two routines alternate through the week; sets, reps and
// load change with the plan phase. Fixed rules, no AI.

enum class Exercise(val label: String, val target: String, val cues: List<String>, val perSide: Boolean = false, val hold: Boolean = false) {
    GobletSquat("Goblet squat", "Quads, glutes", listOf("Hold one dumbbell at your chest, feet shoulder-width", "Sit back and down until thighs are parallel", "Knees track over toes, chest up", "Drive through the whole foot to stand")),
    RomanianDeadlift("Romanian deadlift", "Hamstrings, glutes", listOf("Dumbbells in front of thighs, soft knees", "Push hips back, keep the back flat", "Lower to mid-shin — feel the hamstrings stretch", "Squeeze glutes to stand tall")),
    ReverseLunge("Reverse lunge", "Quads, glutes, balance", listOf("Dumbbells at your sides", "Step one foot back, lower the back knee toward the floor", "Front shin stays vertical", "Push through the front heel to return"), perSide = true),
    SplitSquat("Bulgarian split squat", "Single-leg strength", listOf("Back foot on a chair or couch, dumbbells at sides", "Lower straight down until the front thigh is parallel", "Keep the front knee over the ankle", "Drive up through the front heel"), perSide = true),
    CalfRaise("Single-leg calf raise", "Calves, Achilles", listOf("Hold a dumbbell, stand on one foot (hold a wall)", "Rise as high as you can onto the ball of the foot", "Pause at the top", "Lower slowly — 3 seconds down"), perSide = true),
    BentOverRow("Bent-over row", "Upper back, posture", listOf("Hinge forward with a flat back, dumbbells hanging", "Pull elbows back toward your hips", "Squeeze the shoulder blades together", "Lower with control")),
    GluteBridge("Glute bridge", "Glutes, hips", listOf("Lie on your back, knees bent, dumbbell across the hips", "Press through the heels to lift the hips", "Straight line from shoulders to knees", "Pause, then lower slowly")),
    DeadBug("Dead bug", "Deep core", listOf("Lie on your back, arms up, knees over hips", "Lower the opposite arm and leg toward the floor", "Keep the lower back pressed down", "Return and switch sides"), perSide = true),
    BirdDog("Bird dog", "Core, back, balance", listOf("On hands and knees, back flat", "Reach one arm forward and the opposite leg back", "Hips stay level — don't rotate", "Return and switch sides"), perSide = true),
    Plank("Forearm plank", "Core", listOf("Forearms under shoulders, legs straight", "Straight line from head to heels", "Brace the stomach, squeeze glutes", "Breathe steadily"), hold = true),
}

data class StrengthSet(val exercise: Exercise, val sets: Int, val reps: String, val load: String)

data class StrengthWorkout(val name: String, val sets: List<StrengthSet>, val restSec: Int, val minutes: Int, val note: String)

object StrengthPlan {
    val A = listOf(Exercise.GobletSquat, Exercise.RomanianDeadlift, Exercise.ReverseLunge, Exercise.CalfRaise, Exercise.Plank)
    val B = listOf(Exercise.SplitSquat, Exercise.BentOverRow, Exercise.GluteBridge, Exercise.DeadBug, Exercise.BirdDog)

    /** Sets × reps by phase: base builds the habit, build/peak get heavier, taper keeps it light. */
    fun workout(phase: Phase, second: Boolean): StrengthWorkout {
        val list = if (second) B else A
        val (sets, reps, load, rest) = when (phase) {
            Phase.Base -> Quad(2, 12, "Moderate — 2–3 reps left in the tank", 60)
            Phase.Build -> Quad(3, 8, "Heavier — last 2 reps are hard", 90)
            Phase.Peak -> Quad(2, 8, "Heavy, but stop before form slips", 90)
            else -> Quad(1, 8, "Light — just keep the movement", 60)
        }
        val out = list.map { e ->
            when {
                e.hold -> StrengthSet(e, sets, when (phase) { Phase.Base -> "30 s"; Phase.Build, Phase.Peak -> "45 s"; else -> "30 s" }, "Bodyweight")
                e == Exercise.DeadBug || e == Exercise.BirdDog -> StrengthSet(e, sets, "8 / side", "Bodyweight · slow")
                e == Exercise.GluteBridge -> StrengthSet(e, sets, "${reps + 4}", load)
                e == Exercise.CalfRaise -> StrengthSet(e, sets, "${reps + 3} / side", load)
                e.perSide -> StrengthSet(e, sets, "$reps / side", load)
                else -> StrengthSet(e, sets, "$reps", load)
            }
        }
        // ~45 s of work per set (×2 for per-side) plus rest, plus 5 min warm-up.
        val workSec = out.sumOf { s -> s.sets * (if (s.exercise.perSide) 90 else 45) + (s.sets - 1).coerceAtLeast(0) * rest + rest }
        val note = when (phase) {
            Phase.Taper, Phase.Race -> "Taper: keep it short and light — no soreness before race day."
            Phase.Peak -> "Peak: maintain strength; don't add weight this phase."
            Phase.Build -> "Build: go heavier than base. Not the day before a long run or hard session."
            else -> "Base: learn the movements with good form before adding weight."
        }
        return StrengthWorkout(if (second) "Strength B · single-leg & core" else "Strength A · legs & core", out, rest, 5 + (workSec / 60.0).let { kotlin.math.ceil(it).toInt() }, note)
    }

    private data class Quad(val sets: Int, val reps: Int, val load: String, val rest: Int)
}
