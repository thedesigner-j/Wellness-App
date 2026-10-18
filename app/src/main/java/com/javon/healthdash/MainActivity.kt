package com.javon.healthdash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javon.healthdash.ui.nav.AppNav
import com.javon.healthdash.ui.theme.DarkColors
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.HealthTheme
import com.javon.healthdash.ui.theme.LightColors
import com.javon.healthdash.ui.theme.Theme
import com.javon.healthdash.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = HealthApp.container(this)
        setContent {
            val prefs by container.prefs.state.collectAsStateWithLifecycle()
            val dark = when (prefs.themeMode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> isSystemInDarkTheme()
            }
            enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(DarkColors.bgCanvas.toArgb()) else SystemBarStyle.light(LightColors.bgCanvas.toArgb(), DarkColors.bgCanvas.toArgb()),
                navigationBarStyle = if (dark) SystemBarStyle.dark(DarkColors.bgSurface.toArgb()) else SystemBarStyle.light(LightColors.bgSurface.toArgb(), DarkColors.bgSurface.toArgb()),
            )
            HealthTheme(dark = dark) { AppNav(container) }
        }
    }
}

/** Shown from Health Connect's permission screen: how this app uses health data. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HealthTheme(dark = true) {
                Column(Modifier.fillMaxSize().padding(Dimens.spacing2xl), verticalArrangement = Arrangement.spacedBy(Dimens.spacingLg)) {
                    Text("How Health Dashboard uses your data", style = Theme.type.headingH1, color = Theme.colors.textPrimary)
                    Text(
                        "This is a personal app. It only reads glucose, steps, workouts, heart rate, sleep, VO2 max, weight and body fat " +
                            "from Health Connect to compute your dashboard on this phone. It never writes to Health Connect, has no account " +
                            "or server, and sends no health data anywhere. Meal photos are sent to Claude only to name the foods.",
                        style = Theme.type.bodyL, color = Theme.colors.textSecondary,
                    )
                }
            }
        }
    }
}
