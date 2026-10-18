package com.javon.healthdash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.javon.healthdash.calc.GlucosePoint
import com.javon.healthdash.calc.MINUTE_MS
import com.javon.healthdash.calc.Targets
import com.javon.healthdash.calc.TimeWindow
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme

@Composable
fun LegendDot(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = Theme.type.caption, color = Theme.colors.textSecondary)
    }
}

/**
 * Figma: Glucose Chart. Target band shaded (SPEC §6), 70/140 grid, 180 line in very-high,
 * workouts as translucent bands, meals pinned on the timeline.
 */
@Composable
fun GlucoseChart(
    title: String,
    points: List<GlucosePoint>,
    window: TimeWindow,
    ticks: List<String>,
    modifier: Modifier = Modifier,
    meals: List<Long> = emptyList(),
    workouts: List<TimeWindow> = emptyList(),
    targets: Targets = Targets(),
    onMealClick: ((Long) -> Unit)? = null,
) {
    val c = Theme.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = Theme.type.caption.copy(color = c.chartAxisLabel)
    val mealPainter = rememberVectorPainter(AppIcons.meal)
    AppCard(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Theme.type.labelL, color = c.textPrimary)
            LegendDot("${targets.tightLow.toInt()}–${targets.tightHigh.toInt()}", c.statusInRange)
        }
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val pinSpace = 16.dp.toPx()
            val h = size.height - pinSpace
            val w = size.width
            val inWin = points.filter { it.timeMs in window }.sortedBy { it.timeMs }
            val yMin = minOf(40.0, (inWin.minOfOrNull { it.mgdl } ?: 40.0) - 10)
            val yMax = maxOf(220.0, (inWin.maxOfOrNull { it.mgdl } ?: 220.0) + 10)
            fun x(t: Long) = ((t - window.startMs).toFloat() / window.durationMs) * w
            fun y(v: Double) = (h * (1 - (v - yMin) / (yMax - yMin))).toFloat()

            drawRect(c.chartTargetBand, Offset(0f, y(targets.tightHigh)), Size(w, y(targets.tightLow) - y(targets.tightHigh)))
            for (wk in workouts) {
                val x0 = x(maxOf(wk.startMs, window.startMs)); val x1 = x(minOf(wk.endMs, window.endMs))
                if (x1 > x0) drawRect(c.chartSplitTraining.copy(alpha = 0.14f), Offset(x0, 0f), Size(x1 - x0, h))
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            for (v in listOf(targets.tightLow, targets.tightHigh, targets.veryHigh)) {
                drawLine(if (v == targets.veryHigh) c.statusVeryHigh else c.chartGrid, Offset(0f, y(v)), Offset(w, y(v)), 1.dp.toPx(), pathEffect = dash)
                val label = measurer.measure("${v.toInt()}", axisStyle)
                drawText(label, topLeft = Offset(w - label.size.width, y(v) - label.size.height - 1.dp.toPx()))
            }
            val path = Path()
            var prev: GlucosePoint? = null
            for (p in inWin) {
                if (prev == null || p.timeMs - prev.timeMs > 15 * MINUTE_MS) path.moveTo(x(p.timeMs), y(p.mgdl)) else path.lineTo(x(p.timeMs), y(p.mgdl))
                prev = p
            }
            drawPath(path, c.chartLine, style = Stroke(Dimens.strokeChartLine.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (m in meals.filter { it in window }) drawMealPin(Offset(x(m), size.height - pinSpace / 2), c.bgNeutral, c.borderAccent, c.iconPrimary, mealPainter)
        }
        if (ticks.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ticks.forEach { Text(it, style = Theme.type.caption, color = c.chartAxisLabel) }
            }
        }
    }
}

private fun DrawScope.drawMealPin(center: Offset, fill: Color, ring: Color, glyph: Color, painter: androidx.compose.ui.graphics.vector.VectorPainter) {
    val r = 12.dp.toPx()
    drawCircle(fill, r, center)
    drawCircle(ring, r, center, style = Stroke(2.dp.toPx()))
    val s = 14.dp.toPx()
    translate(center.x - s / 2, center.y - s / 2) {
        with(painter) { draw(Size(s, s), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(glyph)) }
    }
}

/** Figma: Overlay Chart — average rise above baseline, one line per series, comfort band 0–30. */
@Composable
fun OverlayChart(
    title: String,
    series: List<Triple<String, Color, List<Pair<Int, Double>>>>,
    modifier: Modifier = Modifier,
    windowMin: Int = 120,
) {
    val c = Theme.colors
    AppCard(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Theme.type.labelL, color = c.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) { series.forEach { LegendDot(it.first, it.second) } }
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val all = series.flatMap { s -> s.third.map { it.second } }
            val yMin = minOf(-10.0, all.minOrNull() ?: -10.0)
            val yMax = maxOf(60.0, (all.maxOrNull() ?: 60.0) + 5)
            fun x(m: Int) = m.toFloat() / windowMin * size.width
            fun y(v: Double) = (size.height * (1 - (v - yMin) / (yMax - yMin))).toFloat()
            drawRect(c.chartTargetBand, Offset(0f, y(30.0)), Size(size.width, y(0.0) - y(30.0)))
            drawLine(c.chartGrid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            for ((_, color, pts) in series) {
                if (pts.isEmpty()) continue
                val path = Path()
                pts.forEachIndexed { i, (m, v) -> if (i == 0) path.moveTo(x(m), y(v)) else path.lineTo(x(m), y(v)) }
                drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "30", "60", "90", "$windowMin min").forEach { Text(it, style = Theme.type.caption, color = c.chartAxisLabel) }
        }
    }
}

/** Figma: Progress Ring — time-in-range segments. Values are percentages that sum to ~100. */
@Composable
fun ProgressRing(inRange: Double, above: Double, veryHigh: Double, low: Double, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Box(modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(120.dp)) {
            val stroke = 10.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val tl = Offset(stroke / 2, stroke / 2)
            drawArc(c.bgNeutral, 0f, 360f, false, tl, arcSize, style = Stroke(stroke))
            var start = -90f
            for ((pct, color) in listOf(inRange to c.statusInRange, above - veryHigh to c.statusHigh, veryHigh to c.statusVeryHigh, low to c.statusLow)) {
                val sweep = (pct.coerceAtLeast(0.0) / 100.0 * 360).toFloat()
                if (sweep > 0) drawArc(color, start, sweep, false, tl, arcSize, style = Stroke(stroke))
                start += sweep
            }
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${inRange.toInt()}%", style = Theme.type.metricM, color = c.textPrimary)
            Text("in range", style = Theme.type.caption, color = c.textSecondary)
        }
    }
}

@Composable
fun MealPinIcon(modifier: Modifier = Modifier) {
    val c = Theme.colors
    Box(modifier.size(28.dp).clip(CircleShape).background(c.bgNeutral), contentAlignment = Alignment.Center) {
        Icon(AppIcons.meal, null, tint = c.iconPrimary, modifier = Modifier.size(14.dp))
    }
}
