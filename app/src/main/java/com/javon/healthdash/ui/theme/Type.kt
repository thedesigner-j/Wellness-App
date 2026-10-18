package com.javon.healthdash.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.javon.healthdash.R

/**
 * Mirrors Figma Typography › family/sans. To switch to Neue Montreal later, add its .ttf files
 * to res/font and change only this family (same as changing the one Figma variable).
 */
val SansFamily = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private fun style(size: Float, line: Float, tracking: Float, weight: FontWeight) = TextStyle(
    fontFamily = SansFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Text styles — one per Figma text style (same names, same size/line/tracking). */
@Immutable
data class AppType(
    val displayGlucose: TextStyle = style(72f, 72f, -2.88f, FontWeight.Light),
    val displayL: TextStyle = style(48f, 52f, -1.44f, FontWeight.Normal),
    val displayM: TextStyle = style(36f, 40f, -0.72f, FontWeight.Normal),
    val headingH1: TextStyle = style(28f, 34f, -0.42f, FontWeight.Medium),
    val headingH2: TextStyle = style(22f, 28f, -0.22f, FontWeight.Medium),
    val headingH3: TextStyle = style(18f, 24f, 0f, FontWeight.Medium),
    val bodyL: TextStyle = style(16f, 24f, 0f, FontWeight.Normal),
    val bodyM: TextStyle = style(14f, 20f, 0f, FontWeight.Normal),
    val bodyS: TextStyle = style(12f, 16f, 0f, FontWeight.Normal),
    val labelL: TextStyle = style(14f, 20f, 0f, FontWeight.Medium),
    val labelM: TextStyle = style(12f, 16f, 0.12f, FontWeight.Medium),
    val labelS: TextStyle = style(11f, 14f, 0.44f, FontWeight.Medium),
    val metricL: TextStyle = style(32f, 36f, -0.64f, FontWeight.Medium),
    val metricM: TextStyle = style(20f, 24f, -0.2f, FontWeight.Medium),
    val caption: TextStyle = style(11f, 14f, 0.22f, FontWeight.Normal),
)
