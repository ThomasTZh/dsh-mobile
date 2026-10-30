package dev.dsh.mobile.ui.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dsh.mobile.BuildConfig
import dev.dsh.mobile.core.AppGraph
import dev.dsh.mobile.data.ServerProfile
import dev.dsh.mobile.net.PinRules
import dev.dsh.mobile.service.BridgePhase
import dev.dsh.mobile.service.BridgeRuntime
import dev.dsh.mobile.service.NotificationBridgeService
import dev.dsh.mobile.ui.connect.PinSheet
import dev.dsh.mobile.web.Injection
import dev.dsh.mobile.web.UiTweaks
import dev.dsh.mobile.web.WebBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private enum class BrowserPhase { CONNECTING, NEEDS_PIN, LOADING, READY, ERROR }

private class BrowserUiState {
    var phase by mutableStateOf(BrowserPhase.CONNECTING)
    var step by mutableStateOf("Checking server…")
    var error by mutableStateOf<String?>(null)
    var pinError by mutableStateOf<String?>(null)
    var lockoutSeconds by mutableStateOf(0L)
    var pinBusy by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var progress by mutableIntStateOf(0)
    var socket by mutableStateOf("unknown")
    var canGoBack by mutableStateOf(false)
    var webViewAlive by mutableStateOf(true)
}

/**
 * The actual DSH UI, framed by native chrome. Everything security-relevant is
 * decided here: only the configured origin loads in-app, external links leave
 * the app, cookies are seeded natively, and the alert service is started only
 * after the page is confirmed to be the real DSH app.
 */
@Composable
fun BrowserScreen(
    graph: AppGraph,
    activity: ComponentActivity,
    profile: ServerProfile,
    initialPin: String?,
    onExit: () -> Unit,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val settings by graph.settingsStore.state.collectAsState()
    val bridgeStatus by BridgeRuntime.status.collectAsState()
    val scope = rememberCoroutineScope()
    val ui = remember { BrowserUiState() }
    var webViewGen by remember { mutableIntStateOf(0) }
    var connectNonce by remember { mutableIntStateOf(0) }
    var pinOverride by remember { mutableStateOf(initialPin) }
    var showPinSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var webLoginFallback by remember { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback == null) return@rememberLauncherForActivityResult
        if (uris.isNullOrEmpty()) callback.onReceiveValue(null) else callback.onReceiveValue(uris.toTypedArray())
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableAlerts(graph, context, profile, ui) { settingsMessage = it }
        } else {
            settingsMessage = "Notifications are disabled for DSH Mobile. Enable them in system settings to get alerts."
        }
    }

    val background = MaterialTheme.colorScheme.background.toArgb()

    val webView = remember(webViewGen) {
        createBrowserWebView(
            activity = activity,
            profile = profile,
            ui = ui,
            background = background,
            onShowFileChooser = { callback, params ->
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = callback
                val types = params.acceptTypes.filter { it.isNotBlank() }
                fileLauncher.launch(if (types.isEmpty()) arrayOf("*/*") else types.toTypedArray())
            },
            onLoginRequired = {
                if (!webLoginFallback) {
                    ui.phase = BrowserPhase.NEEDS_PIN
                    ui.loading = false
                }
            },
            onRendererGone = {
                ui.webViewAlive = false
                ui.phase = BrowserPhase.ERROR
                ui.error = "The web renderer crashed. Reload to reopen DSH."
            },
            onToast = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
        )
    }

    fun load(url: String) {
        ui.phase = BrowserPhase.LOADING
        ui.step = "Loading DSH…"
        ui.loading = true
        // The connection coroutine can resume on an IO worker (notably under the
        // Compose test dispatcher); every WebView call must run on its own thread.
        webView.post { webView.loadUrl(url) }
    }

    // Compose's AndroidView gives a directly hosted WebView a zero-height CSS
    // layout viewport on this device (100vh/media height queries report 0). A
    // plain FrameLayout parent restores the correct metrics.
    val webViewHolder = remember(webView) {
        FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    suspend fun doConnect(overridePin: String?) {
        ui.phase = BrowserPhase.CONNECTING
        ui.step = "Checking server…"
        ui.error = null
        ui.pinError = null
        ui.lockoutSeconds = 0
        ui.loading = true
        val cookie = CookieManager.getInstance().getCookie(profile.baseUrl)
        Log.d("DshConnect", "probe start base=${profile.baseUrl} hasCookie=${cookie != null}")
        val probe = withContext(Dispatchers.IO) { graph.pocketClient.probe(profile.baseUrl, cookie) }
        Log.d("DshConnect", "probe done reachable=${probe.reachable} needsPin=${probe.needsPin} status=${probe.status} error=${probe.error}")
        if (!probe.reachable) {
            ui.phase = BrowserPhase.ERROR
            ui.error = probe.error ?: "Can't reach ${profile.baseUrl}."
            ui.loading = false
            return
        }
        if (!probe.needsPin) {
            load(profile.baseUrl + "/")
            return
        }
        val pin = overridePin ?: graph.secretStore.getPin(profile.id)
        if (pin.isNullOrBlank()) {
            ui.phase = BrowserPhase.NEEDS_PIN
            ui.loading = false
            return
        }
        ui.step = "Unlocking…"
        ui.pinBusy = true
        Log.d("DshConnect", "login start base=${profile.baseUrl}")
        val login = withContext(Dispatchers.IO) { graph.pocketClient.login(profile.baseUrl, pin) }
        Log.d("DshConnect", "login done ok=${login.ok} rejected=${login.rejected} locked=${login.lockedSeconds} error=${login.error}")
        ui.pinBusy = false
        when {
            login.ok && login.cookie != null -> {
                seedCookie(profile.baseUrl, login.cookie)
                graph.profileStore.markPinSaved(profile.id, true)
                load(profile.baseUrl + "/?dsh-pocket-auth=1")
            }
            login.lockedSeconds != null -> {
                ui.phase = BrowserPhase.NEEDS_PIN
                ui.lockoutSeconds = login.lockedSeconds
                ui.pinError = "Too many attempts. Try again in ${ui.lockoutSeconds}s."
            }
            login.rejected -> {
                if (overridePin == null) {
                    graph.secretStore.removePin(profile.id)
                    graph.profileStore.markPinSaved(profile.id, false)
                }
                ui.phase = BrowserPhase.NEEDS_PIN
                ui.pinError = login.error
            }
            else -> {
                ui.phase = BrowserPhase.ERROR
                ui.error = login.error ?: "Could not unlock the server."
            }
        }
    }

    LaunchedEffect(webView, connectNonce) {
        webLoginFallback = false
        doConnect(pinOverride)
    }

    LaunchedEffect(ui.phase) {
        if (ui.phase == BrowserPhase.NEEDS_PIN) showPinSheet = true
    }

    LaunchedEffect(ui.phase, settings.alertsEnabled) {
        if (ui.phase == BrowserPhase.READY && settings.alertsEnabled &&
            graph.secretStore.getPin(profile.id) != null
        ) {
            NotificationBridgeService.start(context)
        }
    }

    LaunchedEffect(webView, settings.textZoom) {
        webView.settings.textZoom = settings.textZoom
    }

    DisposableEffect(settings.keepAwake) {
        if (settings.keepAwake) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (settings.keepAwake) activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(settings.immersive) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (settings.immersive) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(webView) {
        onDispose {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            webView.destroy()
        }
    }

    BackHandler(enabled = true) {
        when {
            showMenu -> showMenu = false
            showSettings -> showSettings = false
            showPinSheet -> showPinSheet = false
            webView.canGoBack() -> webView.goBack()
            else -> activity.moveTaskToBack(true)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (settings.immersive) Modifier
                    else Modifier.statusBarsPadding().navigationBarsPadding(),
                )
                .imePadding(),
        ) {
            if (!settings.immersive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (webView.canGoBack()) webView.goBack() else activity.moveTaskToBack(true)
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor(ui, bridgeStatus.phase)),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Reload") }, onClick = {
                                showMenu = false
                                webView.reload()
                            })
                            DropdownMenuItem(text = { Text("Reconnect") }, onClick = {
                                showMenu = false
                                ui.phase = BrowserPhase.CONNECTING
                                connectNonce += 1
                            })
                            DropdownMenuItem(text = { Text("Copy address") }, onClick = {
                                showMenu = false
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("DSH", profile.baseUrl))
                            })
                            DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                                showMenu = false
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profile.baseUrl)))
                                }
                            })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = {
                                showMenu = false
                                showSettings = true
                            })
                            DropdownMenuItem(text = { Text("Disconnect") }, onClick = {
                                showMenu = false
                                onExit()
                            })
                        }
                    }
                }
                if (ui.loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                key(webViewGen) {
                    AndroidView(factory = { webViewHolder }, modifier = Modifier.fillMaxSize())
                }

                if (ui.phase == BrowserPhase.CONNECTING) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(14.dp))
                            Text(ui.step, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (ui.phase == BrowserPhase.ERROR) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Can't open DSH", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                ui.error ?: "The server did not respond.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = {
                                ui.error = null
                                ui.phase = BrowserPhase.CONNECTING
                                connectNonce += 1
                            }) { Text("Retry") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                ui.error = null
                                load(profile.baseUrl + "/")
                            }) { Text("Load anyway") }
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = onExit) { Text("Change server") }
                            if (!ui.webViewAlive) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = {
                                    webViewGen += 1
                                    ui.webViewAlive = true
                                    ui.error = null
                                    ui.phase = BrowserPhase.CONNECTING
                                    connectNonce += 1
                                }) { Text("Recreate page") }
                            }
                        }
                    }
                }

                if (ui.phase == BrowserPhase.NEEDS_PIN && !showPinSheet) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Session locked", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showPinSheet = true }) { Text("Unlock") }
                    }
                }

                if (settings.immersive) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                        tonalElevation = 2.dp,
                    ) {
                        IconButton(onClick = {
                            graph.settingsStore.update { it.copy(immersive = false) }
                        }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Exit immersive mode")
                        }
                    }
                }
            }
        }
    }

    if (showPinSheet && ui.phase == BrowserPhase.NEEDS_PIN) {
        PinSheet(
            busy = ui.pinBusy,
            error = ui.pinError,
            lockoutSeconds = ui.lockoutSeconds,
            onDismiss = { showPinSheet = false },
            onUseWebLogin = {
                webLoginFallback = true
                showPinSheet = false
                load(profile.baseUrl + "/")
            },
            onSubmit = { pin, remember ->
                val problem = PinRules.validate(pin)
                if (problem != null) {
                    ui.pinError = problem
                } else {
                    showPinSheet = false
                    if (remember) {
                        if (graph.secretStore.putPin(profile.id, pin)) {
                            scope.launch { graph.profileStore.markPinSaved(profile.id, true) }
                        }
                    } else {
                        graph.secretStore.removePin(profile.id)
                        scope.launch { graph.profileStore.markPinSaved(profile.id, false) }
                    }
                    pinOverride = pin.trim()
                    ui.phase = BrowserPhase.CONNECTING
                    connectNonce += 1
                }
            },
        )
    }

    if (showSettings) {
        BrowserSettingsSheet(
            activity = activity,
            graph = graph,
            profile = profile,
            settings = settings,
            bridgeStatus = bridgeStatus,
            message = settingsMessage,
            onDismiss = { showSettings = false },
            onToggleAlerts = { enabled ->
                settingsMessage = null
                if (enabled) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        enableAlerts(graph, context, profile, ui) { settingsMessage = it }
                    }
                } else {
                    graph.settingsStore.update { it.copy(alertsEnabled = false) }
                    NotificationBridgeService.stop(context)
                }
            },
            onTestNotification = {
                dev.dsh.mobile.service.NotificationPublisher.postTest(context)
                settingsMessage = "Test notification sent."
            },
            onBattery = { requestBatteryExemption(activity) },
            onForget = {
                showSettings = false
                onForget()
            },
            onUnlock = { showPinSheet = true },
        )
    }
}

private fun enableAlerts(
    graph: AppGraph,
    context: Context,
    profile: ServerProfile,
    ui: BrowserUiState,
    onMessage: (String) -> Unit,
) {
    if (graph.secretStore.getPin(profile.id).isNullOrBlank()) {
        onMessage("Save the LAN PIN first (enable Remember PIN and reconnect) so alerts can authenticate.")
        ui.phase = BrowserPhase.NEEDS_PIN
        return
    }
    graph.settingsStore.update { it.copy(alertsEnabled = true) }
    if (!NotificationBridgeService.start(context)) {
        onMessage("Background alerts could not start. Notification permission may be missing.")
    }
}

private fun seedCookie(baseUrl: String, cookie: String) {
    if (cookie.any { it == '\r' || it == '\n' }) return
    CookieManager.getInstance().setCookie(
        baseUrl,
        "dsh_pocket_token=$cookie; Path=/; Max-Age=2592000",
    )
    CookieManager.getInstance().flush()
}

private fun statusColor(ui: BrowserUiState, phase: BridgePhase): Color = when {
    ui.phase == BrowserPhase.ERROR -> Color(0xFFD14343)
    ui.phase == BrowserPhase.NEEDS_PIN -> Color(0xFFE0A400)
    ui.socket == "closed" || ui.socket == "error" -> Color(0xFFE0A400)
    phase == BridgePhase.UNAUTHORIZED || phase == BridgePhase.BRIDGE_MISSING -> Color(0xFFE0A400)
    ui.phase == BrowserPhase.READY -> Color(0xFF2FA45C)
    else -> Color(0xFFE0A400)
}

private fun requestBatteryExemption(activity: ComponentActivity) {
    val intent = Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${activity.packageName}"),
    )
    try {
        activity.startActivity(intent)
    } catch (_: Exception) {
        activity.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun createBrowserWebView(
    activity: ComponentActivity,
    profile: ServerProfile,
    ui: BrowserUiState,
    background: Int,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    onLoginRequired: () -> Unit,
    onRendererGone: () -> Unit,
    onToast: (String) -> Unit,
): WebView {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    val webView = WebView(activity)
    webView.setBackgroundColor(background)
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        useWideViewPort = false
        loadWithOverviewMode = false
        builtInZoomControls = false
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = true
        userAgentString = userAgentString + " DshMobile/0.1"
    }

    val server = profile.baseUrl.toHttpUrlOrNull()
    val serverHost = server?.host
    val serverPort = server?.port

    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, Injection.documentStart, setOf(profile.baseUrl))
        }
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, UiTweaks.script, setOf(profile.baseUrl))
        }
    }

    webView.addJavascriptInterface(
        WebBridge(
            context = activity,
            onSocketState = { state -> ui.socket = state },
            onLoginRequired = onLoginRequired,
        ),
        "DshMobile",
    )

    var transitionReloads = 0

    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            ui.loading = true
            ui.progress = 0
            if (ui.phase != BrowserPhase.NEEDS_PIN) ui.phase = BrowserPhase.LOADING
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (view == null) return
            view.evaluateJavascript("(function(){try{window.__dshMobileFit&&window.__dshMobileFit();}catch(e){}})()", null)
            view.evaluateJavascript(Injection.LOGIN_PROBE) { raw ->
                when (raw?.trim()?.trim('"')) {
                    "login" -> {
                        ui.loading = false
                        onLoginRequired()
                    }
                    "transition" -> {
                        if (transitionReloads < 2) {
                            transitionReloads += 1
                            view.postDelayed({ view.loadUrl(profile.baseUrl + "/") }, 350)
                        } else {
                            ui.phase = BrowserPhase.READY
                            ui.loading = false
                        }
                    }
                    else -> {
                        ui.phase = BrowserPhase.READY
                        ui.loading = false
                        ui.canGoBack = view.canGoBack()
                    }
                }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) return false
            val parsed = url.toHttpUrlOrNull()
            if (parsed == null) {
                openExternal(activity, url)
                return true
            }
            if (parsed.scheme != "http" && parsed.scheme != "https") {
                openExternal(activity, url)
                return true
            }
            if (parsed.host == serverHost && parsed.port == serverPort) return false
            openExternal(activity, url)
            return true
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            ui.canGoBack = view?.canGoBack() ?: false
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                ui.phase = BrowserPhase.ERROR
                ui.loading = false
                ui.error = error?.description?.toString() ?: "The page could not be loaded."
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame == true) {
                val code = errorResponse?.statusCode ?: 0
                if (code == 401 || code == 403) {
                    onLoginRequired()
                } else if (code >= 500) {
                    ui.phase = BrowserPhase.ERROR
                    ui.loading = false
                    ui.error = "The server returned HTTP $code."
                }
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
            handler?.cancel()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            ui.webViewAlive = false
            onRendererGone()
            return true
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            ui.progress = newProgress
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null || fileChooserParams == null) return false
            onShowFileChooser(filePathCallback, fileChooserParams)
            return true
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
            request?.deny()
        }

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
            if (BuildConfig.DEBUG && message != null) {
                Log.d("DshWeb", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            }
            return true
        }
    }

    webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
            onToast("This download is not supported in the app.")
            return@DownloadListener
        }
        if (parsed.host != serverHost || parsed.port != serverPort) {
            openExternal(activity, url)
            return@DownloadListener
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType)
            request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Cookie", it)
            }
            request.setTitle(fileName)
            request.setDescription("Downloading from DSH")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            onToast("Downloading $fileName")
        } catch (_: Exception) {
            onToast("The download could not be started.")
        }
    })

    return webView
}

private fun openExternal(activity: ComponentActivity, url: String) {
    val intent = when {
        url.startsWith("mailto:") || url.startsWith("tel:") -> Intent(Intent.ACTION_VIEW, Uri.parse(url))
        else -> url.toHttpUrlOrNull()?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it.toString())) } ?: return
    }
    runCatching {
        activity.startActivity(Intent.createChooser(intent, null))
    }
}
