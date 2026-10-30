package dev.dsh.mobile.ui

import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.AppGraph
import dev.dsh.mobile.data.ServerProfile
import dev.dsh.mobile.net.LocalNetworkPolicy
import dev.dsh.mobile.service.NotificationBridgeService
import dev.dsh.mobile.service.NotificationPublisher
import dev.dsh.mobile.ui.browser.BrowserScreen
import dev.dsh.mobile.ui.connect.ConnectScreen
import dev.dsh.mobile.ui.scanner.QrScannerScreen
import kotlinx.coroutines.launch
import java.util.UUID

private sealed interface AppRoute {
    data object Boot : AppRoute
    data object Connect : AppRoute
    data object Scanner : AppRoute
    data class Browser(val profile: ServerProfile, val pin: String?) : AppRoute
}

@Composable
fun AppRoot(graph: AppGraph, activity: ComponentActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileFile by graph.profileStore.state.collectAsState()
    val settings by graph.settingsStore.state.collectAsState()
    var route by remember { mutableStateOf<AppRoute>(AppRoute.Boot) }

    LaunchedEffect(Unit) {
        graph.profileStore.ensureLoaded()
        graph.settingsStore.reload()
        val loaded = graph.profileStore.state.value
        val selected = loaded.selected()
        val valid = selected != null && LocalNetworkPolicy.normalize(selected.baseUrl).ok
        route = if (graph.settingsStore.state.value.autoConnect && valid) {
            AppRoute.Browser(selected, null)
        } else {
            AppRoute.Connect
        }
    }

    fun exitToConnect() {
        NotificationBridgeService.stop(context)
        route = AppRoute.Connect
    }

    fun forget(profile: ServerProfile) {
        scope.launch {
            graph.secretStore.removePin(profile.id)
            graph.profileStore.remove(profile.id)
            NotificationBridgeService.stop(context)
            NotificationPublisher.cancelAll(context)
            CookieManager.getInstance().setCookie(profile.baseUrl, "dsh_pocket_token=; Path=/; Max-Age=0")
            CookieManager.getInstance().flush()
            route = AppRoute.Connect
        }
    }

    when (val current = route) {
        AppRoute.Boot -> BootScreen()
        AppRoute.Connect -> ConnectScreen(
            graph = graph,
            profiles = profileFile,
            onOpenScanner = { route = AppRoute.Scanner },
            onConnect = { profile, pin, remember ->
                scope.launch {
                    val existing = graph.profileStore.state.value.profiles
                        .firstOrNull { it.baseUrl == profile.baseUrl }
                    val actual = if (existing != null) existing.copy(name = profile.name) else profile
                    graph.profileStore.upsert(actual)
                    if (remember && pin != null) {
                        if (graph.secretStore.putPin(actual.id, pin)) {
                            graph.profileStore.markPinSaved(actual.id, true)
                        }
                    } else if (!remember) {
                        graph.secretStore.removePin(actual.id)
                        graph.profileStore.markPinSaved(actual.id, false)
                    }
                    graph.profileStore.select(actual.id)
                    route = AppRoute.Browser(actual, if (remember) null else pin)
                }
            },
            onSelect = { profile ->
                scope.launch {
                    graph.profileStore.select(profile.id)
                    route = AppRoute.Browser(profile, null)
                }
            },
            onForget = { profile -> forget(profile) },
        )
        AppRoute.Scanner -> QrScannerScreen(
            onValid = { baseUrl, pin ->
                scope.launch {
                    val existing = graph.profileStore.state.value.profiles.firstOrNull { it.baseUrl == baseUrl }
                    val profile = existing ?: ServerProfile(
                        id = UUID.randomUUID().toString(),
                        name = "DSH on ${baseUrl.substringAfter("://").substringBefore(":")}",
                        baseUrl = baseUrl,
                    )
                    graph.profileStore.upsert(profile)
                    if (pin != null) {
                        if (graph.secretStore.putPin(profile.id, pin)) {
                            graph.profileStore.markPinSaved(profile.id, true)
                        }
                    }
                    graph.profileStore.select(profile.id)
                    route = AppRoute.Browser(profile, pin)
                }
            },
            onCancel = { route = AppRoute.Connect },
        )
        is AppRoute.Browser -> BrowserScreen(
            graph = graph,
            activity = activity,
            profile = current.profile,
            initialPin = current.pin,
            onExit = { exitToConnect() },
            onForget = { forget(current.profile) },
        )
    }
}

@Composable
private fun BootScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
            ) {
                Text(
                    ">_",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                )
            }
            Text("DSH Mobile", style = MaterialTheme.typography.titleMedium)
            CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
        }
    }
}
