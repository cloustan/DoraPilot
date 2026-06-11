package com.dorapilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dorapilot.assistant.AliveForegroundService
import com.dorapilot.assistant.AssistantWorkScheduler
import com.dorapilot.assistant.BackendConfig
import com.dorapilot.assistant.ContextSourcesConfig
import com.dorapilot.assistant.ContextTriageScreenServer
import com.dorapilot.assistant.IntentRoutingServer
import com.dorapilot.assistant.LocalMcpBroker
import com.dorapilot.assistant.LocalOnnxRuntimeEngine
import com.dorapilot.assistant.MainBackendClient
import com.dorapilot.assistant.NativeDictationController
import com.dorapilot.assistant.SystemCapabilityScanner
import com.dorapilot.assistant.TransactionMonitorServer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }?.takeIf { it.hasVibrator() }
    }
    private val mainBackendClient = MainBackendClient()
    private val backendConfig = BackendConfig.load()
    private val localOnnxRuntimeEngine by lazy { LocalOnnxRuntimeEngine(this, backendConfig) }
    private val naturalTtsPlayer by lazy {
        com.dorapilot.assistant.NaturalTtsPlayer(
            context = this,
            config = backendConfig,
            executor = backgroundExecutor,
            emitStatus = { message -> emitTerminalStream("voice", message) }
        )
    }
    private val dictationController by lazy {
        NativeDictationController(
            context = this,
            mainHandler = android.os.Handler(mainLooper),
            evaluateJavascript = { script ->
                if (::mainWebView.isInitialized) {
                    runOnUiThread { mainWebView.evaluateJavascript(script, null) }
                }
            },
            emitStatus = { message ->
                emitTerminalStream("voice", message)
            }
        )
    }
    private val modelTreePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            importSelectedModelFolder(uri)
        } else {
            emitTerminalStream("status", "Model folder selection cancelled.")
        }
    }
    private lateinit var mainWebView: WebView
    // Conversation context lives natively only; the WebView never stores prompts or history.
    private val conversationHistory = ArrayDeque<JSONObject>()
    private val capabilityScanner by lazy { SystemCapabilityScanner(this) }
    private val appCapabilityIndexer by lazy { com.dorapilot.assistant.AppCapabilityIndexer(this) }
    private val contextTriageServer by lazy {
        ContextTriageScreenServer(
            activeScreenProvider = { buildMainScreenSnapshot() },
            foregroundPackageProvider = { packageName },
            capabilityCatalogProvider = { capabilityScanner.readCatalog() }
        )
    }
    private val intentRoutingServer by lazy {
        IntentRoutingServer(this) { uri, packageName ->
            openDeepLinkInternal(uri, packageName)
        }
    }
    private val transactionMonitorServer by lazy {
        TransactionMonitorServer(
            foregroundPackageProvider = { packageName },
            activeScreenProvider = { buildMainScreenSnapshot() }
        )
    }
    private val deviceControlServer by lazy { com.dorapilot.assistant.DeviceControlServer(this) }
    private val appActionsServer by lazy { com.dorapilot.assistant.AppActionsServer(this) }
    private val deviceWebSearchServer by lazy {
        com.dorapilot.assistant.DeviceWebSearchServer(configProvider = { backendConfig })
    }
    private val textIntelligenceServer by lazy {
        com.dorapilot.assistant.TextIntelligenceServer(
            context = this,
            backendClient = mainBackendClient,
            configProvider = { backendConfig }
        )
    }
    private val screenIntelligenceServer by lazy {
        com.dorapilot.assistant.ScreenIntelligenceServer(
            activeScreenProvider = { buildMainScreenSnapshot() },
            textIntelligence = textIntelligenceServer
        )
    }
    private val timelineIntelligenceServer by lazy {
        com.dorapilot.assistant.TimelineIntelligenceServer(
            personalContext = personalContextEngine,
            backendClient = mainBackendClient,
            configProvider = { backendConfig }
        )
    }
    private val deviceCommandRouter by lazy {
        com.dorapilot.assistant.DeviceCommandRouter(
            deviceControl = deviceControlServer,
            intentRouter = intentRoutingServer,
            appResolver = { name -> capabilityScanner.resolvePackageForLabel(name) },
            appActions = appActionsServer,
            textIntelligence = textIntelligenceServer,
            screenIntelligence = screenIntelligenceServer,
            timelineIntelligence = timelineIntelligenceServer,
            webSearch = deviceWebSearchServer,
            contactResolver = { name -> personalContextEngine.resolveContactNumber(name) },
            deviceSearchFallback = { query -> capabilityScanner.findTools(query, 8) }
        )
    }
    private val personalContextEngine by lazy {
        com.dorapilot.assistant.PersonalContextEngine(
            context = this,
            scanner = capabilityScanner,
            foregroundPackageProvider = { packageName }
        )
    }
    private val localMcpBroker by lazy {
        LocalMcpBroker(
            scanner = capabilityScanner,
            triage = contextTriageServer,
            intentRouter = intentRoutingServer,
            transactionMonitor = transactionMonitorServer,
            deviceControl = deviceControlServer,
            personalContext = personalContextEngine,
            appActions = appActionsServer,
            textIntelligence = textIntelligenceServer,
            screenIntelligence = screenIntelligenceServer,
            timelineIntelligence = timelineIntelligenceServer,
            webSearch = deviceWebSearchServer,
            appCapabilities = appCapabilityIndexer
        )
    }

    private fun composeSystemPrompt(userSystem: String, prompt: String): String {
        val baseSystem = userSystem.trim().ifBlank {
            "You are Dora, a helpful on-phone assistant that can also control this device " +
                "(flashlight, media playback, volume, opening apps, system settings) and can " +
                "answer from the user's on-device personal data when relevant. " +
                "Keep replies short and direct: 1-3 sentences or a few brief bullet points. " +
                "Answer directly and never ask the user for permission to search, look something " +
                "up, or proceed - just give the best answer you can; if you are unsure or it needs " +
                "live data you don't have, say so briefly in one sentence."
        }
        val contextSummary = runCatching { personalContextEngine.contextForPrompt(prompt) }.getOrDefault("")
        return if (contextSummary.isBlank()) baseSystem else "$baseSystem\n\n$contextSummary"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the host activity immersive so onboarding does not show status bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_main)
        mainWebView = findViewById(R.id.mainWebView)
        setupMainWebView(mainWebView)
        AliveForegroundService.ensureRunning(this)
        AssistantWorkScheduler.ensurePeriodicCapabilityScan(this)
        // Keep the phone navigation index warm without blocking the UI.
        backgroundExecutor.execute {
            runCatching {
                val stats = capabilityScanner.ensureFreshIndex()
                emitTerminalStream("system", "Navigation index ready: $stats")
                val capStats = appCapabilityIndexer.ensureFresh()
                emitTerminalStream("system", "App capability index ready: $capStats")
            }.onFailure { error ->
                emitTerminalStream("error", "Index warm-up failed: ${error.message}")
            }
        }
        requestNotificationPermissionIfNeeded()
        requestMicrophonePermissionIfNeeded()

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mainWebView.isInitialized) {
            mainWebView.evaluateJavascript(
                "if(window.refreshContextSettingsIfOpen)window.refreshContextSettingsIfOpen();",
                null
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdownNow()
        if (::mainWebView.isInitialized) {
            naturalTtsPlayer.stop()
            dictationController.destroy()
        }
        if (::mainWebView.isInitialized) {
            mainWebView.destroy()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            4101
        )
    }

    private fun requestMicrophonePermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            4102
        )
    }

    private fun setupMainWebView(webView: WebView) {
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                emitTerminalStream(
                    "js",
                    "[${consoleMessage.messageLevel().name}] ${consoleMessage.message()}"
                )
                return true
            }
        }
        webView.addJavascriptInterface(MainActivityBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/www/index.html?mode=app")
    }

    private fun appendConversationHistory(role: String, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        conversationHistory.addLast(
            JSONObject().put("role", role).put("content", text.take(2000))
        )
        while (conversationHistory.size > 24) {
            conversationHistory.removeFirst()
        }
    }

    private fun conversationHistorySnapshot(): JSONArray {
        val snapshot = JSONArray()
        conversationHistory.forEach { snapshot.put(it) }
        return snapshot
    }

    private fun emitTerminalStream(channel: String, chunk: String) {
        if (!::mainWebView.isInitialized) return
        val safeChannel = JSONObject.quote(channel)
        val safeChunk = JSONObject.quote(chunk)
        val script = "window.onTerminalStream($safeChannel, $safeChunk);"
        runOnUiThread {
            mainWebView.evaluateJavascript(script, null)
        }
    }

    private fun buildMainScreenSnapshot(): JSONObject {
        return JSONObject()
            .put("captured_at_ms", System.currentTimeMillis())
            .put("foreground_package", packageName)
            .put("source", "main_activity_webview")
    }

    private fun openDeepLinkInternal(uriString: String, packageName: String): Result<String> {
        return runCatching {
            val intent = if (uriString.startsWith("android.settings.")) {
                Intent(uriString)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
            }
            intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageName.isNotEmpty()) {
                    `package` = packageName
                }
            }
            startActivity(intent)
            "Launched deep link: $uriString"
        }
    }

    private fun openModelFileMenu() {
        modelTreePicker.launch(null)
    }

    private fun importSelectedModelFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        emitTerminalStream("local_ai", "Importing selected model folder...")
        backgroundExecutor.execute {
            runCatching {
                val copied = copyDocumentTreeToModelDir(uri)
                emitTerminalStream("local_ai", "Imported model files=$copied")
                emitTerminalStream("local_ai", "loader check=${localOnnxRuntimeEngine.inspectLocalSetup()}")
            }.onFailure { error ->
                emitTerminalStream("error", "Model folder import failed: ${error.message}")
            }
        }
    }

    private fun copyDocumentTreeToModelDir(treeUri: Uri): Int {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val targetDir = File(filesDir, backendConfig.localGenAiModelFilesDir).apply { mkdirs() }
        var copied = 0

        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val documentIdCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(mimeCol)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue

                val documentId = cursor.getString(documentIdCol)
                val displayName = cursor.getString(nameCol) ?: continue
                val expectedSize = cursor.getLong(sizeCol)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val targetFile = File(targetDir, displayName)
                if (targetFile.exists() && expectedSize > 0 && targetFile.length() == expectedSize) {
                    continue
                }

                contentResolver.openInputStream(childUri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: error("Unable to open $displayName")
                copied += 1
            }
        }
        return copied
    }

    private fun dispatchAgentAction(actionJson: String) {
        val payload = try {
            JSONObject(actionJson.trim())
        } catch (_: JSONException) {
            emitTerminalStream("error", "Invalid JSON payload.")
            return
        }
        val action = payload.optString("action", "unknown")
        emitTerminalStream("system", "Action received: $action")
        emitTerminalStream("request", payload.toString())
        when (action) {
            "mcp.list_tools" -> backgroundExecutor.execute {
                runCatching {
                    emitTerminalStream("mcp", "tools=${localMcpBroker.listTools()}")
                }.onFailure { error ->
                    emitTerminalStream("error", "mcp.list_tools failed: ${error.message}")
                }
            }
            "mcp.call_tool" -> backgroundExecutor.execute {
                val tool = payload.optString("tool", "").trim()
                if (tool.isEmpty()) {
                    emitTerminalStream("error", "mcp.call_tool requires payload.tool")
                    return@execute
                }
                val args = payload.optJSONObject("args") ?: JSONObject()
                runCatching {
                    val result = localMcpBroker.callTool(tool, args)
                    emitTerminalStream("mcp", "tool=$tool result=$result")
                }.onFailure { error ->
                    emitTerminalStream("error", "mcp.call_tool failed: ${error.message}")
                }
            }
            "agent.infer" -> backgroundExecutor.execute {
                runCatching {
                    val useLocal = payload.optBoolean("use_local", false)
                    if (useLocal) {
                        val localPayload = JSONObject(payload.toString())
                        val localResult = localOnnxRuntimeEngine.infer(localPayload)
                        emitTerminalStream("local_ai", "inference result=$localResult")
                    } else {
                        val prompt = payload.optString("prompt", "").trim()
                        appendConversationHistory("user", prompt)
                        val deviceResult = deviceCommandRouter.tryHandle(prompt)
                        if (deviceResult != null) {
                            if (deviceResult.optBoolean("ok", false)) {
                                appendConversationHistory("assistant", deviceResult.optString("output", ""))
                            }
                            emitTerminalStream("backend", "inference result=$deviceResult")
                            return@execute
                        }
                        val requestPayload = JSONObject()
                            .put("prompt", prompt)
                            .put("system", composeSystemPrompt(payload.optString("system", ""), prompt))
                            .put("history", conversationHistorySnapshot())
                            .put("endpoint", backendConfig.endpoint)
                            .put("model", backendConfig.model)
                            .put("api_key", backendConfig.apiKey)
                            .put("headers", backendConfig.headers)
                        val backendResult = mainBackendClient.infer(requestPayload)
                        if (backendResult.optBoolean("ok", false)) {
                            appendConversationHistory("assistant", backendResult.optString("output", ""))
                        }
                        emitTerminalStream("backend", "inference result=$backendResult")
                    }
                }.onFailure { error ->
                    emitTerminalStream("error", "agent.infer failed: ${error.message}")
                }
            }
            "agent.local_infer" -> backgroundExecutor.execute {
                runCatching {
                    val localPayload = JSONObject(payload.toString())
                    val localResult = localOnnxRuntimeEngine.infer(localPayload)
                    emitTerminalStream("local_ai", "inference result=$localResult")
                }.onFailure { error ->
                    emitTerminalStream("error", "agent.local_infer failed: ${error.message}")
                }
            }
            "agent.local_loader_check" -> backgroundExecutor.execute {
                runCatching {
                    val details = localOnnxRuntimeEngine.inspectLocalSetup()
                    emitTerminalStream("local_ai", "loader check=$details")
                }.onFailure { error ->
                    emitTerminalStream("error", "agent.local_loader_check failed: ${error.message}")
                }
            }
            "agent.run_task" -> {
                emitTerminalStream(
                    "status",
                    "agent.run_task remains in assistant overlay. In main app, use agent.infer / agent.local_infer."
                )
            }
            "voice.state.change" -> {
                emitTerminalStream("voice", "State changed to '${payload.optString("state", "unknown")}'")
            }
            else -> emitTerminalStream("status", "No handler yet for '$action'.")
        }
    }

    inner class MainActivityBridge {
        @JavascriptInterface
        fun dispatchAgentAction(actionJson: String) {
            this@MainActivity.dispatchAgentAction(actionJson)
        }

        @JavascriptInterface
        fun requestKeyboard() {
            runOnUiThread {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(mainWebView, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        @JavascriptInterface
        fun dismissAssistant() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("dora_action_log", text)
            clipboard.setPrimaryClip(clip)
            emitTerminalStream("status", "Copied full action log to clipboard.")
        }

        @JavascriptInterface
        fun openModelFileMenu() {
            runOnUiThread { this@MainActivity.openModelFileMenu() }
        }

        @JavascriptInterface
        fun startDictation() {
            dictationController.start()
        }

        @JavascriptInterface
        fun stopDictation() {
            dictationController.stop()
        }

        @JavascriptInterface
        fun cancelDictation() {
            dictationController.cancel()
        }

        @JavascriptInterface
        fun requestNaturalTts(text: String) {
            naturalTtsPlayer.speak(text)
        }

        @JavascriptInterface
        fun isVoiceResponsesEnabled(): Boolean {
            return backendConfig.voiceResponsesEnabled
        }

        @JavascriptInterface
        fun vibrate(timingsJson: String, amplitudesJson: String) {
            this@MainActivity.performHaptic(timingsJson, amplitudesJson)
        }

        @JavascriptInterface
        fun getContextSettings(): String {
            return runCatching { personalContextEngine.getSources().toString() }
                .getOrDefault("{\"ok\":false}")
        }

        @JavascriptInterface
        fun setContextSource(key: String, enabled: Boolean): String {
            return runCatching { personalContextEngine.setSource(key, enabled).toString() }
                .getOrDefault("{\"ok\":false}")
        }

        @JavascriptInterface
        fun requestContextPermission(key: String) {
            runOnUiThread { this@MainActivity.requestContextPermission(key) }
        }
    }

    private fun requestContextPermission(key: String) {
        when (key) {
            ContextSourcesConfig.NOTIFICATIONS, ContextSourcesConfig.MESSAGES -> runCatching {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            ContextSourcesConfig.CALENDAR -> ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_CALENDAR), 4103
            )
            ContextSourcesConfig.CONTACTS -> ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_CONTACTS), 4104
            )
            ContextSourcesConfig.USAGE -> runCatching {
                startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun performHaptic(timingsJson: String, amplitudesJson: String) {
        val vib = vibrator ?: return
        runCatching {
            val timings = JSONArray(timingsJson).let { arr ->
                LongArray(arr.length()) { arr.getLong(it).coerceAtLeast(0L) }
            }
            if (timings.isEmpty() || timings.all { it == 0L }) return

            val amplitudes = JSONArray(amplitudesJson).let { arr ->
                IntArray(arr.length()) { arr.getInt(it).coerceIn(0, 255) }
            }
            val hasAmplitudeControl = vib.hasAmplitudeControl() &&
                amplitudes.size == timings.size

            val effect = if (hasAmplitudeControl) {
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            vib.cancel()
            vib.vibrate(effect)
        }
    }
}
