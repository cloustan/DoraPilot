package com.dorapilot.assistant

import org.json.JSONObject

class TransactionMonitorServer(
    private val foregroundPackageProvider: () -> String,
    private val activeScreenProvider: () -> JSONObject
) {
    fun verifyAppState(args: JSONObject): JSONObject {
        val expectedPackage = args.optString("expected_package", "").trim()
        val containsText = args.optString("contains_text", "").trim()
        val foreground = foregroundPackageProvider()
        val activeScreen = activeScreenProvider()

        val packageMatches = expectedPackage.isEmpty() || expectedPackage == foreground
        val screenText = activeScreen.toString()
        val textMatches = containsText.isEmpty() || screenText.contains(containsText, ignoreCase = true)

        return JSONObject()
            .put("ok", true)
            .put("verified", packageMatches && textMatches)
            .put("foreground_package", foreground)
            .put("expected_package", expectedPackage)
            .put("contains_text", containsText)
            .put("package_match", packageMatches)
            .put("text_match", textMatches)
    }
}
