package com.dorapilot.assistant

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.regex.Pattern
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class AgentAssistantSession(context: Context) : VoiceInteractionSession(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val toyboxShellManager = ToyboxShellManager()
    private val mainBackendClient = MainBackendClient()
    private val backendConfig = BackendConfig.load()
    private val localOnnxRuntimeEngine = LocalOnnxRuntimeEngine(context, backendConfig)
    private val naturalTtsPlayer = NaturalTtsPlayer(
        context = context,
        config = backendConfig,
        executor = backgroundExecutor,
        emitStatus = { message -> emitTerminalStream("voice", message) }
    )
    private val dictationController = NativeDictationController(
        context = context,
        mainHandler = mainHandler,
        evaluateJavascript = { script ->
            overlayWebView?.evaluateJavascript(script, null)
        },
        emitStatus = { message ->
            emitTerminalStream("voice", message)
        }
    )
    private val capabilityScanner = SystemCapabilityScanner(context)
    private val appCapabilityIndexer = AppCapabilityIndexer(context)
    private val httpBridgeServer = HttpBridgeServer()
    private val automationServer = AutomationServer(context)
    private val skillServer = SkillServer(context, httpBridgeServer)
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }?.takeIf { it.hasVibrator() }
    }
    private var overlayWebView: WebView? = null
    // Conversation context lives natively only; the WebView never stores prompts or history.
    private val conversationHistory = ArrayDeque<JSONObject>()
    @Volatile
    private var latestAssistDump: String = "No Assist Structure captured yet."
    @Volatile
    private var latestAssistJson: JSONObject = JSONObject()
    @Volatile
    private var latestForegroundPackage: String = ""
    private val contextTriageServer = ContextTriageScreenServer(
        activeScreenProvider = { latestAssistJson },
        foregroundPackageProvider = { latestForegroundPackage },
        capabilityCatalogProvider = { capabilityScanner.readCatalog() }
    )
    private val intentRoutingServer = IntentRoutingServer(context) { uri, packageName ->
        openDeepLinkInternal(uri, packageName)
    }
    private val transactionMonitorServer = TransactionMonitorServer(
        foregroundPackageProvider = { latestForegroundPackage },
        activeScreenProvider = { latestAssistJson }
    )
    private val deviceControlServer = DeviceControlServer(context)
    private val appActionsServer = AppActionsServer(context)
    private val deviceWebSearchServer = DeviceWebSearchServer(configProvider = { backendConfig })
    private val textIntelligenceServer = TextIntelligenceServer(
        context = context,
        backendClient = mainBackendClient,
        configProvider = { backendConfig }
    )
    private val screenIntelligenceServer = ScreenIntelligenceServer(
        activeScreenProvider = { latestAssistJson },
        textIntelligence = textIntelligenceServer
    )
    private val personalContextEngine = PersonalContextEngine(
        context = context,
        scanner = capabilityScanner,
        foregroundPackageProvider = { latestForegroundPackage }
    )
    private val timelineIntelligenceServer = TimelineIntelligenceServer(
        personalContext = personalContextEngine,
        backendClient = mainBackendClient,
        configProvider = { backendConfig }
    )
    private val deviceCommandRouter = DeviceCommandRouter(
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
    private val localMcpBroker = LocalMcpBroker(
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
        appCapabilities = appCapabilityIndexer,
        httpBridge = httpBridgeServer,
        automation = automationServer,
        skills = skillServer
    )

    @Volatile
    private var isSessionShowing: Boolean = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        isSessionShowing = true
        val wasNull = overlayWebView == null
        showOverlayShell()
        if (!wasNull) {
            mainHandler.postDelayed({
                if (isSessionShowing) {
                    overlayWebView?.evaluateJavascript("if(typeof showAssistant === 'function') showAssistant();", null)
                }
            }, 80L)
        }
    }

    override fun onHide() {
        super.onHide()
        isSessionShowing = false
        overlayWebView?.evaluateJavascript("if(typeof resetAssistant === 'function') resetAssistant();", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownOverlay()
    }

    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        latestAssistDump = buildAssistDump(structure, content)
        latestForegroundPackage = content?.intent?.component?.packageName
            ?: content?.intent?.`package`
            ?: ""
        latestAssistJson = JSONObject()
            .put("captured_at_ms", System.currentTimeMillis())
            .put("foreground_package", latestForegroundPackage)
            .put("assist_dump", latestAssistDump)
        emitTerminalStream("assist", "Assist Structure captured.")
    }

    fun dismissAssistant() {
        mainHandler.post {
            hide()
        }
    }

    fun requestKeyboard() {
        mainHandler.post {
            val webView = overlayWebView ?: return@post
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun copyToClipboard(text: String) {
        mainHandler.post {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("dora_action_log", text)
            clipboard.setPrimaryClip(clip)
            emitTerminalStream("status", "Copied full action log to clipboard.")
        }
    }

    fun openModelFileMenu() {
        mainHandler.post {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            runCatching {
                context.startActivity(intent)
            }.onFailure { error ->
                emitTerminalStream("error", "Failed to open model file picker: ${error.message}")
            }
        }
    }

    fun startDictation() {
        dictationController.start()
    }

    fun stopDictation() {
        dictationController.stop()
    }

    fun cancelDictation() {
        dictationController.cancel()
    }

    fun requestNaturalTts(text: String) {
        naturalTtsPlayer.speak(text)
    }

    fun isVoiceResponsesEnabled(): Boolean {
        return backendConfig.voiceResponsesEnabled
    }

    fun vibrate(timingsJson: String, amplitudesJson: String) {
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

    fun dispatchAgentAction(actionJson: String) {
        mainHandler.post {
            val normalizedJson = actionJson.trim()
            val payload = try {
                JSONObject(normalizedJson)
            } catch (_: JSONException) {
                emitTerminalStream("error", "Invalid JSON payload.")
                return@post
            }

            val action = payload.optString("action", "unknown")
            emitTerminalStream("system", "Action received: $action")
            emitTerminalStream("request", payload.toString())

            // Placeholder engine dispatch path: keep this local and in-process.
            when (action) {
                "voice.start" -> emitTerminalStream("status", "Voice engine started.")
                "voice.stop" -> emitTerminalStream("status", "Voice engine stopped.")
                "voice.state.change" -> {
                    val state = payload.optString("state", "unknown")
                    emitTerminalStream("voice", "State changed to '$state'")
                }
                "read_screen_layout" -> {
                    emitTerminalStream("status", "read_screen_layout is deprecated. Use read_assist_structure.")
                    emitTerminalStream("assist", latestAssistDump)
                }
                "read_assist_structure" -> {
                    emitTerminalStream("assist", latestAssistDump)
                }
                "click_ui_element" -> {
                    emitTerminalStream("status", "click_ui_element is deprecated. Use open_deep_link / run_app_action.")
                }
                "open_deep_link", "run_app_action" -> {
                    val uri = payload.optString("uri", "").trim()
                    if (uri.isEmpty()) {
                        emitTerminalStream("error", "$action requires payload.uri")
                        return@post
                    }
                    val packageName = payload.optString("package", "").trim()
                    openDeepLink(uri, packageName)
                }
                "mcp.list_tools" -> backgroundExecutor.execute {
                    runCatching {
                        val tools = localMcpBroker.listTools()
                        emitTerminalStream("mcp", "tools=$tools")
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
                            val localResult = localOnnxRuntimeEngine.infer(payload)
                            emitTerminalStream("local_ai", "inference result=$localResult")
                        } else {
                            val prompt = payload.optString("prompt", "").trim()
                            appendConversationHistory("user", prompt)
                            // Multi-step / compound requests run the model-driven
                            // MCP tool-calling agent loop.
                            if (looksMultiStep(prompt)) {
                                runAgentTaskLoop(JSONObject().put("goal", prompt))
                                return@execute
                            }
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
                            val result = mainBackendClient.infer(requestPayload)
                            if (result.optBoolean("ok", false)) {
                                appendConversationHistory("assistant", result.optString("output", ""))
                            }
                            emitTerminalStream("backend", "inference result=$result")
                        }
                    }.onFailure { error ->
                        emitTerminalStream("error", "agent.infer failed: ${error.message}")
                    }
                }
                "agent.local_infer" -> backgroundExecutor.execute {
                    runCatching {
                        val localResult = localOnnxRuntimeEngine.infer(payload)
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
                "agent.run_task" -> backgroundExecutor.execute {
                    runAgentTaskLoop(payload)
                }
                "shell.bootstrap" -> backgroundExecutor.execute {
                    runCatching {
                        toyboxShellManager.bootstrap(::emitTerminalStream)
                    }.onFailure { error ->
                        emitTerminalStream("error", "Shell bootstrap failed: ${error.message}")
                    }
                }
                "shell.healthcheck" -> backgroundExecutor.execute {
                    runCatching {
                        toyboxShellManager.healthcheck(::emitTerminalStream)
                    }.onFailure { error ->
                        emitTerminalStream("error", "Shell healthcheck failed: ${error.message}")
                    }
                }
                "shell.exec" -> backgroundExecutor.execute {
                    val command = payload.optString("command", "").trim()
                    if (command.isEmpty()) {
                        emitTerminalStream("error", "shell.exec requires payload.command")
                        return@execute
                    }
                    runCatching {
                        toyboxShellManager.exec(command, ::emitTerminalStream)
                    }.onFailure { error ->
                        emitTerminalStream("error", "shell.exec failed: ${error.message}")
                    }
                }
                else -> emitTerminalStream("status", "No handler yet for '$action'.")
            }
        }
    }

    private fun showOverlayShell() {
        val existing = overlayWebView
        if (existing != null) {
            existing.requestFocusFromTouch()
            return
        }

        setUiEnabled(true)
        configureSessionWindow()

        val view = WebView(context).apply {
            // Keep the view composited as transparent so underlying apps stay visible.
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            requestFocusFromTouch()
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.d(TAG, "overlay WebView touch down x=${event.x} y=${event.y}")
                    v.requestFocusFromTouch()
                }
                false
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val line = consoleMessage.lineNumber()
                    val source = consoleMessage.sourceId()
                    val level = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    emitTerminalStream("js", "[$level] $message ($source:$line)")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (isSessionShowing) {
                        view?.evaluateJavascript("if(typeof showAssistant === 'function') showAssistant();", null)
                    }
                }
            }
            addJavascriptInterface(AndroidBridge(this@AgentAssistantSession), "AndroidBridge")
            loadUrl("file:///android_asset/www/index.html")
        }

        setContentView(view)
        overlayWebView = view
    }

    private fun configureSessionWindow() {
        val dialogWindow = window?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
        dialogWindow.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialogWindow.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        dialogWindow.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun teardownOverlay() {
        naturalTtsPlayer.stop()
        dictationController.destroy()
        toyboxShellManager.close()
        overlayWebView?.let { view ->
            view.destroy()
        }
        overlayWebView = null
    }

    private fun openDeepLink(uriString: String, packageName: String) {
        openDeepLinkInternal(uriString, packageName)
            .onSuccess { message ->
                emitTerminalStream("app", message)
            }
            .onFailure { error ->
                emitTerminalStream("error", "Failed to launch deep link: ${error.message}")
            }
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
            context.startActivity(intent)
            "Launched deep link: $uriString"
        }
    }

    private fun buildAssistDump(structure: AssistStructure?, content: AssistContent?): String {
        if (structure == null) return "Assist structure unavailable. Trigger the assistant on top of a target app first."

        val builder = StringBuilder()
        builder.append("windows=").append(structure.windowNodeCount)
        content?.webUri?.toString()?.let { builder.append(" webUri=").append(it) }
        content?.intent?.dataString?.let { builder.append(" intentData=").append(it) }
        builder.appendLine()

        for (windowIndex in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(windowIndex)
            val root = windowNode.rootViewNode ?: continue
            builder.appendLine("window[$windowIndex]")
            appendViewNode(builder, root, depth = 0, maxDepth = 4, maxNodes = 120, counter = intArrayOf(0))
        }
        return builder.toString().trim()
    }

    private fun appendViewNode(
        builder: StringBuilder,
        node: AssistStructure.ViewNode,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        counter: IntArray
    ) {
        if (counter[0] >= maxNodes || depth > maxDepth) return
        counter[0] += 1
        val indent = "  ".repeat(depth)
        val className = node.className ?: "View"
        val text = node.text?.toString()?.trim().orEmpty()
        val hint = node.hint?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.idEntry?.trim().orEmpty()
        val parts = mutableListOf("class=$className")
        if (id.isNotEmpty()) parts += "id=$id"
        if (text.isNotEmpty()) parts += "text=$text"
        if (hint.isNotEmpty()) parts += "hint=$hint"
        if (desc.isNotEmpty()) parts += "desc=$desc"
        builder.append(indent).append("- ").append(parts.joinToString(" | ")).appendLine()

        for (i in 0 until node.childCount) {
            appendViewNode(builder, node.getChildAt(i), depth + 1, maxDepth, maxNodes, counter)
            if (counter[0] >= maxNodes) break
        }
    }

    private fun appendConversationHistory(role: String, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        conversationHistory.addLast(
            JSONObject().put("role", role).put("content", text.take(2000))
        )
        while (conversationHistory.size > MAX_CONVERSATION_MESSAGES) {
            conversationHistory.removeFirst()
        }
    }

    private fun conversationHistorySnapshot(): JSONArray {
        val snapshot = JSONArray()
        conversationHistory.forEach { snapshot.put(it) }
        return snapshot
    }

    private fun emitTerminalStream(channel: String, chunk: String) {
        mainHandler.post {
            val webView = overlayWebView ?: return@post
            val safeChannel = JSONObject.quote(channel)
            val safeChunk = JSONObject.quote(chunk)
            val script = "window.onTerminalStream($safeChannel, $safeChunk);"
            webView.evaluateJavascript(script, null)
        }
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

    private fun runAgentTaskLoop(payload: JSONObject) {
        val goal = payload.optString("goal", payload.optString("prompt", "")).trim()
        val apiKey = backendConfig.apiKey.trim()
        val maxRetries = payload.optInt("max_retries", 2).coerceIn(0, 4)

        if (goal.isEmpty()) {
            emitTerminalStream("error", "agent.run_task requires goal")
            return
        }

        resolveDeterministicAnswer(goal)?.let { deterministic ->
            emitAgentFinal(deterministic)
            return
        }

        // Trivial single command -> instant native path (sub-second, no cloud).
        if (!looksMultiStep(goal)) {
            deviceCommandRouter.tryHandle(goal)?.let { deviceResult ->
                emitAgentFinal(deviceResult.optString("output", "Done."))
                return
            }
        }

        setOverlayWorking()

        if (apiKey.isEmpty()) {
            // No cloud model configured -> best-effort deterministic chaining.
            runDeterministicChain(goal)
            return
        }

        // Model-driven MCP tool calling. Workers AI Llama is reliable for a
        // single action per turn but stalls on compound "do A and B" requests,
        // so we decompose into atomic steps and let the model natively choose
        // the tool + args for each step via function calling.
        val toolsCatalog = agentToolCatalog()
        val tools = toOpenAiTools(toolsCatalog)
        val toolNames = buildToolNameSet(toolsCatalog)
        val steps = decomposeSteps(goal)
        emitTerminalStream("agent", "Agent goal: $goal -> ${steps.size} step(s)")

        val outputs = mutableListOf<String>()
        var modelFailure = false
        for ((index, step) in steps.withIndex()) {
            emitTerminalStream("agent", "Step ${index + 1}/${steps.size}: $step")
            val stepResult = runAgentStep(step, tools, toolNames, maxRetries)
            if (stepResult == null) {
                modelFailure = true
                deviceCommandRouter.tryHandle(step)?.let {
                    outputs += it.optString("output", "Done.").trim()
                }
            } else if (stepResult.isNotBlank()) {
                outputs += stepResult.trim()
            }
            runCatching { Thread.sleep(200) }
        }

        val answer = outputs.filter { it.isNotEmpty() }.joinToString(" ")
        when {
            answer.isNotBlank() -> emitAgentFinal(answer)
            modelFailure -> runDeterministicChain(goal)
            else -> emitAgentFinal("")
        }
    }

    /**
     * Run one atomic step through native model tool calling: the model picks a
     * tool + args, we execute it via the MCP broker and report the outcome. If
     * the model answers without a tool (e.g. an info question) we return that
     * text. Returns null only when the model request itself fails.
     */
    private fun runAgentStep(
        step: String,
        tools: JSONArray,
        toolNames: Set<String>,
        maxRetries: Int
    ): String? {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "You are Dora's phone-control agent. Call exactly ONE tool to do what " +
                            "the user asks. For questions needing live or factual info, call " +
                            "device_web_search.search. Use personal-data tools only when the user " +
                            "asks about their own notifications, messages, or saved facts. Never ask " +
                            "for permission. If no tool fits, answer in one short sentence."
                    )
            )
            .put(JSONObject().put("role", "user").put("content", step))

        val completion = requestCompletionWithRetry(
            request = MainBackendClient.CompletionRequest(
                endpoint = backendConfig.endpoint.trim(),
                apiKey = backendConfig.apiKey.trim(),
                model = backendConfig.model.trim(),
                messages = messages,
                tools = tools,
                headers = backendConfig.headers,
                temperature = 0.0
            ),
            maxRetries = maxRetries
        )
        if (!completion.ok) {
            emitTerminalStream("error", "Model request failed: ${completion.error}")
            return null
        }

        val toolCalls = completion.toolCalls
        val content = completion.outputText
            .ifBlank { completion.message.optString("content", "") }
            .trim()

        if (toolCalls.length() == 0) {
            if (content.isNotEmpty()) return content
            // Empty model output -> let the instant router try the step.
            return deviceCommandRouter.tryHandle(step)?.optString("output")
        }

        val results = mutableListOf<String>()
        for (i in 0 until toolCalls.length()) {
            val fn = toolCalls.optJSONObject(i)?.optJSONObject("function") ?: continue
            val rawName = fn.optString("name", "").trim()
            val toolName = normalizeToolName(rawName, toolNames)
            if (toolName.isEmpty() || toolName !in toolNames) {
                emitTerminalStream("agent", "Model picked unknown tool '$rawName', skipping.")
                continue
            }
            val rawArgs = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                .getOrDefault(JSONObject())
            val toolArgs = normalizeToolArgs(toolName, rawArgs, step)
            emitTerminalStream("agent", "Calling tool: $toolName $toolArgs")
            val toolResult = callToolWithRetry(toolName, toolArgs, maxRetries)
            emitTerminalStream("mcp", "tool=$toolName result=$toolResult")
            val out = toolOutputText(toolResult)
            if (out.isNotEmpty()) results += out
        }
        if (results.isEmpty()) {
            return deviceCommandRouter.tryHandle(step)?.optString("output")
        }
        return results.joinToString(" ")
    }

    /** Pull the most human-readable text out of an MCP tool result. */
    private fun toolOutputText(result: JSONObject): String {
        val text = listOf("output", "spoken", "answer", "message", "result", "summary")
            .firstNotNullOfOrNull { key -> result.optString(key, "").trim().ifBlank { null } }
        return text ?: if (result.optBoolean("ok", false)) "Done." else "That didn't work."
    }

    /** Split a compound goal into atomic steps; returns [goal] when not compound. */
    private fun decomposeSteps(goal: String): List<String> {
        val parts = goal
            .split(Regex("(?i)\\s+(?:and then|after that|then|and|&)\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts else listOf(goal)
    }

    /**
     * Lean, action-oriented subset of the MCP catalog exposed to the agent model.
     * Cross-app actions go through the single generic `start_intent` tool (the
     * uniform Android intent format) instead of one bespoke tool per app feature,
     * which keeps the model's context small while covering nearly every app.
     */
    private fun agentToolCatalog(): JSONArray {
        val all = localMcpBroker.listTools()
        val allowPrefixes = listOf(
            "device_control.", "personal_context.", "text_intelligence."
        )
        val allowExact = setOf(
            "http.request",
            "skill.import_url",
            "skill.create",
            "skill.list",
            "skill.run",
            "automation.create",
            "automation.list",
            "automation.run_now",
            "app_capabilities.search",
            "intent_routing_server.start_intent",
            "intent_routing_server.open_app",
            "intent_routing_server.launch_system_action",
            "device_web_search.search",
            // Specific tools only where the generic intent is unreliable (natural
            // language time/duration parsing) or non-trivial (media search).
            "app_actions.set_alarm",
            "app_actions.set_timer",
            "app_actions.play_media",
            "context_triage_screen.get_active_screen_json"
        )
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val tool = all.optJSONObject(i) ?: continue
            val name = tool.optString("name", "")
            if (allowPrefixes.any { name.startsWith(it) } || name in allowExact) {
                out.put(tool)
            }
        }
        return out
    }

    /** Fallback chaining via the instant router when the cloud model is down. */
    private fun runDeterministicChain(goal: String) {
        val outputs = mutableListOf<String>()
        for (part in decomposeSteps(goal)) {
            val res = deviceCommandRouter.tryHandle(part) ?: continue
            outputs += res.optString("output", "Done.").trim()
            runCatching { Thread.sleep(200) }
        }
        emitAgentFinal(outputs.filter { it.isNotEmpty() }.joinToString(" "))
    }

    /** Emit the agent's final answer through the normal render path (overlay + app). */
    private fun emitAgentFinal(answer: String) {
        val text = answer.trim()
            .ifBlank { "I couldn't fully complete that. Try rephrasing it or splitting it into steps." }
        appendConversationHistory("assistant", text)
        val result = JSONObject().put("ok", true).put("output", text)
        emitTerminalStream("backend", "inference result=$result")
    }

    private fun setOverlayWorking() {
        mainHandler.post {
            overlayWebView?.evaluateJavascript(
                "if(typeof setOverlayTitle==='function')setOverlayTitle('assistant','Working on it\\u2026');",
                null
            )
        }
    }

    private val actionVerbs = listOf(
        "open ", "launch ", "play ", "turn on", "turn off", "set ", "send ", "text ",
        "call ", "navigate", "directions", "find ", "mute", "unmute", "pause", "skip",
        "resume", "stop ", "create ", "schedule ", "take a photo", "take a picture",
        "record a video", "message ", "dial ", "fetch ", "download "
    )

    /** Heuristic: does the request contain 2+ distinct actions (needs chaining)? */
    private fun looksMultiStep(prompt: String): Boolean {
        val t = prompt.lowercase()
        return actionVerbs.count { t.contains(it) } >= 2
    }

    private fun requestCompletionWithRetry(
        request: MainBackendClient.CompletionRequest,
        maxRetries: Int
    ): MainBackendClient.CompletionResult {
        var attempt = 0
        var last = mainBackendClient.complete(request)
        while (!last.ok && attempt < maxRetries) {
            attempt += 1
            val delayMs = 350L * (attempt + 1)
            emitTerminalStream("agent", "Model retry $attempt/$maxRetries in ${delayMs}ms")
            runCatching { Thread.sleep(delayMs) }
            last = mainBackendClient.complete(request)
        }
        return last
    }

    private fun callToolWithRetry(toolName: String, args: JSONObject, maxRetries: Int): JSONObject {
        var attempt = 0
        var result = localMcpBroker.callTool(toolName, args)
        while (!result.optBoolean("ok", false) && attempt < maxRetries) {
            attempt += 1
            val delayMs = 220L * (attempt + 1)
            emitTerminalStream("agent", "Tool $toolName retry $attempt/$maxRetries in ${delayMs}ms")
            runCatching { Thread.sleep(delayMs) }
            result = localMcpBroker.callTool(toolName, args)
        }
        return result
    }

    private fun toOpenAiTools(localTools: JSONArray): JSONArray {
        val tools = JSONArray()
        for (i in 0 until localTools.length()) {
            val item = localTools.optJSONObject(i) ?: continue
            tools.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", item.optString("name"))
                            .put("description", item.optString("description"))
                            .put("parameters", item.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))
                    )
            )
        }
        return tools
    }

    private fun buildToolsSummary(tools: JSONArray): String {
        val parts = mutableListOf<String>()
        for (i in 0 until tools.length()) {
            val item = tools.optJSONObject(i) ?: continue
            val name = item.optString("name", "").trim()
            val description = item.optString("description", "").trim()
            if (name.isNotEmpty()) {
                parts += "$name - $description"
            }
        }
        return parts.joinToString("\n")
    }

    private fun buildTurnPrompt(goal: String, toolsSummary: String, history: List<String>): String {
        val historyText = if (history.isEmpty()) {
            "No prior steps."
        } else {
            history.takeLast(8).joinToString("\n")
        }
        return """
            Goal: $goal
            Foreground package: $latestForegroundPackage
            Available tools:
            $toolsSummary

            Prior observations:
            $historyText

            Think briefly, then decide the next single step.
            Return ONLY JSON:
            {"thinking":"<one short sentence>","rationale":"<why this tool is best now>","need_screen_context":false,"tool_candidates":[{"name":"<tool>","score":0.0,"reason":"..."}],"action":"tool","tool":"<tool_name>","args":{...}}
            or
            {"thinking":"<one short sentence>","rationale":"<why no tool is needed>","need_screen_context":false,"action":"final","answer":"<final user response>"}

            Rules:
            - Choose ONLY tools from Available tools.
            - If the goal is a simple factual question that does not require phone interaction, return final directly.
            - If a previous tool failed due to missing args, fix args before retrying.
            - Do NOT call scanner tools unless goal is about indexing/catalog.
            - Prefer completing the goal over re-indexing unless indexing is explicitly needed.
        """.trimIndent()
    }

    private fun parseAgentDecision(raw: String): JSONObject? {
        val cleaned = raw
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val direct = runCatching { JSONObject(cleaned) }.getOrNull()
        if (direct != null) return normalizeDecision(direct)

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val candidate = cleaned.substring(start, end + 1)
            val parsed = runCatching { JSONObject(candidate) }.getOrNull()
            if (parsed != null) return normalizeDecision(parsed)
        }
        return null
    }

    private fun normalizeDecision(decision: JSONObject): JSONObject {
        val normalized = JSONObject(decision.toString())
        if (!normalized.has("action")) {
            when {
                normalized.has("tool") -> normalized.put("action", "tool")
                normalized.has("answer") -> normalized.put("action", "final")
                normalized.has("next_action") -> normalized.put("action", normalized.optString("next_action", "tool"))
            }
        }
        if (!normalized.has("tool")) {
            val functionName = normalized.optString("function_name", "").trim()
            if (functionName.isNotEmpty()) normalized.put("tool", functionName)
            val selected = normalized.optString("selected_tool", normalized.optString("chosen_tool", "")).trim()
            if (normalized.optString("tool", "").isBlank() && selected.isNotEmpty()) {
                normalized.put("tool", selected)
            }
            if (normalized.optString("tool", "").isBlank()) {
                val candidates = normalized.optJSONArray("tool_candidates")
                if (candidates != null && candidates.length() > 0) {
                    var bestName = ""
                    var bestScore = Double.NEGATIVE_INFINITY
                    for (i in 0 until candidates.length()) {
                        val rawCandidate = candidates.opt(i)
                        when (rawCandidate) {
                            is JSONObject -> {
                                val name = rawCandidate.optString("name", rawCandidate.optString("tool", "")).trim()
                                val score = rawCandidate.optDouble("score", 0.0)
                                if (name.isNotEmpty() && score >= bestScore) {
                                    bestScore = score
                                    bestName = name
                                }
                            }
                            is String -> {
                                val name = rawCandidate.trim()
                                if (name.isNotEmpty() && bestName.isEmpty()) {
                                    bestName = name
                                }
                            }
                        }
                    }
                    if (bestName.isNotEmpty()) {
                        normalized.put("tool", bestName)
                    }
                }
            }
        }
        if (!normalized.has("args")) {
            val argsFromDecision = runCatching { JSONObject() }.getOrDefault(JSONObject())
            if (normalized.has("arguments")) {
                val rawArgs = normalized.opt("arguments")
                when (rawArgs) {
                    is JSONObject -> return JSONObject(normalized.toString()).put("args", rawArgs)
                    is String -> {
                        val parsed = runCatching { JSONObject(rawArgs) }.getOrNull()
                        if (parsed != null) return JSONObject(normalized.toString()).put("args", parsed)
                    }
                }
            }
            normalized.put("args", argsFromDecision)
        }
        return normalized
    }

    private fun buildToolNameSet(tools: JSONArray): Set<String> {
        val names = mutableSetOf<String>()
        for (i in 0 until tools.length()) {
            val name = tools.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
            if (name.isNotEmpty()) names += name
        }
        return names
    }

    private fun normalizeToolName(rawTool: String, available: Set<String>): String {
        if (rawTool.isBlank()) return ""
        if (rawTool in available) return rawTool

        val aliases = mapOf(
            "open_settings" to "intent_routing_server.launch_system_action",
            "launch_system_action" to "intent_routing_server.launch_system_action",
            "open_deep_link" to "intent_routing_server.fire_app_deep_link",
            "search_web" to "intent_routing_server.search_web",
            "open_maps_query" to "intent_routing_server.open_maps_query",
            "read_screen" to "context_triage_screen.get_active_screen_json",
            "get_active_contacts" to "context_triage_screen.get_active_contacts",
            "update_package" to "system_capability_scanner.update_changed_package"
        )
        aliases[rawTool]?.let { if (it in available) return it }

        val lower = rawTool.lowercase()
        val fuzzy = available.firstOrNull { it.lowercase().contains(lower) || lower.contains(it.lowercase()) }
        return fuzzy.orEmpty()
    }

    private fun chooseFallbackTool(
        goal: String,
        thinking: String,
        rationale: String,
        availableTools: Set<String>
    ): Pair<String, JSONObject>? {
        val text = "$goal $thinking $rationale".lowercase()
        if ((text.contains("screen") || text.contains("see") || text.contains("what is on")) &&
            "context_triage_screen.get_active_screen_json" in availableTools
        ) {
            return "context_triage_screen.get_active_screen_json" to JSONObject()
        }
        if (text.contains("maps") && "intent_routing_server.open_maps_query" in availableTools) {
            return "intent_routing_server.open_maps_query" to JSONObject().put("query", goal)
        }
        return null
    }

    private fun validateToolChoice(
        toolName: String,
        args: JSONObject,
        goal: String,
        failureCounts: Map<String, Int>
    ): String? {
        val lowerGoal = goal.lowercase()
        if (toolName.startsWith("system_capability_scanner.") &&
            listOf("index", "catalog", "scan", "package").none { lowerGoal.contains(it) }
        ) {
            return "Scanner tool is unrelated to current goal."
        }

        if (toolName == "intent_routing_server.launch_system_action") {
            val actionType = args.optString("actionType", "").trim()
            if (actionType.isEmpty()) {
                return "Missing actionType for launch_system_action."
            }
            val nested = args.optJSONObject("args") ?: JSONObject()
            if ((actionType == "send_sms" || actionType == "dial_number") &&
                nested.optString("phone", "").trim().isEmpty()
            ) {
                return "Phone number is required for $actionType."
            }
        }

        if (toolName == "intent_routing_server.fire_app_deep_link") {
            val uri = args.optString("uri", "").trim()
            if (uri.isEmpty()) return "Deep link uri is required."
        }

        if ((failureCounts[toolName] ?: 0) >= 2) {
            return "Tool has repeatedly failed; choose a different strategy."
        }
        return null
    }

    private fun resolveDeterministicAnswer(goal: String): String? {
        val lower = goal.lowercase()
        val asksDate = (lower.contains("date") || lower.contains("today")) &&
            (lower.contains("what") || lower.contains("whats") || lower.contains("what's"))
        val asksTime = lower.contains("time now") || lower == "time" || lower.contains("what time")

        if (asksDate || asksTime) {
            val now = ZonedDateTime.now()
            val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
            val time = now.format(DateTimeFormatter.ofPattern("h:mm a z"))
            return "Today is $date. Current time is $time."
        }
        return null
    }

    private fun filterToolsForGoal(allTools: JSONArray, goal: String): JSONArray {
        // Let the model choose tools; avoid hardcoded task filters.
        return allTools
    }

    private fun normalizeToolArgs(toolName: String, args: JSONObject, goal: String): JSONObject {
        val normalized = JSONObject(args.toString())
        when (toolName) {
            "intent_routing_server.launch_system_action" -> {
                if (normalized.optString("actionType", "").isBlank()) {
                    val inferred = inferActionType(goal, normalized)
                    if (inferred.isNotBlank()) normalized.put("actionType", inferred)
                }
                if (!normalized.has("args")) normalized.put("args", JSONObject())
                val nested = normalized.optJSONObject("args") ?: JSONObject()
                if (nested.optString("phone", "").isBlank()) {
                    extractPhone(goal)?.let { nested.put("phone", it) }
                }
                if (nested.optString("body", "").isBlank() && goal.contains("message", ignoreCase = true)) {
                    val body = extractMessageBody(goal)
                    if (body.isNotBlank()) nested.put("body", body)
                }
                normalized.put("args", nested)
            }
            "system_capability_scanner.update_changed_package" -> {
                if (normalized.optString("packageName", "").isBlank()) {
                    val packageCandidate = normalized.optString(
                        "package",
                        normalized.optString("package_name", latestForegroundPackage)
                    ).trim()
                    if (packageCandidate.isNotBlank()) normalized.put("packageName", packageCandidate)
                }
            }
            "intent_routing_server.fire_app_deep_link" -> {
                val uri = normalized.optString("uri", "").trim()
                if (uri.isBlank() || uri.startsWith("deflink://")) {
                    val fallbackUri = when {
                        goal.contains("maps", ignoreCase = true) -> "geo:0,0?q=maps"
                        goal.contains("settings", ignoreCase = true) -> "android.settings.SETTINGS"
                        else -> ""
                    }
                    if (fallbackUri.isNotBlank()) normalized.put("uri", fallbackUri)
                }
            }
        }
        return normalized
    }

    private fun inferActionType(goal: String, args: JSONObject): String {
        val lowerGoal = goal.lowercase()
        if (lowerGoal.contains("sms") || lowerGoal.contains("text") || lowerGoal.contains("message")) {
            return "send_sms"
        }
        if (lowerGoal.contains("dial") || lowerGoal.contains("call")) {
            return "dial_number"
        }
        if (lowerGoal.contains("wifi") || lowerGoal.contains("wi-fi")) return "open_wifi_settings"
        if (lowerGoal.contains("bluetooth")) return "open_bluetooth_settings"
        if (lowerGoal.contains("do not disturb") || lowerGoal.contains("dnd")) return "open_do_not_disturb"
        if (lowerGoal.contains("airplane")) return "open_airplane_settings"
        if (lowerGoal.contains("weather")) return "open_weather"
        if (lowerGoal.contains("setting")) return "open_settings"
        return args.optString("actionType", "")
    }

    private fun extractPhone(text: String): String? {
        val matcher = Pattern.compile("\\b\\d{7,15}\\b").matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    private fun extractMessageBody(goal: String): String {
        val lowered = goal.lowercase()
        val marker = listOf("body", "message", "text").firstOrNull { lowered.contains(it) } ?: return ""
        val idx = lowered.indexOf(marker)
        if (idx < 0) return ""
        return goal.substring(idx + marker.length).trim().trim(':').trim().ifBlank { "" }
    }

    private fun normalizeAssistantMessage(assistantMessage: JSONObject): JSONObject {
        val normalized = JSONObject().put("role", "assistant")
        val toolCalls = assistantMessage.optJSONArray("tool_calls")
        val content = normalizeMessageContent(assistantMessage.opt("content"))

        if (toolCalls != null && toolCalls.length() > 0) {
            normalized.put("tool_calls", toolCalls)
            // Keep tool-call turns schema-compatible with providers that require null content.
            normalized.put("content", JSONObject.NULL)
        } else {
            normalized.put("content", content)
        }
        return normalized
    }

    private fun normalizeMessageContent(raw: Any?): String {
        return when (raw) {
            null, JSONObject.NULL -> ""
            is String -> raw
            is JSONArray -> {
                val pieces = mutableListOf<String>()
                for (i in 0 until raw.length()) {
                    val item = raw.opt(i)
                    when (item) {
                        is String -> if (item.isNotBlank()) pieces += item
                        is JSONObject -> {
                            val text = item.optString("text", "").trim()
                            if (text.isNotEmpty()) pieces += text
                        }
                    }
                }
                pieces.joinToString("\n")
            }
            is JSONObject -> raw.optString("text", raw.toString())
            else -> raw.toString()
        }
    }

    private fun resolveToolName(toolCall: JSONObject, function: JSONObject): String {
        val fromFunction = function.optString("name", "").trim()
        if (fromFunction.isNotEmpty()) return fromFunction

        val directName = toolCall.optString("name", "").trim()
        if (directName.isNotEmpty()) return directName

        val toolObj = toolCall.optJSONObject("tool")
        val nestedName = toolObj?.optString("name", "")?.trim().orEmpty()
        return nestedName
    }

    private fun resolveToolArgs(toolCall: JSONObject, function: JSONObject): JSONObject {
        val functionArgs = function.opt("arguments")
        parseArgs(functionArgs)?.let { return it }

        val directArgs = toolCall.opt("arguments")
        parseArgs(directArgs)?.let { return it }

        return JSONObject()
    }

    private fun parseArgs(raw: Any?): JSONObject? {
        return when (raw) {
            null, JSONObject.NULL -> null
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
    }

    companion object {
        private const val TAG = "AgentAssistantSession"
        private const val MAX_CONVERSATION_MESSAGES = 24
    }
}
