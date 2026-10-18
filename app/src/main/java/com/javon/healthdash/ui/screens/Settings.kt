package com.javon.healthdash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.HOUR_MS
import com.javon.healthdash.data.DataType
import com.javon.healthdash.data.SampleData
import com.javon.healthdash.data.SecureKeys
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppCard
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.BottomNav
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.EmptyState
import com.javon.healthdash.ui.components.LargeTopBar
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.Notice
import com.javon.healthdash.ui.components.NoticeType
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SegmentedControl
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.components.SyncStatus
import com.javon.healthdash.ui.components.SyncStatusRow
import com.javon.healthdash.ui.components.Tab
import com.javon.healthdash.ui.components.Toggle
import com.javon.healthdash.ui.theme.AppIcons
import com.javon.healthdash.ui.theme.Dimens
import com.javon.healthdash.ui.theme.Theme
import com.javon.healthdash.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SourceRows(state: DashboardState) {
    val byType = state.syncStates.associateBy { it.type }
    RowGroup {
        DataType.entries.forEach { type ->
            val s = byType[type.name]
            val (status, detail) = when {
                s == null -> SyncStatus.NotConnected to "Not synced yet"
                s.error != null -> SyncStatus.NotConnected to s.error
                s.lastRecordMs == null -> SyncStatus.NotConnected to "No data received — check sharing in the source app"
                state.nowMs - s.lastRecordMs > 2 * HOUR_MS -> SyncStatus.Delayed to "Last data ${Fmt.ago(s.lastRecordMs, state.nowMs)} · ${s.countLast24h} in 24 h"
                else -> SyncStatus.Synced to "Last data ${Fmt.ago(s.lastRecordMs, state.nowMs)} · ${s.countLast24h} in 24 h"
            }
            SyncStatusRow(type.label, status, detail, s?.origins?.split(",")?.filter { it.isNotBlank() }?.joinToString(", ") { Fmt.app(it) }?.ifEmpty { "—" } ?: "—")
        }
    }
}

@Composable
fun MoreScreen(onTab: (Tab) -> Unit, onSettings: () -> Unit, onSources: () -> Unit, onTrends: () -> Unit) {
    ScreenFrame(topBar = { LargeTopBar("More", "Galaxy S26 Ultra") }, bottomBar = { BottomNav(Tab.More) { onTab(it) } }) {
        RowGroup {
            ListRow("Trends", subtitle = "Every metric vs target · sleep & HRV context", icon = AppIcons.trends, onClick = onTrends)
            ListRow("Body", subtitle = "Weight, body fat, lean mass · Phase 4", icon = AppIcons.strength, chevron = false)
            ListRow("Weekly summary", subtitle = "Phase 4", icon = AppIcons.calendar, chevron = false)
            ListRow("Doctor report", subtitle = "One-tap PDF · Phase 4", icon = AppIcons.document, chevron = false)
        }
        RowGroup {
            ListRow("Settings", subtitle = "Data, targets, keys, AI, theme", icon = AppIcons.tune, onClick = onSettings)
            ListRow("Data sources", subtitle = "Health Connect sync status", icon = AppIcons.sync, onClick = onSources)
        }
        Caption("Personal tool, not medical advice. All data stays on this phone.")
    }
}

@Composable
fun SettingsScreen(container: AppContainer, onTab: (Tab) -> Unit, onBack: () -> Unit, onSources: () -> Unit, onKeys: () -> Unit, onTargets: () -> Unit) {
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ScreenFrame(topBar = { SmallTopBar("Settings", onBack) }, bottomBar = { BottomNav(Tab.More) { onTab(it) } }) {
        RowGroup {
            ListRow("Data sources", subtitle = "Health Connect · glucose source", icon = AppIcons.sync, onClick = onSources)
            ListRow("Targets & training rules", subtitle = "Ranges, windows, plan rules, fueling table", icon = AppIcons.trends, onClick = onTargets)
        }
        RowGroup {
            ListRow("API keys & AI", subtitle = "Claude · USDA · ${prefs.claudeModel}", icon = AppIcons.lab, onClick = onKeys)
        }
        RowGroup {
            ListRow("Post-meal walk nudge", subtitle = "10 min after logging a meal", icon = AppIcons.bell, chevron = false,
                trailingContent = { Toggle(prefs.walkNudge, { container.prefs.setWalkNudge(it) }) })
            ListRow("Sample data", subtitle = "Generated data for exploring the screens", icon = AppIcons.document, chevron = false,
                trailingContent = {
                    Toggle(prefs.sampleData, { on ->
                        scope.launch {
                            container.prefs.setSampleData(on)
                            if (on) container.sample.load() else { container.sample.clear(); runCatching { container.syncNow() } }
                        }
                    })
                })
        }
        SectionHeader("Theme")
        SegmentedControl(listOf("Dark", "Light", "System"), prefs.themeMode.ordinal, { container.prefs.setThemeMode(ThemeMode.entries[it]) })
        Caption("Export & backup, notifications for unlogged meals and weekly summaries arrive in later phases.")
    }
}

@Composable
fun DataSourcesScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit, onSync: () -> Unit) {
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val glucoseOrigins = state.syncStates.firstOrNull { it.type == DataType.Glucose.name }?.origins?.split(",")?.filter { it.isNotBlank() }.orEmpty()
    ScreenFrame(topBar = { SmallTopBar("Data sources", onBack, AppIcons.sync, onSync) }) {
        Text("Read-only via Health Connect. Late data is merged when it arrives and meal responses are recomputed.", style = Theme.type.bodyM, color = Theme.colors.textSecondary)
        if (prefs.sampleData) Notice(NoticeType.Info, "Sample data is on", "Turn it off in Settings to see only your real data.")
        SourceRows(state)
        SectionHeader("Glucose source")
        if (glucoseOrigins.size <= 1) {
            Caption(if (glucoseOrigins.isEmpty()) "No app is writing glucose yet." else "Only ${Fmt.app(glucoseOrigins.first())} writes glucose. If several CGM apps write it, choose which one to trust here.")
        } else {
            RowGroup {
                (listOf<String?>(null) + glucoseOrigins).forEach { pkg ->
                    val sel = prefs.glucoseSource == pkg
                    ListRow(
                        pkg?.let { Fmt.app(it) } ?: "All apps", subtitle = pkg ?: "Combine every source", chevron = false,
                        leading = { Box(Modifier.size(20.dp).clip(CircleShape).background(if (sel) Theme.colors.bgAccent else Theme.colors.bgSurface).then(if (sel) Modifier else Modifier.border(2.dp, Theme.colors.borderStrong, CircleShape))) },
                        onClick = { container.prefs.setGlucoseSource(pkg) },
                    )
                }
            }
        }
        AppButton("Sync now", onSync, Modifier.fillMaxWidth(), style = ButtonStyle.Secondary, icon = AppIcons.sync)
        val context = androidx.compose.ui.platform.LocalContext.current
        AppButton("Open Health Connect data", {
            // Health Connect's own data browser (shows which apps are writing what).
            runCatching { context.startActivity(androidx.health.connect.client.HealthConnectClient.getHealthConnectManageDataIntent(context)) }
                .recoverCatching { context.startActivity(android.content.Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")) }
        }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost, icon = AppIcons.heart)
        Caption("Shows what Garmin, Strava and Samsung Health have written. If a type is empty there, that app isn't sharing it yet.")
    }
}

@Composable
fun ApiKeysScreen(container: AppContainer, onBack: () -> Unit) {
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val present by container.keys.present.collectAsStateWithLifecycle()
    var claude by remember { mutableStateOf("") }
    var usda by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(prefs.claudeModel) }
    var saved by remember { mutableStateOf(false) }
    ScreenFrame(
        topBar = { SmallTopBar("API keys & AI", onBack) },
        footer = {
            AppButton(if (saved) "Saved" else "Save", {
                if (claude.isNotBlank()) container.keys.put(SecureKeys.CLAUDE, claude)
                if (usda.isNotBlank()) container.keys.put(SecureKeys.USDA, usda)
                if (model.isNotBlank()) container.prefs.setClaudeModel(model)
                claude = ""; usda = ""; saved = true
            }, Modifier.fillMaxWidth())
        },
    ) {
        AppTextField("Claude API key", claude, { claude = it; saved = false }, secret = true,
            placeholder = if (present[SecureKeys.CLAUDE] == true) "Saved · ••••${container.keys.hint(SecureKeys.CLAUDE) ?: ""}" else "sk-ant-…",
            helper = "Encrypted with Android Keystore")
        AppTextField("Claude model", model, { model = it; saved = false }, helper = "Stored in config, not in code")
        AppTextField("USDA FoodData Central key", usda, { usda = it; saved = false }, secret = true,
            placeholder = if (present[SecureKeys.USDA] == true) "Saved · ••••${container.keys.hint(SecureKeys.USDA) ?: ""}" else "Free key from fdc.nal.usda.gov")
        SectionHeader("AI use")
        RowGroup {
            ListRow("Food photo recognition", subtitle = "Names foods + portions only · you confirm every result", icon = AppIcons.camera, chevron = false,
                trailingContent = { Toggle(prefs.foodRecognition, { container.prefs.setFoodRecognition(it) }) })
            ListRow("AI rewording of summaries", subtitle = "Phase 4 · numbers are checked against computed facts", icon = AppIcons.ai, chevron = false,
                trailingContent = { Toggle(prefs.aiRewording, { container.prefs.setAiRewording(it) }) })
        }
        Caption("Network calls are limited to Claude (food photos) and USDA (nutrition lookup). No analytics or tracking.")
    }
}

@Composable
fun TargetsScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<com.javon.healthdash.data.TargetDef?>(null) }
    editing?.let { def ->
        var text by remember(def.key) { mutableStateOf(Fmt.amount(state.targetValues[def.key] ?: def.default)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(def.label) },
            text = { AppTextField(def.label, text, { text = it }, suffix = def.unit, keyboardType = KeyboardType.Decimal, helper = "Default ${Fmt.amount(def.default)} ${def.unit}") },
            confirmButton = { TextButton({ text.toDoubleOrNull()?.let { v -> scope.launch { container.targets.set(def.key, v) } }; editing = null }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } },
        )
    }
    ScreenFrame(topBar = { SmallTopBar("Targets", onBack) }) {
        container.targets.defs.groupBy { it.group }.forEach { (group, defs) ->
            Text(group.uppercase(), style = Theme.type.labelS, color = Theme.colors.textTertiary)
            RowGroup {
                defs.forEach { def ->
                    ListRow(def.label, trailing = "${Fmt.amount(state.targetValues[def.key] ?: def.default)} ${def.unit}", onClick = { editing = def })
                }
            }
        }
        Caption("Every calculation reads these values. Changes apply immediately.")
    }
}

