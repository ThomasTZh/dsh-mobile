package dev.dsh.mobile.ui.browser

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.BuildConfig
import dev.dsh.mobile.core.AppGraph
import dev.dsh.mobile.data.AppSettings
import dev.dsh.mobile.data.ServerProfile
import dev.dsh.mobile.service.BridgePhase
import dev.dsh.mobile.service.BridgeStatus
import dev.dsh.mobile.service.NotificationBridgeService
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsSheet(
    activity: ComponentActivity,
    graph: AppGraph,
    profile: ServerProfile,
    settings: AppSettings,
    bridgeStatus: BridgeStatus,
    message: String?,
    onDismiss: () -> Unit,
    onToggleAlerts: (Boolean) -> Unit,
    onTestNotification: () -> Unit,
    onBattery: () -> Unit,
    onForget: () -> Unit,
    onUnlock: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var portText by remember(settings.bridgePort) { mutableStateOf(settings.bridgePort.toString()) }
    var portError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Text(
                profile.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(bridgeColor(bridgeStatus.phase)),
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Background alerts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        bridgeText(bridgeStatus),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.alertsEnabled, onCheckedChange = onToggleAlerts)
            }
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTestNotification) { Text("Test notification") }
                OutlinedButton(onClick = onUnlock) { Text("Unlock / change PIN") }
            }

            Spacer(Modifier.height(18.dp))
            Text("Alert types", style = MaterialTheme.typography.titleSmall)
            ToggleRow("Approval needed", settings.alertApproval) {
                graph.settingsStore.update { s -> s.copy(alertApproval = it) }
            }
            ToggleRow("Questions", settings.alertQuestion) {
                graph.settingsStore.update { s -> s.copy(alertQuestion = it) }
            }
            ToggleRow("Session finished", settings.alertDone) {
                graph.settingsStore.update { s -> s.copy(alertDone = it) }
            }
            ToggleRow("Errors", settings.alertError) {
                graph.settingsStore.update { s -> s.copy(alertError = it) }
            }
            ToggleRow("Hide details on lock screen", settings.hideDetails) {
                graph.settingsStore.update { s -> s.copy(hideDetails = it) }
            }

            Spacer(Modifier.height(14.dp))
            Text("Reading", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${settings.textZoom}%", style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = settings.textZoom.toFloat(),
                onValueChange = { value ->
                    graph.settingsStore.update { it.copy(textZoom = value.roundToInt()) }
                },
                valueRange = 80f..160f,
                steps = 7,
            )
            ToggleRow("Keep the screen awake", settings.keepAwake) {
                graph.settingsStore.update { s -> s.copy(keepAwake = it) }
            }
            ToggleRow("Full screen (immersive)", settings.immersive) {
                graph.settingsStore.update { s -> s.copy(immersive = it) }
            }

            Spacer(Modifier.height(14.dp))
            Text("Background reliability", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android may delay a background connection on some phones. Allow DSH Mobile to ignore battery optimizations to keep alerts timely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onBattery) { Text("Battery optimization settings") }

            Spacer(Modifier.height(14.dp))
            Text("Advanced", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = portText,
                onValueChange = {
                    portText = it.filter(Char::isDigit).take(5)
                    portError = null
                },
                label = { Text("Alert bridge port") },
                supportingText = { Text("Must match the bridge plugin on the PC (default 3082).") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            portError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    val parsed = portText.toIntOrNull()
                    if (parsed == null || parsed !in 1..65535) {
                        portError = "Enter a port between 1 and 65535."
                    } else {
                        graph.settingsStore.update { it.copy(bridgePort = parsed) }
                        if (settings.alertsEnabled) {
                            NotificationBridgeService.stop(activity)
                            NotificationBridgeService.start(activity)
                        }
                        portError = "Saved."
                    }
                }) { Text("Apply port") }
            }

            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onForget) {
                Text("Forget this server", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "DSH Mobile ${BuildConfig.VERSION_NAME} · local network only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Whale-girl icon by Sutera-Diffusus (MIT)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun bridgeText(status: BridgeStatus): String = when (status.phase) {
    BridgePhase.DISABLED -> "Off. Turn on to keep alerts running in the background."
    BridgePhase.CONNECTING -> "Connecting to the PC bridge…"
    BridgePhase.LIVE -> "Connected to the PC bridge."
    BridgePhase.RECONNECTING -> "Reconnecting…"
    BridgePhase.UNAUTHORIZED -> "The saved PIN was rejected by the bridge."
    BridgePhase.BRIDGE_MISSING -> "Bridge not found. Install the DSH Mobile Bridge on the PC."
    BridgePhase.PIN_UNAVAILABLE -> "The PC bridge has no pocket PIN to check."
    BridgePhase.NO_PROFILE -> "No server selected."
    BridgePhase.NO_PIN -> "A saved PIN is needed for alerts."
    BridgePhase.DISCONNECTED -> "Off."
    BridgePhase.ERROR -> status.message.ifBlank { "Alert bridge error." }
}

private fun bridgeColor(phase: BridgePhase): Color = when (phase) {
    BridgePhase.LIVE -> Color(0xFF2FA45C)
    BridgePhase.UNAUTHORIZED, BridgePhase.BRIDGE_MISSING, BridgePhase.PIN_UNAVAILABLE, BridgePhase.ERROR -> Color(0xFFD14343)
    BridgePhase.DISABLED, BridgePhase.NO_PROFILE, BridgePhase.NO_PIN, BridgePhase.DISCONNECTED -> Color(0xFF8A93A6)
    else -> Color(0xFFE0A400)
}
