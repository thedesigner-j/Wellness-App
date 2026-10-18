package com.javon.healthdash.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** Mirrors the Figma "Dimension" collection. */
object Dimens {
    val spacing0 = 0.dp
    val spacingXxs = 2.dp
    val spacingXs = 4.dp
    val spacingSm = 8.dp
    val spacingMd = 12.dp
    val spacingLg = 16.dp
    val spacingXl = 20.dp
    val spacing2xl = 24.dp
    val spacing3xl = 32.dp
    val spacing4xl = 40.dp
    val spacing5xl = 48.dp
    val spacing6xl = 64.dp

    val radiusXs = 4.dp
    val radiusSm = 8.dp
    val radiusMd = 12.dp
    val radiusLg = 16.dp
    val radiusXl = 24.dp
    val radius2xl = 32.dp

    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val touchTarget = 48.dp
    val buttonSm = 36.dp
    val buttonMd = 48.dp
    val buttonLg = 56.dp

    val strokeHairline = 1.dp
    val strokeDefault = 1.5.dp
    val strokeChartLine = 2.dp
    val strokeFocus = 2.dp
}

object Shapes {
    val xs = RoundedCornerShape(Dimens.radiusXs)
    val sm = RoundedCornerShape(Dimens.radiusSm)
    val md = RoundedCornerShape(Dimens.radiusMd)
    val lg = RoundedCornerShape(Dimens.radiusLg)
    val xl = RoundedCornerShape(Dimens.radiusXl)
    val xxl = RoundedCornerShape(Dimens.radius2xl)
    val full = RoundedCornerShape(percent = 50)
}

val LocalAppColors = staticCompositionLocalOf { DarkColors }
val LocalAppType = staticCompositionLocalOf { AppType() }

/** Access point used by all components: `Theme.colors.bgSurface`, `Theme.type.bodyM`. */
object Theme {
    val colors: AppColors @Composable get() = LocalAppColors.current
    val type: AppType @Composable get() = LocalAppType.current
}

enum class ThemeMode { Dark, Light, System }

@Composable
fun HealthTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val material = if (dark) {
        darkColorScheme(
            primary = colors.bgAccent, onPrimary = colors.textOnAccent,
            background = colors.bgCanvas, onBackground = colors.textPrimary,
            surface = colors.bgSurface, onSurface = colors.textPrimary,
            surfaceContainer = colors.bgSurfaceRaised, surfaceContainerHigh = colors.bgSurfaceRaised,
            outline = colors.borderStrong, outlineVariant = colors.borderSubtle, error = colors.textDanger,
        )
    } else {
        lightColorScheme(
            primary = colors.bgAccent, onPrimary = colors.textOnAccent,
            background = colors.bgCanvas, onBackground = colors.textPrimary,
            surface = colors.bgSurface, onSurface = colors.textPrimary,
            surfaceContainer = colors.bgSurfaceRaised, surfaceContainerHigh = colors.bgSurfaceRaised,
            outline = colors.borderStrong, outlineVariant = colors.borderSubtle, error = colors.textDanger,
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors, LocalAppType provides AppType()) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
