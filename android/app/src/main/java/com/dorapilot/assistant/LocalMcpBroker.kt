package com.dorapilot.assistant

import org.json.JSONArray
import org.json.JSONObject

class LocalMcpBroker(
    private val scanner: SystemCapabilityScanner,
    private val triage: ContextTriageScreenServer,
    private val intentRouter: IntentRoutingServer,
    private val transactionMonitor: TransactionMonitorServer,
    private val deviceControl: DeviceControlServer,
    private val personalContext: PersonalContextEngine,
    private val appActions: AppActionsServer,
    private val textIntelligence: TextIntelligenceServer,
    private val screenIntelligence: ScreenIntelligenceServer
) {
    fun listTools(): JSONArray {
        return JSONArray().apply {
            put(
                JSONObject()
                    .put("name", "system_capability_scanner.index_all_apps")
                    .put("description", "Scan PackageManager/ShortcutManager and build on-device catalog.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "system.open_deep_link")
                    .put("description", "Legacy alias for intent_routing_server.fire_app_deep_link.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("uri", JSONObject().put("type", "string"))
                                    .put("package", JSONObject().put("type", "string"))
                            )
                            .put("required", JSONArray().put("uri"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "system_capability_scanner.update_changed_package")
                    .put("description", "Incrementally update catalog entry for one package.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("packageName", JSONObject().put("type", "string")))
                            .put("required", JSONArray().put("packageName"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "context_triage_screen.get_active_screen_json")
                    .put("description", "Get active AssistStructure JSON with relevant app context.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "context_triage_screen.get_foreground_package")
                    .put("description", "Get current foreground package from latest assist context.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "context_triage_screen.get_relevant_tools_for_foreground_app")
                    .put("description", "Return recommended tools for the current foreground app.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "system_capability_scanner.find_tools")
                    .put("description", "Search indexed apps/shortcuts for relevant tools by query.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("query", JSONObject().put("type", "string"))
                                    .put("limit", JSONObject().put("type", "number"))
                            )
                            .put("required", JSONArray().put("query"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "intent_routing_server.fire_app_deep_link")
                    .put("description", "Execute deep link intent with optional package scoping.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("uri", JSONObject().put("type", "string"))
                                    .put("package", JSONObject().put("type", "string"))
                            )
                            .put("required", JSONArray().put("uri"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "intent_routing_server.launch_system_action")
                    .put("description", "Execute predefined Android system action.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("actionType", JSONObject().put("type", "string"))
                                    .put("args", JSONObject().put("type", "object"))
                            )
                            .put("required", JSONArray().put("actionType"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "intent_routing_server.open_app")
                    .put("description", "Open an installed app by package name.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("package", JSONObject().put("type", "string")))
                            .put("required", JSONArray().put("package"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "intent_routing_server.search_web")
                    .put("description", "Open browser search for query text.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                            .put("required", JSONArray().put("query"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "intent_routing_server.open_maps_query")
                    .put("description", "Open Google Maps search using a free-text query.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                            .put("required", JSONArray().put("query"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "device_control.set_torch")
                    .put("description", "Turn the flashlight/torch on, off, or toggle it.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "state",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("on").put("off").put("toggle"))
                                )
                            )
                            .put("required", JSONArray().put("state"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "device_control.media")
                    .put("description", "Control media playback: play, pause, play_pause, next, previous, stop.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray().put("play").put("pause").put("play_pause")
                                                .put("next").put("previous").put("stop")
                                        )
                                )
                            )
                            .put("required", JSONArray().put("command"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "device_control.set_volume")
                    .put("description", "Set media volume. Use percent (0-100) for an absolute level, or direction (up/down/mute/unmute/max).")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("percent", JSONObject().put("type", "number"))
                                    .put("direction", JSONObject().put("type", "string"))
                            )
                    )
            )
            put(
                JSONObject()
                    .put("name", "personal_context.get_snapshot")
                    .put("description", "Get on-device personal context: time, battery, network, volume, foreground app, frequently used apps, and remembered facts.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "personal_context.remember")
                    .put("description", "Persist a durable fact/preference about the user (e.g. key='favorite music app', value='Spotify').")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("key", JSONObject().put("type", "string"))
                                    .put("value", JSONObject().put("type", "string"))
                            )
                            .put("required", JSONArray().put("key").put("value"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "personal_context.search")
                    .put("description", "Search the user's on-device personal data (messages, notifications, calendar) for items relevant to a query, to answer questions like 'when does my friend's flight land and what restaurant did they suggest'.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("query", JSONObject().put("type", "string"))
                                .put("limit", JSONObject().put("type", "number"))
                        ).put("required", JSONArray().put("query"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "personal_context.get_sources")
                    .put("description", "List personal-context sources (notifications, messages, calendar, contacts, usage) with enabled + permission status.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "personal_context.set_source")
                    .put("description", "Enable/disable a personal-context source. key: notifications|messages|calendar|contacts|usage.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("key", JSONObject().put("type", "string"))
                                .put("enabled", JSONObject().put("type", "boolean"))
                        ).put("required", JSONArray().put("key").put("enabled"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "personal_context.forget")
                    .put("description", "Delete a previously remembered user fact by key.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("key", JSONObject().put("type", "string")))
                            .put("required", JSONArray().put("key"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "app_actions.set_alarm")
                    .put("description", "Set a clock alarm at hour (0-23) and minute. Optional label.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("hour", JSONObject().put("type", "number"))
                                .put("minute", JSONObject().put("type", "number"))
                                .put("label", JSONObject().put("type", "string"))
                        ).put("required", JSONArray().put("hour").put("minute"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "app_actions.set_timer")
                    .put("description", "Start a countdown timer for a number of seconds.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("seconds", JSONObject().put("type", "number"))
                                .put("label", JSONObject().put("type", "string"))
                        ).put("required", JSONArray().put("seconds"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "app_actions.start_navigation")
                    .put("description", "Start turn-by-turn navigation to a destination. mode: drive/walk/bike/transit.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("query", JSONObject().put("type", "string"))
                                .put("mode", JSONObject().put("type", "string"))
                        ).put("required", JSONArray().put("query"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "app_actions.open_camera")
                    .put("description", "Open the camera. Set video=true for the video camera.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties", JSONObject().put("video", JSONObject().put("type", "boolean"))
                        )
                    )
            )
            put(
                JSONObject()
                    .put("name", "app_actions.create_calendar_event")
                    .put("description", "Open a new calendar event editor with title/time/location prefilled.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("title", JSONObject().put("type", "string"))
                                .put("begin_ms", JSONObject().put("type", "number"))
                                .put("end_ms", JSONObject().put("type", "number"))
                                .put("location", JSONObject().put("type", "string"))
                        ).put("required", JSONArray().put("title"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "app_actions.play_media")
                    .put("description", "Search/play a query on a music or video app. app: spotify/youtube.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("query", JSONObject().put("type", "string"))
                                .put("app", JSONObject().put("type", "string"))
                        ).put("required", JSONArray().put("query"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "text_intelligence.transform")
                    .put("description", "Writing tools on text: summarize, key_points, action_items, proofread, shorter, longer, professional, casual, polite, translate, compose. Provide mode and text (and language for translate).")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("mode", JSONObject().put("type", "string"))
                                .put("text", JSONObject().put("type", "string"))
                                .put("language", JSONObject().put("type", "string"))
                        ).put("required", JSONArray().put("mode").put("text"))
                    )
            )
            put(
                JSONObject()
                    .put("name", "screen_intelligence.summarize")
                    .put("description", "Summarize the current Android AssistStructure screen text.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "screen_intelligence.key_points")
                    .put("description", "Extract key points from the current Android AssistStructure screen text.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "screen_intelligence.action_items")
                    .put("description", "Extract concrete action items from the current Android AssistStructure screen text.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "screen_intelligence.translate")
                    .put("description", "Translate the current Android AssistStructure screen text. Optional language defaults to English.")
                    .put(
                        "input_schema",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject().put("language", JSONObject().put("type", "string"))
                        )
                    )
            )
            put(
                JSONObject()
                    .put("name", "system_capability_scanner.quick_stats")
                    .put("description", "Report navigation catalog freshness and indexed app count.")
                    .put("input_schema", JSONObject().put("type", "object"))
            )
            put(
                JSONObject()
                    .put("name", "transaction_monitor.verify_app_state")
                    .put("description", "Validate if expected app/screen state is active after intent routing.")
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("expected_package", JSONObject().put("type", "string"))
                                    .put("contains_text", JSONObject().put("type", "string"))
                            )
                    )
            )
        }
    }

    fun callTool(toolName: String, args: JSONObject): JSONObject {
        return when (toolName) {
            "system_capability_scanner.index_all_apps" -> scanner.indexAllApps()
            "system_capability_scanner.update_changed_package" -> {
                val packageName = args.optString("packageName", args.optString("package", args.optString("package_name", ""))).trim()
                scanner.updateChangedPackage(packageName)
            }
            "context_triage_screen.get_active_screen_json" -> triage.getActiveScreenJson()
            "context_triage_screen.get_foreground_package" -> triage.getForegroundPackage()
            "context_triage_screen.get_relevant_tools_for_foreground_app" -> triage.getRelevantToolsForForegroundApp()
            "context_triage_screen.get_active_contacts" -> {
                // Fallback stub so planners can recover instead of hard failing unknown tool.
                JSONObject()
                    .put("ok", true)
                    .put("contacts", JSONArray())
                    .put("note", "Contact extraction tool is not yet implemented.")
            }
            "system_capability_scanner.find_tools" -> {
                val query = args.optString("query", "").trim()
                val limit = args.optInt("limit", 12)
                scanner.findTools(query, limit)
            }
            "intent_routing_server.fire_app_deep_link" -> {
                intentRouter.fireAppDeepLink(
                    uri = args.optString("uri", "").trim(),
                    packageName = args.optString("package", "").trim()
                )
            }
            "intent_routing_server.open_app" -> {
                val pkg = args.optString(
                    "package",
                    args.optString("packageName", args.optString("package_name", ""))
                ).trim()
                intentRouter.openApp(pkg)
            }
            "intent_routing_server.search_web" -> {
                val query = args.optString("query", "").trim()
                intentRouter.searchWeb(query)
            }
            "intent_routing_server.open_maps_query" -> {
                val query = args.optString("query", "").trim()
                intentRouter.openMapsQuery(query)
            }
            "intent_routing_server.launch_system_action" -> {
                val nestedArgs = args.optJSONObject("args") ?: JSONObject()
                val actionType = args.optString(
                    "actionType",
                    args.optString("action_type", args.optString("action", nestedArgs.optString("actionType", "")))
                ).trim()
                intentRouter.launchSystemAction(actionType, nestedArgs)
            }
            "device_control.set_torch" -> {
                val state = args.optString("state", args.optString("value", args.optString("mode", ""))).trim()
                deviceControl.setTorch(state)
            }
            "device_control.media" -> {
                val command = args.optString("command", args.optString("action", "")).trim()
                deviceControl.mediaControl(command)
            }
            "device_control.set_volume" -> {
                val percent = if (args.has("percent")) args.optInt("percent") else null
                val direction = args.optString("direction", args.optString("action", "")).trim().ifEmpty { null }
                deviceControl.setVolume(percent, direction)
            }
            "personal_context.get_snapshot" -> personalContext.getSnapshot()
            "personal_context.search" -> personalContext.searchPersonalData(
                args.optString("query", "").trim(),
                args.optInt("limit", 8)
            )
            "personal_context.get_sources" -> personalContext.getSources()
            "personal_context.set_source" -> personalContext.setSource(
                args.optString("key", "").trim(),
                args.optBoolean("enabled", false)
            )
            "personal_context.remember" -> {
                val key = args.optString("key", "").trim()
                val value = args.optString("value", "")
                personalContext.remember(key, value)
            }
            "personal_context.forget" -> {
                val key = args.optString("key", "").trim()
                personalContext.forget(key)
            }
            "app_actions.set_alarm" -> appActions.setAlarm(
                args.optInt("hour", -1),
                args.optInt("minute", 0),
                args.optString("label", "")
            )
            "app_actions.set_timer" -> appActions.setTimer(
                args.optInt("seconds", 0),
                args.optString("label", "")
            )
            "app_actions.start_navigation" -> appActions.startNavigation(
                args.optString("query", "").trim(),
                args.optString("mode", "drive")
            )
            "app_actions.open_camera" -> appActions.openCamera(args.optBoolean("video", false))
            "app_actions.create_calendar_event" -> appActions.createCalendarEvent(
                args.optString("title", "").trim(),
                args.optLong("begin_ms", 0L),
                args.optLong("end_ms", 0L),
                args.optString("location", "")
            )
            "app_actions.play_media" -> {
                val query = args.optString("query", "").trim()
                when (args.optString("app", "youtube").trim().lowercase()) {
                    "spotify" -> appActions.playOnSpotify(query)
                    else -> appActions.playOnYouTube(query)
                }
            }
            "text_intelligence.transform" -> {
                val modeName = args.optString("mode", "summarize").trim().uppercase()
                val mode = runCatching { TextIntelligenceServer.Mode.valueOf(modeName) }
                    .getOrDefault(TextIntelligenceServer.Mode.SUMMARIZE)
                textIntelligence.transform(mode, args.optString("text", ""), args.optString("language", ""))
            }
            "screen_intelligence.summarize" -> screenIntelligence.summarize()
            "screen_intelligence.key_points" -> screenIntelligence.keyPoints()
            "screen_intelligence.action_items" -> screenIntelligence.actionItems()
            "screen_intelligence.translate" -> screenIntelligence.translate(args.optString("language", "English"))
            "system_capability_scanner.quick_stats" -> scanner.quickStats()
            "transaction_monitor.verify_app_state" -> transactionMonitor.verifyAppState(args)

            // Legacy aliases retained for earlier UI scripts.
            "system.list_launchable_apps" -> scanner.readCatalog()
            "system.open_deep_link" -> {
                intentRouter.fireAppDeepLink(
                    uri = args.optString("uri", "").trim(),
                    packageName = args.optString("package", "").trim()
                )
            }
            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unknown MCP tool: $toolName")
        }
    }
}
