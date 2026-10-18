package com.javon.healthdash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme

/** One point of a trend series; null value = not enough data that day (a gap in the line). */
data class TrendPoint(val timeMs: Long, val value: Double?)

/**
 * Figma: Trend Line Chart (Goal=Below / Goal=Above). The goal side of the target line is shaded.
 * Markers (e.g. lab A1c results) are drawn as filled circles.
 */
@Composable
fun TrendLineChart(
    title: String,
    points: List<TrendPoint>,
    target: Double?,
    targetLabel: String,
    goalAbove: Boolean,
    ticks: List<String>,
    modifier: Modifier = Modifier,
    seriesLabel: String = "",
    markers: List<Pair<Long, Double>> = emptyList(),
    markerLabel: String = "",
    lineColor: Color = Theme.colors.chartLine,
    format: (Double) -> String = { "%.1f".format(it) },
) {
    val c = Theme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = Theme.type.caption.copy(color = c.statusInRange)
    val axisStyle = Theme.type.caption.copy(color = c.chartAxisLabel)
    AppCard(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Theme.type.labelL, color = c.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
                if (seriesLabel.isNotEmpty()) LegendDot(seriesLabel, lineColor)
                if (markers.isNotEmpty() && markerLabel.isNotEmpty()) LegendDot(markerLabel, c.chartLabMarker)
            }
        }
        val values = points.mapNotNull { it.value } + markers.map { it.second } + listOfNotNull(target)
        if (points.none { it.value != null }) {
            Text("Not enough data for this period.", style = Theme.type.bodyM, color = c.textSecondary)
        } else {
            val tMin = points.first().timeMs
            val tMax = points.last().timeMs.coerceAtLeast(tMin + 1)
            val lo = values.min()
            val hi = values.max()
            val pad = ((hi - lo) * 0.15).coerceAtLeast(hi * 0.02 + 0.05)
            val yMin = lo - pad
            val yMax = hi + pad
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                fun x(t: Long) = (t - tMin).toFloat() / (tMax - tMin) * size.width
                fun y(v: Double) = (size.height * (1 - (v - yMin) / (yMax - yMin))).toFloat()
                if (target != null) {
                    val ty = y(target)
                    if (goalAbove) drawRect(c.chartTargetBand, Offset.Zero, Size(size.width, ty))
                    else drawRect(c.chartTargetBand, Offset(0f, ty), Size(size.width, size.height - ty))
                    drawLine(c.statusInRange, Offset(0f, ty), Offset(size.width, ty), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                    val l = measurer.measure(targetLabel, labelStyle)
                    drawText(l, topLeft = Offset(size.width - l.size.width, if (goalAbove) ty + 2.dp.toPx() else ty - l.size.height - 2.dp.toPx()))
                }
                val path = Path()
                var drawing = false
                for (p in points) {
                    val v = p.value
                    if (v == null) { drawing = false; continue }
                    if (!drawing) path.moveTo(x(p.timeMs), y(v)) else path.lineTo(x(p.timeMs), y(v))
                    drawing = true
                }
                drawPath(path, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                points.lastOrNull { it.value != null }?.let { drawCircle(lineColor, 3.dp.toPx(), Offset(x(it.timeMs), y(it.value!!))) }
                for ((t, v) in markers) {
                    if (t < tMin || t > tMax) continue
                    drawCircle(c.bgSurface, 7.dp.toPx(), Offset(x(t), y(v)))
                    drawCircle(c.chartLabMarker, 5.dp.toPx(), Offset(x(t), y(v)))
                }
                val top = measurer.measure(format(yMax - pad), axisStyle)
                drawText(top, topLeft = Offset(0f, 0f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ticks.forEach { Text(it, style = Theme.type.caption, color = c.chartAxisLabel) }
            }
        }
    }
}

/** Figma: Sheet — modal bottom sheet with handle, title and close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Theme.colors.bgSurfaceRaised,
        scrimColor = Theme.colors.bgOverlay,
        shape = Shapes.xxl.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.spacing2xl).padding(bottom = Dimens.spacing3xl).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingLg),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Theme.type.headingH2, color = Theme.colors.textPrimary)
                AppIconButton(com.javon.healthdash.ui.theme.AppIcons.close, "Close", onDismiss, size = 36.dp, iconSize = 22.dp)
            }
            content()
        }
    }
}
