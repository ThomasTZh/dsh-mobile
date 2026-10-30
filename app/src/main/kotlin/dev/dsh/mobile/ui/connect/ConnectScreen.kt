package dev.dsh.mobile.ui.connect

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.AppGraph
import dev.dsh.mobile.data.ProfilesFile
import dev.dsh.mobile.data.ServerProfile
import dev.dsh.mobile.net.LanDiscovery
import dev.dsh.mobile.net.LocalNetworkPolicy
import dev.dsh.mobile.net.PinRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun ConnectScreen(
    graph: AppGraph,
    profiles: ProfilesFile,
    onOpenScanner: () -> Unit,
    onConnect: (ServerProfile, String?, Boolean) -> Unit,
    onSelect: (ServerProfile) -> Unit,
    onForget: (ServerProfile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showManual by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<LanDiscovery.Candidate>>(emptyList()) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    var forgetTarget by remember { mutableStateOf<ServerProfile?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ">_",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("DSH Mobile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "DeepSeek Harness on your local network",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        if (profiles.profiles.isNotEmpty()) {
            Text("Saved servers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            profiles.profiles.sortedByDescending { it.lastUsedAt }.forEach { profile ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { onSelect(profile) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (profile.id == profiles.selectedProfileId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(5.dp),
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                profile.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { forgetTarget = profile }) { Text("Forget") }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        Button(
            onClick = onOpenScanner,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text("Scan QR code")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { showManual = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text("Enter address")
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = {
                if (discovering) return@TextButton
                discovering = true
                discoveryError = null
                found = emptyList()
                scope.launch {
                    val result = withContext(Dispatchers.IO) { graph.lanDiscovery.discover() }
                    found = result
                    discovering = false
                    if (result.isEmpty()) {
                        discoveryError = "No DSH Pocket server found on this Wi-Fi. Check that dsh web is running and the PC firewall allows port 3081."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (discovering) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Searching your Wi-Fi…")
            } else {
                Text("Find on my Wi-Fi")
            }
        }

        if (found.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Found on this network", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            found.forEach { candidate ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clickable {
                            val profile = ServerProfile(
                                id = UUID.randomUUID().toString(),
                                name = "DSH on ${candidate.host}",
                                baseUrl = candidate.baseUrl,
                            )
                            onConnect(profile, null, true)
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(candidate.host, style = MaterialTheme.typography.bodyLarge)
                            Text(candidate.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Connect", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        discoveryError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Local network only. The phone must be on the same Wi-Fi as the PC, and dsh web with DSH Pocket must be running there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showManual) {
        ManualServerSheet(
            onDismiss = { showManual = false },
            onConnect = { profile, pin, remember ->
                showManual = false
                onConnect(profile, pin, remember)
            },
        )
    }

    forgetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${target.name}?") },
            text = { Text("The saved PIN and the session cookie for this server will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    forgetTarget = null
                    onForget(target)
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualServerSheet(
    onDismiss: () -> Unit,
    onConnect: (ServerProfile, String?, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var address by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text("Connect to DSH", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Use the LAN address shown in DSH → Settings → Phone access. The default port is 3081.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; error = null },
                label = { Text("Address") },
                placeholder = { Text("192.168.1.15:3081") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                trailingIcon = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrBlank()) address = text.trim()
                    }) { Text("Paste") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.take(PinRules.MAX_LENGTH); error = null },
                label = { Text("LAN PIN (optional)") },
                supportingText = { Text("Leave blank to enter it on the next screen.") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val normalized = LocalNetworkPolicy.normalize(address)
                    if (!normalized.ok) {
                        error = normalized.error
                    } else {
                        onConnect(
                            ServerProfile(
                                id = UUID.randomUUID().toString(),
                                name = "DSH on ${normalized.baseUrl!!.substringAfter("://").substringBefore(":")}",
                                baseUrl = normalized.baseUrl,
                            ),
                            pin.trim().takeIf { it.isNotBlank() },
                            remember,
                        )
                    }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = remember, onCheckedChange = { remember = it })
                Text("Remember PIN", style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = {
                    val normalized = LocalNetworkPolicy.normalize(address)
                    if (!normalized.ok) {
                        error = normalized.error
                        return@Button
                    }
                    if (pin.isNotBlank() && PinRules.validate(pin) != null) {
                        error = PinRules.validate(pin)
                        return@Button
                    }
                    onConnect(
                        ServerProfile(
                            id = UUID.randomUUID().toString(),
                            name = "DSH on ${normalized.baseUrl!!.substringAfter("://").substringBefore(":")}",
                            baseUrl = normalized.baseUrl,
                        ),
                        pin.trim().takeIf { it.isNotBlank() },
                        remember,
                    )
                }) { Text("Connect") }
            }
        }
    }
}
