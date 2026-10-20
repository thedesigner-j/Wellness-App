package com.javon.healthdash.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.javon.healthdash.calc.Exercise
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Animated side-view figure for each dumbbell exercise. Body segments are drawn from joint angles in degrees:
// 0 = pointing down, 90 = forward (right, the way the figure faces), 180 = up, -90 = back.
// Each exercise has a start and an end pose; the figure eases between them on a loop.

private data class Pose(
    val torso: Float = 180f,            // hip → shoulder
    val nearThigh: Float = 0f, val nearShin: Float = 0f, val nearFoot: Float = 90f,
    val farThigh: Float = 0f, val farShin: Float = 0f, val farFoot: Float = 90f,
    val nearUpper: Float = 0f, val nearFore: Float = 0f,
    val farUpper: Float = 0f, val farFore: Float = 0f,
    /** Anchor: true = near toe fixed on the floor; false = hip at [hipX], [hipY] (body units, y up from floor). */
    val toeAnchor: Boolean = true,
    val hipX: Float = 0f, val hipY: Float = 0f,
)

private enum class Hold { None, Hands, Chest, Hips }

private data class Motion(val a: Pose, val b: Pose, val hold: Hold, val bench: Boolean = false, val downMs: Int = 1400, val upMs: Int = 1000)

private const val TORSO = 0.5f
private const val THIGH = 0.45f
private const val SHIN = 0.45f
private const val FOOT = 0.13f
private const val UPPER = 0.3f
private const val FORE = 0.28f
private const val HEAD = 0.1f

private fun motion(e: Exercise): Motion = when (e) {
    Exercise.GobletSquat -> Motion(
        Pose(nearUpper = 25f, nearFore = 165f, farUpper = 25f, farFore = 165f),
        Pose(torso = 150f, nearThigh = 80f, nearShin = -25f, farThigh = 80f, farShin = -25f, nearUpper = 40f, nearFore = 150f, farUpper = 40f, farFore = 150f),
        Hold.Chest,
    )
    Exercise.RomanianDeadlift -> Motion(
        Pose(),
        Pose(torso = 100f, nearThigh = 25f, nearShin = -12f, farThigh = 25f, farShin = -12f),
        Hold.Hands,
    )
    Exercise.ReverseLunge -> Motion(
        Pose(farThigh = -5f, farShin = -5f),
        Pose(nearThigh = 80f, nearShin = 0f, farThigh = -25f, farShin = -95f, farFoot = 150f),
        Hold.Hands,
    )
    Exercise.SplitSquat -> Motion(
        Pose(nearThigh = 20f, nearShin = 5f, farThigh = -20f, farShin = -100f, farFoot = 180f),
        Pose(nearThigh = 80f, nearShin = 5f, farThigh = -15f, farShin = -150f, farFoot = 200f),
        Hold.Hands, bench = true,
    )
    Exercise.CalfRaise -> Motion(
        Pose(farThigh = 30f, farShin = -40f),
        Pose(nearFoot = 50f, farThigh = 30f, farShin = -40f),
        Hold.Hands, downMs = 1600, upMs = 800,
    )
    Exercise.BentOverRow -> Motion(
        Pose(torso = 115f, nearThigh = 20f, nearShin = -12f, farThigh = 20f, farShin = -12f),
        Pose(torso = 115f, nearThigh = 20f, nearShin = -12f, farThigh = 20f, farShin = -12f, nearUpper = -55f, nearFore = 5f, farUpper = -55f, farFore = 5f),
        Hold.Hands, downMs = 1300, upMs = 900,
    )
    Exercise.GluteBridge -> Motion(
        Pose(torso = -90f, nearThigh = 135f, nearShin = 45f, farThigh = 135f, farShin = 45f, nearUpper = -10f, nearFore = 60f, farUpper = -10f, farFore = 60f),
        Pose(torso = -62f, nearThigh = 118f, nearShin = 12f, farThigh = 118f, farShin = 12f, nearUpper = -10f, nearFore = 60f, farUpper = -10f, farFore = 60f),
        Hold.Hips,
    )
    Exercise.DeadBug -> Motion(
        Pose(torso = -90f, nearThigh = 180f, nearShin = 90f, farThigh = 180f, farShin = 90f, nearUpper = 180f, nearFore = 180f, farUpper = 180f, farFore = 180f, toeAnchor = false, hipX = 0.1f, hipY = 0.06f),
        Pose(torso = -90f, nearThigh = 100f, nearShin = 95f, farThigh = 180f, farShin = 90f, nearUpper = 180f, nearFore = 180f, farUpper = -100f, farFore = -100f, toeAnchor = false, hipX = 0.1f, hipY = 0.06f),
        Hold.None, downMs = 1600, upMs = 1200,
    )
    Exercise.BirdDog -> Motion(
        Pose(torso = 110f, nearThigh = 0f, nearShin = -90f, farThigh = 0f, farShin = -90f, nearFoot = -90f, farFoot = -90f, toeAnchor = false, hipX = -0.25f, hipY = 0.45f),
        Pose(torso = 110f, nearThigh = -95f, nearShin = -95f, farThigh = 0f, farShin = -90f, nearFoot = -90f, farFoot = -90f, farUpper = 95f, farFore = 95f, toeAnchor = false, hipX = -0.25f, hipY = 0.45f),
        Hold.None, downMs = 1500, upMs = 1300,
    )
    Exercise.Plank -> Motion(
        Pose(torso = 98f, nearThigh = -82f, nearShin = -82f, farThigh = -82f, farShin = -82f, nearFoot = 20f, farFoot = 20f, nearUpper = 0f, nearFore = 90f, farUpper = 0f, farFore = 90f),
        Pose(torso = 96f, nearThigh = -80f, nearShin = -82f, farThigh = -80f, farShin = -82f, nearFoot = 20f, farFoot = 20f, nearUpper = 0f, nearFore = 90f, farUpper = 0f, farFore = 90f),
        Hold.None, downMs = 2000, upMs = 2000,
    )
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun mix(a: Pose, b: Pose, t: Float) = Pose(
    lerp(a.torso, b.torso, t),
    lerp(a.nearThigh, b.nearThigh, t), lerp(a.nearShin, b.nearShin, t), lerp(a.nearFoot, b.nearFoot, t),
    lerp(a.farThigh, b.farThigh, t), lerp(a.farShin, b.farShin, t), lerp(a.farFoot, b.farFoot, t),
    lerp(a.nearUpper, b.nearUpper, t), lerp(a.nearFore, b.nearFore, t),
    lerp(a.farUpper, b.farUpper, t), lerp(a.farFore, b.farFore, t),
    a.toeAnchor, lerp(a.hipX, b.hipX, t), lerp(a.hipY, b.hipY, t),
)

/** Direction for an angle (0 = down, 90 = forward) in body units with y up. */
private fun dir(deg: Float): Offset {
    val r = Math.toRadians(deg.toDouble())
    return Offset(sin(r).toFloat(), -cos(r).toFloat())
}

private data class Skeleton(
    val hip: Offset, val shoulder: Offset, val head: Offset,
    val nearKnee: Offset, val nearAnkle: Offset, val nearToe: Offset,
    val farKnee: Offset, val farAnkle: Offset, val farToe: Offset,
    val nearElbow: Offset, val nearHand: Offset, val farElbow: Offset, val farHand: Offset,
)

private fun skeleton(p: Pose): Skeleton {
    val hip = if (p.toeAnchor) {
        // Near toe fixed at the origin on the floor; walk back up the leg to the hip.
        val ankle = Offset(0f, 0f) - dir(p.nearFoot) * FOOT
        val knee = ankle - dir(p.nearShin) * SHIN
        knee - dir(p.nearThigh) * THIGH
    } else Offset(p.hipX, p.hipY)
    val shoulder = hip + dir(p.torso) * TORSO
    val head = shoulder + dir(p.torso) * (HEAD * 1.4f)
    fun leg(thigh: Float, shin: Float, foot: Float): Triple<Offset, Offset, Offset> {
        val k = hip + dir(thigh) * THIGH; val a = k + dir(shin) * SHIN
        return Triple(k, a, a + dir(foot) * FOOT)
    }
    val (nk, na, nt) = leg(p.nearThigh, p.nearShin, p.nearFoot)
    val (fk, fa, ft) = leg(p.farThigh, p.farShin, p.farFoot)
    val ne = shoulder + dir(p.nearUpper) * UPPER; val nh = ne + dir(p.nearFore) * FORE
    val fe = shoulder + dir(p.farUpper) * UPPER; val fh = fe + dir(p.farFore) * FORE
    return Skeleton(hip, shoulder, head, nk, na, nt, fk, fa, ft, ne, nh, fe, fh)
}

/** Looping animated figure demonstrating [exercise]. */
@Composable
fun ExerciseFigure(exercise: Exercise, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val m = motion(exercise)
    val transition = rememberInfiniteTransition(label = exercise.name)
    val t by transition.animateFloat(
        0f, 0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = m.downMs + m.upMs + 800
                0f at 0 using FastOutSlowInEasing
                0f at 300 using FastOutSlowInEasing
                1f at 300 + m.downMs using FastOutSlowInEasing
                1f at 600 + m.downMs using FastOutSlowInEasing
                0f at 600 + m.downMs + m.upMs
            },
            RepeatMode.Restart,
        ),
        label = "pose",
    )
    val near = c.textPrimary
    val far = c.textTertiary
    val accent = c.bgAccent
    val floor = c.borderStrong
    val bench = c.bgNeutral
    val start = skeleton(m.a)
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1.6f).clip(Shapes.lg).background(c.bgSurfaceSunken)
            .semantics { contentDescription = "Animation: ${exercise.label}" },
    ) {
        val s = skeleton(mix(m.a, m.b, t))
        val e = skeleton(m.b)
        // Fit both extreme poses (and the floor) into the canvas.
        val pts = listOf(start, e).flatMap { k -> listOf(k.hip, k.shoulder, k.head, k.nearKnee, k.nearToe, k.farKnee, k.farToe, k.nearHand, k.farHand, k.nearAnkle, k.farAnkle, k.nearElbow, k.farElbow) }
        val minX = pts.minOf { it.x } - HEAD; val maxX = pts.maxOf { it.x } + HEAD
        val maxY = max(pts.maxOf { it.y } + HEAD, 0.6f); val minY = min(0f, pts.minOf { it.y })
        val scale = min(size.width * 0.8f / (maxX - minX), size.height * 0.78f / (maxY - minY))
        val floorY = size.height * 0.88f
        val ox = size.width / 2 - (minX + maxX) / 2 * scale
        fun P(o: Offset) = Offset(ox + o.x * scale, floorY - o.y * scale)
        val w = scale * 0.07f
        drawLine(floor, Offset(size.width * 0.06f, floorY + w / 2), Offset(size.width * 0.94f, floorY + w / 2), strokeWidth = 2f)
        if (m.bench) {
            val bx = P(start.farAnkle).x
            drawRect(bench, topLeft = Offset(bx - scale * 0.25f, floorY - scale * 0.42f), size = androidx.compose.ui.geometry.Size(scale * 0.4f, scale * 0.42f))
        }
        fun limb(a: Offset, b: Offset, col: Color, width: Float = w) = drawLine(col, P(a), P(b), strokeWidth = width, cap = StrokeCap.Round)
        // Far side first, then torso, then near side on top.
        limb(s.hip, s.farKnee, far); limb(s.farKnee, s.farAnkle, far); limb(s.farAnkle, s.farToe, far, w * 0.8f)
        limb(s.shoulder, s.farElbow, far); limb(s.farElbow, s.farHand, far)
        if (m.hold == Hold.Hands) dumbbell(P(s.farHand), scale, far)
        limb(s.hip, s.shoulder, near, w * 1.25f)
        drawCircle(near, radius = HEAD * scale, center = P(s.head))
        limb(s.hip, s.nearKnee, near); limb(s.nearKnee, s.nearAnkle, near); limb(s.nearAnkle, s.nearToe, near, w * 0.8f)
        limb(s.shoulder, s.nearElbow, near); limb(s.nearElbow, s.nearHand, near)
        when (m.hold) {
            Hold.Hands -> dumbbell(P(s.nearHand), scale, accent)
            Hold.Chest -> dumbbell(P(s.nearHand), scale * 1.2f, accent)
            Hold.Hips -> dumbbell(P(s.hip) + Offset(0f, -scale * 0.09f), scale * 1.2f, accent)
            Hold.None -> {}
        }
    }
}

private fun DrawScope.dumbbell(at: Offset, scale: Float, color: Color) {
    val half = scale * 0.08f
    drawLine(color, at - Offset(half, 0f), at + Offset(half, 0f), strokeWidth = scale * 0.035f, cap = StrokeCap.Round)
    drawLine(color, at - Offset(half, scale * 0.045f), at - Offset(half, -scale * 0.045f), strokeWidth = scale * 0.06f, cap = StrokeCap.Round)
    drawLine(color, at + Offset(half, -scale * 0.045f), at + Offset(half, scale * 0.045f), strokeWidth = scale * 0.06f, cap = StrokeCap.Round)
}
