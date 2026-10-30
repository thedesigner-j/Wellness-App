package com.javon.healthdash.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javon.healthdash.AppContainer
import com.javon.healthdash.calc.DAY_MS
import com.javon.healthdash.calc.GarminApi
import com.javon.healthdash.calc.METERS_PER_MI
import com.javon.healthdash.calc.Sport
import com.javon.healthdash.data.GarminApiSource
import com.javon.healthdash.data.SecureKeys
import com.javon.healthdash.data.sportOf
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.Fmt
import com.javon.healthdash.ui.components.AppButton
import com.javon.healthdash.ui.components.AppTextField
import com.javon.healthdash.ui.components.ButtonStyle
import com.javon.healthdash.ui.components.Caption
import com.javon.healthdash.ui.components.Heading
import com.javon.healthdash.ui.components.ListRow
import com.javon.healthdash.ui.components.MetricTiles
import com.javon.healthdash.ui.components.Notice
import com.javon.healthdash.ui.components.NoticeType
import com.javon.healthdash.ui.components.RowGroup
import com.javon.healthdash.ui.components.ScreenFrame
import com.javon.healthdash.ui.components.SectionHeader
import com.javon.healthdash.ui.components.SmallTopBar
import com.javon.healthdash.ui.theme.AppIcons
import kotlinx.coroutines.launch

/**
 * Optional Garmin Connect personal API connection. The user creates their own key on Garmin Connect
 * and pastes it here; it is stored encrypted next to the Claude and USDA keys.
 */
@Composable
fun GarminApiScreen(container: AppContainer, state: DashboardState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val present by container.keys.present.collectAsStateWithLifecycle()
    val connected = present[SecureKeys.GARMIN] == true
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val sync = state.syncStates.firstOrNull { it.type == GarminApiSource.TYPE }

    fun syncNow() = scope.launch {
        busy = true; message = null
        message = runCatching { container.garmin.sync(force = true) }.fold({ null }, { it.message ?: "Sync failed" })
        busy = false
    }

    ScreenFrame(
        topBar = { SmallTopBar("Garmin personal API", onBack) },
        footer = {
            if (key.isNotBlank()) {
                AppButton("Save key & sync", {
                    container.keys.put(SecureKeys.GARMIN, key)
                    container.keys.put(SecureKeys.GARMIN_TOKEN, "")
                    key = ""
                    syncNow()
                }, Modifier.fillMaxWidth())
            } else if (connected) {
                AppButton(if (busy) "Syncing…" else "Sync now", { if (!busy) syncNow() }, Modifier.fillMaxWidth(), style = ButtonStyle.Secondary, icon = AppIcons.sync)
            }
        },
    ) {
        Heading(
            "Full run and ride history",
            "Optional. Reads your activities straight from Garmin Connect, so runs Health Connect drops still count, with history back 90 days. Health Connect keeps working either way.",
        )
        if (!connected) {
            RowGroup {
                ListRow("1. Open Personal API Key on Garmin Connect", subtitle = "connect.garmin.com › Settings › Personal API Key", icon = AppIcons.link,
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_PAGE))) } })
                ListRow("2. Create Key", subtitle = "Accept the terms and pick an expiry (1 year means fewer renewals)", icon = AppIcons.lab, chevron = false)
                ListRow("3. Copy the key", subtitle = "Garmin only shows it once", icon = AppIcons.document, chevron = false)
                ListRow("4. Paste it below", subtitle = "Stored encrypted on this phone only", icon = AppIcons.check, chevron = false)
            }
        }
        AppTextField(
            "Garmin personal API key", key, { key = it; message = null }, secret = true,
            placeholder = if (connected) "Saved · ••••${container.keys.hint(SecureKeys.GARMIN) ?: ""}" else "Paste your key",
            helper = if (connected) "Paste a new key to replace it" else "Encrypted with Android Keystore",
        )
        message?.let { Notice(NoticeType.Info, "Couldn't sync", it) }
        if (connected) {
            val since = state.nowMs - GarminApi.HISTORY_DAYS * DAY_MS
            val rows = state.workouts.filter { it.source == GarminApi.SOURCE && it.startMs >= since }
            val runs = rows.filter { sportOf(it.exerciseType) == Sport.Run }
            val rides = rows.filter { sportOf(it.exerciseType) == Sport.Ride }
            SectionHeader("Status")
            when {
                sync == null -> Caption(if (busy) "First sync — fetching 90 days…" else "Not synced yet.")
                sync.error != null && message == null -> Notice(NoticeType.Info, "Last sync failed", sync.error)
                else -> Caption("Last synced ${Fmt.ago(sync.lastSyncMs, state.nowMs)}" + (sync.lastRecordMs?.let { " · newest activity ${Fmt.dayShort(it)}" } ?: ""))
            }
            MetricTiles(
                listOf(
                    "Activities · 90 d" to "${rows.size}",
                    "Runs" to "${runs.size} · ${Fmt.one(runs.sumOf { it.distanceM ?: 0.0 } / METERS_PER_MI)} mi",
                    "Rides" to "${rides.size}",
                ),
            )
            AppButton("Remove key", {
                scope.launch { container.garmin.disconnect() }
            }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
            Caption("Removing the key stops syncing; activities already pulled stay. Keys expire — Garmin emails a week before.")
        }
        Caption("Read-only: the app pulls activities and never sends anything to Garmin or your watch. When the same activity also comes through Health Connect, the Garmin API copy is used.")
    }
}

private const val KEY_PAGE = "https://connect.garmin.com/app/settings/personalApiToken"
