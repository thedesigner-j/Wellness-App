package com.javon.healthdash.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javon.healthdash.AppContainer
import com.javon.healthdash.data.SecureKeys
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.Heading
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.Notice
import com.javon.healthdash.ui.components.NoticeType
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.StepDots
import com.javon.healthdash.ui.components.Toggle
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Shapes
import com.javon.healthdash.ui.theme.Theme
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(container: AppContainer, state: DashboardState, onFinished: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    val back = { if (step > 1) step-- }
    when (step) {
        1 -> WelcomeStep { step = 2 }
        2 -> HealthConnectStep(container, onBack = back, onNext = { step = 3 })
        3 -> DataCheckStep(container, state, onBack = back, onNext = { step = 4 })
        4 -> ApiKeysStep(container, onBack = back, onNext = { step = 5 })
        else -> TargetsStep(container, state, onBack = back) {
            container.prefs.setOnboardingDone(true)
            onFinished()
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val c = Theme.colors
    ScreenFrame(topBar = {}, footer = { AppButton("Get started", onNext, Modifier.fillMaxWidth()) }) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(Shapes.xxl).background(Brush.linearGradient(c.gradientHero)))
        StepDots(1, 5)
        Text("Steady glucose, one day at a time.", style = Theme.type.displayL, color = c.textPrimary)
        Text(
            "Your CGM, Garmin, scale and meals in one dashboard. Every number is computed on this phone by plain code — AI only names the foods in your photos.",
            style = Theme.type.bodyL, color = c.textSecondary,
        )
        Notice(NoticeType.Info, "Personal tool, not medical advice", "Discuss A1c and lab results with your doctor.")
    }
}

@Composable
private fun HealthConnectStep(container: AppContainer, onBack: () -> Unit, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { onNext() }
    val rows = listOf(
        Triple(AppIcons.glucose, "Blood glucose", "From your CGM app"),
        Triple(AppIcons.walk, "Steps", "Garmin Connect"),
        Triple(AppIcons.heart, "Heart rate, resting HR, HRV", "Garmin Connect"),
        Triple(AppIcons.sleep, "Sleep stages", "Garmin Connect"),
        Triple(AppIcons.run, "Exercise sessions", "Type, distance, HR"),
        Triple(AppIcons.trends, "VO2 max", "If Garmin shares it"),
        Triple(AppIcons.strength, "Weight, body fat", "Renpho / Fitindex via Samsung Health"),
    )
    ScreenFrame(
        topBar = { SmallTopBar("Connect data", onBack) },
        footer = {
            if (container.health.available) {
                AppButton("Open Health Connect", { launcher.launch(container.health.permissions) }, Modifier.fillMaxWidth())
            } else {
                AppButton("Use sample data", {
                    scope.launch { container.prefs.setSampleData(true); container.sample.load(); onNext() }
                }, Modifier.fillMaxWidth())
            }
        },
    ) {
        StepDots(2, 5)
        Heading("Allow Health Connect", "Read-only. Data never leaves your phone. This app asks to read:")
        if (!container.health.available) {
            EmptyState("Health Connect isn't available", "Update or enable Health Connect in Settings, then come back. You can explore with sample data meanwhile.")
        }
        RowGroup {
            rows.forEach { (icon, title, sub) -> ListRow(title, subtitle = sub, icon = icon, chevron = false) }
        }
    }
}

@Composable
private fun DataCheckStep(container: AppContainer, state: DashboardState, onBack: () -> Unit, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (!container.prefs.state.value.sampleData) runCatching { container.syncNow() }
        syncing = false
    }
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    ScreenFrame(
        topBar = { SmallTopBar("Connect data", onBack) },
        footer = {
            AppButton("Continue", onNext, Modifier.fillMaxWidth())
            if (!prefs.sampleData) {
                AppButton("Use sample data for now", {
                    scope.launch { container.prefs.setSampleData(true); container.sample.load() }
                }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
            }
        },
    ) {
        StepDots(3, 5)
        Heading("Checking what arrives", if (syncing) "Reading the last 90 days from Health Connect…" else "Here is what Health Connect sent in the last 24 hours.")
        SourceRows(state)
        if (prefs.sampleData) {
            Notice(NoticeType.Info, "Sample data is on", "Screens show generated data so you can explore. Turn it off in Settings when your real data is flowing.")
        } else if (!syncing && state.syncStates.none { it.type == "Glucose" && it.countLast24h > 0 }) {
            Notice(NoticeType.Info, "No glucose yet", "Turn on Health Connect sharing in your CGM app. Official apps can sync about 3 hours late.")
        }
    }
}

@Composable
private fun ApiKeysStep(container: AppContainer, onBack: () -> Unit, onNext: () -> Unit) {
    var claude by remember { mutableStateOf("") }
    var usda by remember { mutableStateOf("") }
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val present by container.keys.present.collectAsStateWithLifecycle()
    val save = {
        if (claude.isNotBlank()) container.keys.put(SecureKeys.CLAUDE, claude)
        if (usda.isNotBlank()) container.keys.put(SecureKeys.USDA, usda)
        onNext()
    }
    ScreenFrame(
        topBar = { SmallTopBar("Connect data", onBack) },
        footer = {
            AppButton("Continue", save, Modifier.fillMaxWidth())
            AppButton("Skip for now", onNext, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
        },
    ) {
        StepDots(4, 5)
        Heading("Add your API keys", "Used only for food photo recognition (Claude) and nutrition lookup (USDA FoodData Central). Stored encrypted with Android Keystore.")
        AppTextField("Claude API key", claude, { claude = it }, secret = true,
            placeholder = if (present[SecureKeys.CLAUDE] == true) "Saved · enter to replace" else "sk-ant-…",
            helper = "Food photos only · model set in Settings")
        AppTextField("USDA FoodData Central key", usda, { usda = it }, secret = true,
            placeholder = if (present[SecureKeys.USDA] == true) "Saved · enter to replace" else "Free key from fdc.nal.usda.gov",
            helper = "Free key from fdc.nal.usda.gov")
        RowGroup {
            ListRow("AI rewording of summaries", subtitle = "Off by default. Template text always works without AI.", icon = AppIcons.ai, chevron = false,
                trailingContent = { Toggle(prefs.aiRewording, { container.prefs.setAiRewording(it) }) })
        }
    }
}

@Composable
private fun TargetsStep(container: AppContainer, state: DashboardState, onBack: () -> Unit, onFinish: () -> Unit) {
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onFinish() }
    val v = state.targetValues
    ScreenFrame(
        topBar = { SmallTopBar("Your targets", onBack) },
        footer = { AppButton("Finish setup", { notif.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth()) },
    ) {
        StepDots(5, 5)
        Heading("Set your targets", "Every target is editable later in Settings › Targets.")
        RowGroup {
            ListRow("Tight range", subtitle = "Time in range target", trailing = "${v["tightLow"]?.toInt() ?: 70}–${v["tightHigh"]?.toInt() ?: 140} mg/dL", icon = AppIcons.glucose, chevron = false)
            ListRow("A1c goal", subtitle = "Primary goal", trailing = "< ${v["a1cGoal"] ?: 5.7}%", icon = AppIcons.lab, chevron = false)
            ListRow("Post-workout window", subtitle = "Counts as Training", trailing = "${v["trainingAfterMin"]?.toInt() ?: 60} min", icon = AppIcons.run, chevron = false)
            ListRow("Data sufficiency", subtitle = "Minimum coverage", trailing = "${v["minCoveragePct"]?.toInt() ?: 70}%", icon = AppIcons.info, chevron = false)
            ListRow("Post-meal walk", subtitle = "Steps within ${v["walkWindowMin"]?.toInt() ?: 45} min", trailing = "%,d".format((v["walkSteps"] ?: 1000.0).toInt()), icon = AppIcons.walk, chevron = false)
        }
    }
}
