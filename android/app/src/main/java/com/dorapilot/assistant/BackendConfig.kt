package com.dorapilot.assistant

import com.dorapilot.BuildConfig
import org.json.JSONObject

data class BackendConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val headers: JSONObject,
    val voiceResponsesEnabled: Boolean,
    val localAiEnabled: Boolean,
    val localOnnxModelAsset: String,
    val localOnnxInputName: String,
    val localOnnxOutputName: String,
    val localGenAiModelAssetDir: String,
    val localGenAiModelFilesDir: String,
    val localGenAiMaxTokens: Int,
    val localGenAiTemperature: Float,
    val modelRegistryEndpoint: String
) {
    companion object {
        fun load(): BackendConfig {
            val headers = runCatching {
                JSONObject(BuildConfig.DORA_BACKEND_HEADERS_JSON)
            }.getOrDefault(JSONObject())

            return BackendConfig(
                endpoint = BuildConfig.DORA_BACKEND_ENDPOINT,
                model = BuildConfig.DORA_BACKEND_MODEL,
                apiKey = BuildConfig.DORA_BACKEND_API_KEY,
                headers = headers,
                voiceResponsesEnabled = BuildConfig.DORA_VOICE_RESPONSES_ENABLED,
                localAiEnabled = BuildConfig.DORA_LOCAL_AI_ENABLED,
                localOnnxModelAsset = BuildConfig.DORA_LOCAL_ONNX_MODEL_ASSET,
                localOnnxInputName = BuildConfig.DORA_LOCAL_ONNX_INPUT_NAME,
                localOnnxOutputName = BuildConfig.DORA_LOCAL_ONNX_OUTPUT_NAME,
                localGenAiModelAssetDir = BuildConfig.DORA_LOCAL_GENAI_MODEL_ASSET_DIR,
                localGenAiModelFilesDir = BuildConfig.DORA_LOCAL_GENAI_MODEL_FILES_DIR,
                localGenAiMaxTokens = BuildConfig.DORA_LOCAL_GENAI_MAX_TOKENS,
                localGenAiTemperature = BuildConfig.DORA_LOCAL_GENAI_TEMPERATURE,
                modelRegistryEndpoint = BuildConfig.DORA_MODEL_REGISTRY_ENDPOINT
            )
        }
    }
}
