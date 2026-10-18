package com.javon.healthdash.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Primitive palette — mirrors the Figma "Primitives" collection (Wellness App file).
 * Components never use these directly; they read [AppColors].
 */
object Prim {
    val neutral0 = Color(0xFFFFFFFF)
    val neutral50 = Color(0xFFF9F9F9)
    val neutral100 = Color(0xFFEFEFEF)
    val neutral200 = Color(0xFFDADADA)
    val neutral300 = Color(0xFFBABABA)
    val neutral400 = Color(0xFFA7A7A7)
    val neutral500 = Color(0xFF646464)
    val neutral600 = Color(0xFF4A4A4A)
    val neutral700 = Color(0xFF333333)
    val neutral800 = Color(0xFF222022)
    val neutral850 = Color(0xFF161316)
    val neutral900 = Color(0xFF0D0C0D)
    val neutral1000 = Color(0xFF000000)
    val orange50 = Color(0xFFFFF1EA)
    val orange400 = Color(0xFFFF6D29)
    val orange500 = Color(0xFFE85002)
    val orange600 = Color(0xFFC24200)
    val ember500 = Color(0xFFF16001)
    val red300 = Color(0xFFF2645C)
    val red500 = Color(0xFFC10801)
    val sand100 = Color(0xFFF6EFE7)
    val sand300 = Color(0xFFD9C3AB)
    val sand500 = Color(0xFFB39A7E)
    val sand700 = Color(0xFF7A6550)
    val brown700 = Color(0xFF453027)
    val brown800 = Color(0xFF2A1D17)
    val mint100 = Color(0xFFDDF5E8)
    val mint400 = Color(0xFF5BD594)
    val mint500 = Color(0xFF2EBD72)
    val mint700 = Color(0xFF1C7A49)
    val mint900 = Color(0xFF10331F)
    val amber300 = Color(0xFFFFC266)
    val amber400 = Color(0xFFFFAD33)
    val amber500 = Color(0xFFE89400)
    val sky300 = Color(0xFF7CC6FF)
    val sky500 = Color(0xFF2E9BEA)
    val indigo100 = Color(0xFFECECFE)
    val indigo300 = Color(0xFFA9ABFA)
    val indigo500 = Color(0xFF6E72EE)
    val indigo800 = Color(0xFF24254F)
    val mint500a16 = Color(0x292EBD72)
    val mint500a12 = Color(0x1F2EBD72)
    val neutral0a08 = Color(0x14FFFFFF)
    val neutral1000a08 = Color(0x14000000)
    val neutral1000a40 = Color(0x66000000)
    val neutral1000a60 = Color(0x99000000)
}

/** Semantic colors — mirrors the Figma "Color" collection. Names follow the Figma variable path. */
@Immutable
data class AppColors(
    val bgCanvas: Color,
    val bgSurface: Color,
    val bgSurfaceRaised: Color,
    val bgSurfaceSunken: Color,
    val bgInverse: Color,
    val bgOverlay: Color,
    val bgHover: Color,
    val bgAccent: Color,
    val bgAccentPressed: Color,
    val bgAccentSubtle: Color,
    val bgNeutral: Color,
    val bgNotice: Color,
    val bgAi: Color,
    val bgSuccessSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textInverse: Color,
    val textOnAccent: Color,
    val textAccent: Color,
    val textAi: Color,
    val textNotice: Color,
    val textSuccess: Color,
    val textDanger: Color,
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconAccent: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val borderFocus: Color,
    val borderAccent: Color,
    val statusLow: Color,
    val statusInRange: Color,
    val statusHigh: Color,
    val statusVeryHigh: Color,
    val chartLine: Color,
    val chartGrid: Color,
    val chartAxisLabel: Color,
    val chartTargetBand: Color,
    val chartWalk: Color,
    val chartNoWalk: Color,
    val chartSplitTraining: Color,
    val chartSplitOvernight: Color,
    val chartSplitEveryday: Color,
    val chartPlanned: Color,
    val chartCompleted: Color,
    val chartFuelMark: Color,
    val chartLabMarker: Color,
    val gradientHero: List<Color>,
    val isDark: Boolean,
)

val DarkColors = AppColors(
    bgCanvas = Prim.neutral1000,
    bgSurface = Prim.neutral850,
    bgSurfaceRaised = Prim.neutral800,
    bgSurfaceSunken = Prim.neutral900,
    bgInverse = Prim.neutral50,
    bgOverlay = Prim.neutral1000a60,
    bgHover = Prim.neutral0a08,
    bgAccent = Prim.orange500,
    bgAccentPressed = Prim.orange600,
    bgAccentSubtle = Prim.brown700,
    bgNeutral = Prim.neutral700,
    bgNotice = Prim.brown800,
    bgAi = Prim.indigo800,
    bgSuccessSubtle = Prim.mint900,
    textPrimary = Prim.neutral50,
    textSecondary = Prim.neutral400,
    textTertiary = Prim.neutral500,
    textInverse = Prim.neutral900,
    textOnAccent = Prim.neutral0,
    textAccent = Prim.orange400,
    textAi = Prim.indigo300,
    textNotice = Prim.sand300,
    textSuccess = Prim.mint400,
    textDanger = Prim.red300,
    iconPrimary = Prim.neutral50,
    iconSecondary = Prim.neutral400,
    iconAccent = Prim.orange400,
    borderSubtle = Prim.neutral700,
    borderStrong = Prim.neutral500,
    borderFocus = Prim.orange400,
    borderAccent = Prim.orange500,
    statusLow = Prim.red300,
    statusInRange = Prim.mint400,
    statusHigh = Prim.sand300,
    statusVeryHigh = Prim.amber400,
    chartLine = Prim.neutral50,
    chartGrid = Prim.neutral700,
    chartAxisLabel = Prim.neutral500,
    chartTargetBand = Prim.mint500a16,
    chartWalk = Prim.mint400,
    chartNoWalk = Prim.neutral500,
    chartSplitTraining = Prim.sky300,
    chartSplitOvernight = Prim.indigo300,
    chartSplitEveryday = Prim.neutral300,
    chartPlanned = Prim.neutral500,
    chartCompleted = Prim.orange500,
    chartFuelMark = Prim.amber300,
    chartLabMarker = Prim.orange400,
    gradientHero = listOf(Prim.neutral1000, Prim.red500, Prim.ember500, Prim.sand300),
    isDark = true,
)

val LightColors = AppColors(
    bgCanvas = Prim.neutral50,
    bgSurface = Prim.neutral0,
    bgSurfaceRaised = Prim.neutral0,
    bgSurfaceSunken = Prim.neutral100,
    bgInverse = Prim.neutral850,
    bgOverlay = Prim.neutral1000a40,
    bgHover = Prim.neutral1000a08,
    bgAccent = Prim.orange500,
    bgAccentPressed = Prim.orange600,
    bgAccentSubtle = Prim.orange50,
    bgNeutral = Prim.neutral100,
    bgNotice = Prim.sand100,
    bgAi = Prim.indigo100,
    bgSuccessSubtle = Prim.mint100,
    textPrimary = Prim.neutral900,
    textSecondary = Prim.neutral500,
    textTertiary = Prim.neutral400,
    textInverse = Prim.neutral50,
    textOnAccent = Prim.neutral0,
    textAccent = Prim.orange600,
    textAi = Prim.indigo500,
    textNotice = Prim.sand700,
    textSuccess = Prim.mint700,
    textDanger = Prim.red500,
    iconPrimary = Prim.neutral900,
    iconSecondary = Prim.neutral500,
    iconAccent = Prim.orange500,
    borderSubtle = Prim.neutral200,
    borderStrong = Prim.neutral300,
    borderFocus = Prim.orange500,
    borderAccent = Prim.orange500,
    statusLow = Prim.red500,
    statusInRange = Prim.mint500,
    statusHigh = Prim.sand500,
    statusVeryHigh = Prim.amber500,
    chartLine = Prim.neutral900,
    chartGrid = Prim.neutral200,
    chartAxisLabel = Prim.neutral400,
    chartTargetBand = Prim.mint500a12,
    chartWalk = Prim.mint500,
    chartNoWalk = Prim.neutral400,
    chartSplitTraining = Prim.sky500,
    chartSplitOvernight = Prim.indigo500,
    chartSplitEveryday = Prim.neutral500,
    chartPlanned = Prim.neutral300,
    chartCompleted = Prim.orange500,
    chartFuelMark = Prim.amber500,
    chartLabMarker = Prim.orange600,
    gradientHero = listOf(Prim.neutral1000, Prim.red500, Prim.ember500, Prim.sand300),
    isDark = false,
)
