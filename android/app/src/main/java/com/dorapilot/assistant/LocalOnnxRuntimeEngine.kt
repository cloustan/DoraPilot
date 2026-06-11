package com.dorapilot.assistant

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer

class LocalOnnxRuntimeEngine(
    private val context: Context,
    private val config: BackendConfig
) {
    @Volatile
    private var session: Any? = null
    private val lock = Any()
    @Volatile
    private var genAiModel: Any? = null
    @Volatile
    private var genAiTokenizer: Any? = null
    @Volatile
    private var genAiModelClass: Class<*>? = null
    @Volatile
    private var genAiTokenizerClass: Class<*>? = null
    @Volatile
    private var genAiGeneratorParamsClass: Class<*>? = null
    @Volatile
    private var genAiGeneratorClass: Class<*>? = null

    fun isConfigured(): Boolean {
        if (!config.localAiEnabled) return false
        return config.localGenAiModelFilesDir.isNotBlank() ||
            config.localGenAiModelAssetDir.isNotBlank() ||
            config.localOnnxModelAsset.isNotBlank()
    }

    fun inspectLocalSetup(): JSONObject {
        val result = JSONObject()
            .put("ok", true)
            .put("local_ai_enabled", config.localAiEnabled)
            .put("genai_files_dir", config.localGenAiModelFilesDir)
            .put("genai_asset_dir", config.localGenAiModelAssetDir)
            .put("raw_onnx_asset", config.localOnnxModelAsset)

        val genAiRuntimeAvailable = runCatching {
            Class.forName("ai.onnxruntime.genai.Model")
            true
        }.getOrElse { false }
        result.put("genai_runtime_available", genAiRuntimeAvailable)
        val rawOnnxRuntimeAvailable = runCatching {
            Class.forName("ai.onnxruntime.OrtEnvironment")
            Class.forName("ai.onnxruntime.OnnxTensor")
            true
        }.getOrElse { false }
        result.put("raw_onnx_runtime_available", rawOnnxRuntimeAvailable)

        val fileCandidates = JSONArray()
        val resolvedFileDir = configuredModelDirCandidates()
            .onEach { candidate ->
                fileCandidates.put(
                    JSONObject()
                        .put("path", candidate.absolutePath)
                        .put("exists", candidate.exists())
                        .put("is_directory", candidate.isDirectory)
                )
            }
            .firstOrNull { it.isDirectory }
        result.put("genai_file_candidates", fileCandidates)
        if (resolvedFileDir != null) {
            val listing = resolvedFileDir.list()?.toList().orEmpty()
            val files = JSONArray().apply { listing.sorted().forEach { put(it) } }
            result.put("genai_resolved_files_dir", resolvedFileDir.absolutePath)
            result.put("genai_files", files)
            result.put("genai_files_has_genai_config", listing.contains("genai_config.json"))
            result.put("genai_files_has_model_onnx", listing.contains("model.onnx"))
            result.put("genai_files_has_tokenizer", listing.contains("tokenizer.json"))
        }

        if (config.localGenAiModelAssetDir.isNotBlank()) {
            val listing = runCatching {
                context.assets.list(config.localGenAiModelAssetDir)?.toList().orEmpty()
            }.getOrElse { emptyList() }
            val files = JSONArray().apply { listing.sorted().forEach { put(it) } }
            result.put("genai_assets", files)
            result.put("genai_has_genai_config", listing.contains("genai_config.json"))
            result.put("genai_has_model_onnx", listing.contains("model.onnx"))
            result.put("genai_has_tokenizer", listing.contains("tokenizer.json"))
        }
        val hasUsableGenAiFiles = result.optBoolean("genai_files_has_genai_config", false) &&
            result.optBoolean("genai_files_has_model_onnx", false) &&
            result.optBoolean("genai_files_has_tokenizer", false)
        val hasUsableGenAiAssets = result.optBoolean("genai_has_genai_config", false) &&
            result.optBoolean("genai_has_model_onnx", false) &&
            result.optBoolean("genai_has_tokenizer", false)
        val hasRawOnnx = config.localOnnxModelAsset.isNotBlank() && rawOnnxRuntimeAvailable
        val ready = config.localAiEnabled && (hasRawOnnx || (genAiRuntimeAvailable && (hasUsableGenAiFiles || hasUsableGenAiAssets)))
        result.put("ready", ready)
        result.put(
            "setup_message",
            when {
                !config.localAiEnabled -> "Local AI is disabled. Set DORA_LOCAL_AI_ENABLED=true."
                ready -> "Local AI setup looks ready."
                config.localOnnxModelAsset.isNotBlank() && !rawOnnxRuntimeAvailable ->
                    "Raw ONNX Runtime is not packaged. Build with DORA_INCLUDE_ONNX_RUNTIME=true to enable raw ONNX tensors."
                !genAiRuntimeAvailable && (config.localGenAiModelFilesDir.isNotBlank() || config.localGenAiModelAssetDir.isNotBlank()) ->
                    "GenAI runtime is not packaged. Add a 16 KB-compatible ONNX Runtime GenAI AAR and enable DORA_INCLUDE_LOCAL_GENAI_AAR=true."
                else -> "Local model files are missing. Import a model folder with genai_config.json, model.onnx, and tokenizer.json."
            }
        )
        return result
    }

    fun infer(payload: JSONObject): JSONObject {
        if (!config.localAiEnabled) {
            val message = "Local AI is disabled. Set DORA_LOCAL_AI_ENABLED=true."
            return JSONObject()
                .put("ok", false)
                .put("error", message)
                .put("output", message)
                .put("code", "local_ai_disabled")
        }
        val prompt = payload.optString("prompt", "").trim()
        if (prompt.isNotBlank() &&
            (config.localGenAiModelFilesDir.isNotBlank() || config.localGenAiModelAssetDir.isNotBlank())
        ) {
            return inferGenAi(prompt, payload)
        }

        if (config.localOnnxModelAsset.isBlank()) {
            val message = "Missing DORA_LOCAL_ONNX_MODEL_ASSET. For LLM prompts, set DORA_LOCAL_GENAI_MODEL_ASSET_DIR."
            return JSONObject()
                .put("ok", false)
                .put("error", message)
                .put("output", message)
                .put("code", "missing_local_model")
        }
        if (!isRawOnnxRuntimeAvailable()) {
            val message = "Raw ONNX Runtime is not packaged. Build with DORA_INCLUDE_ONNX_RUNTIME=true to enable raw ONNX tensors."
            return JSONObject()
                .put("ok", false)
                .put("error", message)
                .put("output", message)
                .put("code", "raw_onnx_runtime_missing")
        }

        val inputShape = payload.optJSONArray("input_shape")
        val inputTensor = payload.optJSONArray("input_tensor")
        if (inputShape == null || inputTensor == null) {
            val message = "Local inference expects prompt for GenAI, or input_shape/input_tensor for raw ONNX."
            return JSONObject()
                .put("ok", false)
                .put("error", message)
                .put("output", message)
                .put("code", "invalid_local_payload")
        }

        return runCatching {
            val modelSession = ensureSession()
            val inputName = payload
                .optString("input_name", config.localOnnxInputName)
                .ifBlank { config.localOnnxInputName.ifBlank { "input" } }
            val outputName = payload
                .optString("output_name", config.localOnnxOutputName)
                .ifBlank { config.localOnnxOutputName.ifBlank { "output" } }
            val shape = jsonToLongArray(inputShape)
            val floats = jsonToFloatArray(inputTensor)
            val elementCount = shape.fold(1L) { acc, dim -> acc * dim }.toInt()
            require(floats.size == elementCount) {
                "input_tensor size (${floats.size}) does not match input_shape product ($elementCount)"
            }

            val tensor = createOnnxTensor(floats, shape)
            useAutoCloseable(tensor) { input ->
                val result = runRawSession(modelSession, inputName, input)
                useAutoCloseable(result) { rawResult ->
                    val outputValue = firstOnnxOutputValue(rawResult)
                    if (outputValue == null) {
                        JSONObject()
                            .put("ok", false)
                            .put("error", "Model produced no outputs.")
                            .put("output", "Model produced no outputs.")
                    } else {
                        JSONObject()
                            .put("ok", true)
                            .put("output_name", outputName)
                            .put("output", convertOutput(outputValue))
                    }
                }
            }
        }.getOrElse { error ->
            val message = error.message ?: "ONNX inference failed"
            JSONObject()
                .put("ok", false)
                .put("error", message)
                .put("output", message)
                .put("code", "raw_onnx_failed")
        }
    }

    private fun inferGenAi(prompt: String, payload: JSONObject): JSONObject {
        var stage = "starting"
        return runCatching {
            stage = "loading model"
            val state = ensureGenAiReady()
            val maxTokens = payload.optInt("max_tokens", config.localGenAiMaxTokens).coerceIn(1, 1024)
            val temperature = payload.optDouble(
                "temperature",
                config.localGenAiTemperature.toDouble()
            ).coerceIn(0.0, 2.0)

            stage = "creating generator params"
            val params = state.paramsClass.getConstructor(state.modelClass)
                .newInstance(state.model)
            try {
                stage = "encoding prompt"
                val templatedPrompt = if (payload.optBoolean("raw_prompt", false)) {
                    prompt
                } else {
                    buildChatMlPrompt(prompt, payload)
                }
                val encodedPrompt = encodePrompt(state.tokenizerClass, state.tokenizer, templatedPrompt)
                val promptTokenCount = countEncodedTokens(encodedPrompt)
                val maxLength = (promptTokenCount + maxTokens).coerceAtLeast(maxTokens)
                stage = "setting max_length"
                setSearchOption(state.paramsClass, params, "max_length", maxLength.toDouble())
                stage = "setting temperature"
                setSearchOption(state.paramsClass, params, "temperature", temperature)
                stage = "setting do_sample"
                setSearchOption(state.paramsClass, params, "do_sample", temperature > 0.0)
                stage = "creating generator"
                val generator = state.generatorClass
                    .getConstructor(state.modelClass, state.paramsClass)
                    .newInstance(state.model, params)
                try {
                    stage = "appending prompt"
                    appendGeneratorInput(state.generatorClass, generator, encodedPrompt)
                    var generatedTokens = 0
                    stage = "generating token"
                    while (!isGeneratorDone(state.generatorClass, generator) && generatedTokens < maxTokens) {
                        invokeRequired(
                            state.generatorClass,
                            generator,
                            "generateNextToken",
                            emptyArray(),
                            emptyArray()
                        )
                        generatedTokens += 1
                    }

                    stage = "reading generated sequence"
                    val generated = invokeRequired(
                        state.generatorClass,
                        generator,
                        "getSequence",
                        arrayOf(java.lang.Long.TYPE),
                        arrayOf(0L)
                    )
                    stage = "decoding output"
                    // getSequence returns prompt + generated tokens; drop the prompt
                    // so the reply does not echo the user's input.
                    val newTokensOnly = if (generated is IntArray && promptTokenCount in 1 until generated.size) {
                        generated.copyOfRange(promptTokenCount, generated.size)
                    } else {
                        generated
                    }
                    val text = decodeGenerated(state.tokenizerClass, state.tokenizer, newTokensOnly)
                        .trim()
                    JSONObject()
                        .put("ok", true)
                        .put("mode", "genai")
                        .put("output", text)
                        .put("prompt_tokens", promptTokenCount)
                        .put("generated_tokens", generatedTokens)
                        .put("max_tokens", maxTokens)
                        .put("max_length", maxLength)
                        .put("temperature", temperature)
                } finally {
                    closeQuietly(generator)
                    closeQuietly(encodedPrompt)
                }
            } finally {
                closeQuietly(params)
            }
        }.getOrElse { error ->
            val message = describeError(stage, error)
            JSONObject()
                .put("ok", false)
                .put("mode", "genai")
                .put("stage", stage)
                .put("error", message)
                .put("output", message)
                .put("code", "genai_failed")
        }
    }

    /**
     * Wrap the user prompt in the ChatML format used by Qwen-family instruct
     * models. Without this, the model does raw text continuation and emits
     * unrelated completions instead of answering.
     */
    private fun buildChatMlPrompt(prompt: String, payload: JSONObject): String {
        val system = payload.optString("system", "You are Dora, a concise on-device assistant.")
        return buildString {
            append("<|im_start|>system\n")
            append(system)
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append(prompt)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    private fun countEncodedTokens(encodedPrompt: Any): Int {
        if (encodedPrompt is IntArray) return encodedPrompt.size
        return runCatching {
            val getSequence = encodedPrompt.javaClass.getMethod("getSequence", java.lang.Long.TYPE)
            val firstSequence = getSequence.invoke(encodedPrompt, 0L)
            (firstSequence as? IntArray)?.size ?: 0
        }.getOrDefault(0)
    }

    private fun describeError(stage: String, error: Throwable): String {
        val root = unwrapThrowable(error)
        val typeName = root.javaClass.name.substringAfterLast('.')
        val message = root.message?.takeIf { it.isNotBlank() } ?: root.toString()
        return "$stage failed: $typeName: $message"
    }

    private fun unwrapThrowable(error: Throwable): Throwable {
        var current = error
        while (current is java.lang.reflect.InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current.cause?.takeIf { current.message.isNullOrBlank() } ?: current
    }

    private fun ensureGenAiReady(): GenAiState {
        val cachedModel = genAiModel
        val cachedTokenizer = genAiTokenizer
        val cachedModelClass = genAiModelClass
        val cachedTokenizerClass = genAiTokenizerClass
        val cachedParamsClass = genAiGeneratorParamsClass
        val cachedGeneratorClass = genAiGeneratorClass
        if (cachedModel != null &&
            cachedTokenizer != null &&
            cachedModelClass != null &&
            cachedTokenizerClass != null &&
            cachedParamsClass != null &&
            cachedGeneratorClass != null
        ) {
            return GenAiState(
                modelClass = cachedModelClass,
                tokenizerClass = cachedTokenizerClass,
                paramsClass = cachedParamsClass,
                generatorClass = cachedGeneratorClass,
                model = cachedModel,
                tokenizer = cachedTokenizer
            )
        }

        synchronized(lock) {
            val modelClass = Class.forName("ai.onnxruntime.genai.Model")
            val tokenizerClass = Class.forName("ai.onnxruntime.genai.Tokenizer")
            val paramsClass = Class.forName("ai.onnxruntime.genai.GeneratorParams")
            val generatorClass = Class.forName("ai.onnxruntime.genai.Generator")
            val modelDir = resolveGenAiModelDir()
            val model = modelClass.getConstructor(String::class.java).newInstance(modelDir.absolutePath)
            val tokenizer = tokenizerClass.getConstructor(modelClass).newInstance(model)

            genAiModelClass = modelClass
            genAiTokenizerClass = tokenizerClass
            genAiGeneratorParamsClass = paramsClass
            genAiGeneratorClass = generatorClass
            genAiModel = model
            genAiTokenizer = tokenizer

            return GenAiState(
                modelClass = modelClass,
                tokenizerClass = tokenizerClass,
                paramsClass = paramsClass,
                generatorClass = generatorClass,
                model = model,
                tokenizer = tokenizer
            )
        }
    }

    private fun encodePrompt(tokenizerClass: Class<*>, tokenizer: Any, prompt: String): Any {
        val encode = tokenizerClass.getMethod("encode", String::class.java)
        return encode.invoke(tokenizer, prompt)
            ?: throw IllegalStateException("Tokenizer returned null encoded sequence")
    }

    private fun setGeneratorInput(paramsClass: Class<*>, params: Any, encodedPrompt: Any) {
        val method = paramsClass.methods.firstOrNull { candidate ->
            candidate.name == "setInput" &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0].isAssignableFrom(encodedPrompt.javaClass)
        } ?: throw IllegalStateException(
            "GeneratorParams.setInput() overload for ${encodedPrompt.javaClass.name} was not found."
        )
        method.invoke(params, encodedPrompt)
    }

    private fun appendGeneratorInput(generatorClass: Class<*>, generator: Any, encodedPrompt: Any) {
        val method = generatorClass.methods.firstOrNull { candidate ->
            candidate.name == "appendTokenSequences" &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0].isAssignableFrom(encodedPrompt.javaClass)
        } ?: generatorClass.methods.firstOrNull { candidate ->
            candidate.name == "appendTokens" &&
                candidate.parameterCount == 1 &&
                encodedPrompt is IntArray &&
                candidate.parameterTypes[0] == IntArray::class.java
        } ?: throw IllegalStateException(
            "Generator input overload for ${encodedPrompt.javaClass.name} was not found."
        )
        method.invoke(generator, encodedPrompt)
    }

    private fun isGeneratorDone(generatorClass: Class<*>, generator: Any): Boolean {
        val method = generatorClass.getMethod("isDone")
        return method.invoke(generator) as? Boolean ?: false
    }

    private fun decodeGenerated(tokenizerClass: Class<*>, tokenizer: Any, generated: Any?): String {
        if (generated == null) return ""
        if (generated is IntArray) {
            val decode = tokenizerClass.getMethod("decode", IntArray::class.java)
            return decode.invoke(tokenizer, generated)?.toString().orEmpty()
        }

        val decodeMethod = tokenizerClass.methods.firstOrNull { method ->
            method.name == "decode" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(generated.javaClass)
        }
        if (decodeMethod != null) {
            return decodeMethod.invoke(tokenizer, generated)?.toString().orEmpty()
        }

        val decodeBatchMethod = tokenizerClass.methods.firstOrNull { method ->
            method.name == "decodeBatch" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(generated.javaClass)
        } ?: throw IllegalStateException(
            "Tokenizer decode/decodeBatch overload for ${generated.javaClass.name} was not found."
        )
        val decoded = decodeBatchMethod.invoke(tokenizer, generated) ?: return ""
        return when (decoded) {
            is Array<*> -> decoded.joinToString("\n") { it?.toString().orEmpty() }.trim()
            else -> decoded.toString()
        }
    }

    private fun setSearchOption(paramsClass: Class<*>, params: Any, key: String, value: Double) {
        val method = paramsClass.methods.firstOrNull { candidate ->
            candidate.name == "setSearchOption" &&
                candidate.parameterCount == 2 &&
                candidate.parameterTypes[0] == String::class.java &&
                (candidate.parameterTypes[1] == java.lang.Double.TYPE ||
                    candidate.parameterTypes[1] == java.lang.Float.TYPE ||
                    candidate.parameterTypes[1] == java.lang.Integer.TYPE)
        } ?: return

        when (method.parameterTypes[1]) {
            java.lang.Double.TYPE -> method.invoke(params, key, value)
            java.lang.Float.TYPE -> method.invoke(params, key, value.toFloat())
            java.lang.Integer.TYPE -> method.invoke(params, key, value.toInt())
        }
    }

    private fun setSearchOption(paramsClass: Class<*>, params: Any, key: String, value: Boolean) {
        val method = paramsClass.methods.firstOrNull { candidate ->
            candidate.name == "setSearchOption" &&
                candidate.parameterCount == 2 &&
                candidate.parameterTypes[0] == String::class.java &&
                candidate.parameterTypes[1] == java.lang.Boolean.TYPE
        } ?: return
        method.invoke(params, key, value)
    }

    private fun invokeOptional(
        targetClass: Class<*>,
        target: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>
    ): Any? {
        val method = runCatching { targetClass.getMethod(name, *parameterTypes) }.getOrNull() ?: return null
        return method.invoke(target, *args)
    }

    private fun invokeRequired(
        targetClass: Class<*>,
        target: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>
    ): Any? {
        val method = targetClass.getMethod(name, *parameterTypes)
        return method.invoke(target, *args)
    }

    private fun isRawOnnxRuntimeAvailable(): Boolean = runCatching {
        Class.forName("ai.onnxruntime.OrtEnvironment")
        Class.forName("ai.onnxruntime.OnnxTensor")
        Class.forName("ai.onnxruntime.OrtSession")
        true
    }.getOrDefault(false)

    private fun createOnnxTensor(floats: FloatArray, shape: LongArray): Any {
        val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
        val tensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
        val env = envClass.getMethod("getEnvironment").invoke(null)
        return tensorClass
            .getMethod("createTensor", envClass, FloatBuffer::class.java, LongArray::class.java)
            .invoke(null, env, FloatBuffer.wrap(floats), shape)
            ?: throw IllegalStateException("OnnxTensor.createTensor returned null")
    }

    private fun runRawSession(modelSession: Any, inputName: String, tensor: Any): Any {
        val run = modelSession.javaClass.methods.firstOrNull { method ->
            method.name == "run" && method.parameterCount == 1 && Map::class.java.isAssignableFrom(method.parameterTypes[0])
        } ?: throw IllegalStateException("OrtSession.run(Map) was not found.")
        return run.invoke(modelSession, mapOf(inputName to tensor))
            ?: throw IllegalStateException("OrtSession.run returned null")
    }

    private fun firstOnnxOutputValue(result: Any): Any? {
        val optional = result.javaClass.getMethod("get", Integer.TYPE).invoke(result, 0) ?: return null
        val output = optional.javaClass.getMethod("orElse", Any::class.java).invoke(optional, null) ?: return null
        return output.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
            ?.invoke(output)
    }

    private fun <T> useAutoCloseable(obj: Any, block: (Any) -> T): T {
        return try {
            block(obj)
        } finally {
            closeQuietly(obj)
        }
    }

    private fun ensureSession(): Any {
        session?.let { return it }
        synchronized(lock) {
            session?.let { return it }
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val optionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val env = envClass.getMethod("getEnvironment").invoke(null)
            val modelPath = ensureModelCopied(config.localOnnxModelAsset)
            val options = optionsClass.getConstructor().newInstance()
            runCatching { optionsClass.getMethod("setIntraOpNumThreads", Integer.TYPE).invoke(options, 1) }
            val created = envClass.getMethod("createSession", String::class.java, optionsClass)
                .invoke(env, modelPath, options)
            session = created
            return created
        }
    }

    private fun ensureModelDirCopied(assetDir: String): File {
        val safeDirName = assetDir.replace('/', '_').ifBlank { "genai_model" }
        val destinationRoot = File(context.filesDir, "genai/$safeDirName")
        copyAssetDir(assetDir, destinationRoot)
        return destinationRoot
    }

    private fun resolveGenAiModelDir(): File {
        val filesDir = configuredModelDirCandidates().firstOrNull { it.isDirectory }
        if (filesDir != null) {
            validateGenAiFileBundle(filesDir)
            return filesDir
        }

        if (config.localGenAiModelAssetDir.isNotBlank()) {
            validateGenAiAssetBundle(config.localGenAiModelAssetDir)
            return ensureModelDirCopied(config.localGenAiModelAssetDir)
        }

        throw IllegalStateException(
            "Local GenAI model directory not found. Expected files under '${config.localGenAiModelFilesDir}'."
        )
    }

    private fun configuredModelDirCandidates(): List<File> {
        val rawPath = config.localGenAiModelFilesDir.trim()
        if (rawPath.isBlank()) return emptyList()

        val configured = File(rawPath)
        if (configured.isAbsolute) return listOf(configured)

        return listOfNotNull(
            File(context.filesDir, rawPath),
            context.getExternalFilesDir(null)?.let { File(it, rawPath) },
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                rawPath
            ),
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                configured.name
            ),
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "Dora_Pilot/$rawPath"
            )
        )
    }

    private fun validateGenAiFileBundle(modelDir: File) {
        val entries = modelDir.list()?.toSet().orEmpty()
        require(entries.isNotEmpty()) {
            "Model directory '${modelDir.absolutePath}' was not found or is empty."
        }
        val required = listOf("genai_config.json", "model.onnx", "tokenizer.json")
        val missing = required.filterNot { it in entries }
        require(missing.isEmpty()) {
            "Model directory '${modelDir.absolutePath}' is missing required files: ${missing.joinToString(", ")}"
        }
    }

    private fun validateGenAiAssetBundle(assetDir: String) {
        require(assetDir.isNotBlank()) { "DORA_LOCAL_GENAI_MODEL_ASSET_DIR is empty." }
        val entries = context.assets.list(assetDir)?.toSet().orEmpty()
        require(entries.isNotEmpty()) {
            "Asset directory '$assetDir' was not found or is empty."
        }
        val required = listOf("genai_config.json", "model.onnx", "tokenizer.json")
        val missing = required.filterNot { it in entries }
        require(missing.isEmpty()) {
            "Asset directory '$assetDir' is missing required files: ${missing.joinToString(", ")}"
        }
    }

    private fun copyAssetDir(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        destination.mkdirs()
        for (child in children) {
            val childAssetPath = if (assetPath.isBlank()) child else "$assetPath/$child"
            val childDestination = File(destination, child)
            copyAssetDir(childAssetPath, childDestination)
        }
    }

    private fun ensureModelCopied(assetPath: String): String {
        val fileName = assetPath.substringAfterLast('/')
        val modelFile = File(context.filesDir, "models/$fileName")
        if (!modelFile.exists() || modelFile.length() == 0L) {
            modelFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return modelFile.absolutePath
    }

    private fun jsonToLongArray(array: JSONArray): LongArray {
        return LongArray(array.length()) { idx ->
            array.optLong(idx, 0L)
        }
    }

    private fun jsonToFloatArray(array: JSONArray): FloatArray {
        return FloatArray(array.length()) { idx ->
            array.optDouble(idx, 0.0).toFloat()
        }
    }

    private fun convertOutput(output: Any): Any {
        return when (output) {
            is FloatArray -> JSONArray().apply { output.forEach { put(it.toDouble()) } }
            is LongArray -> JSONArray().apply { output.forEach { put(it) } }
            is Array<*> -> JSONArray().apply { output.forEach { put(convertNested(it)) } }
            else -> output.toString()
        }
    }

    private fun convertNested(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
            is LongArray -> JSONArray().apply { value.forEach { put(it) } }
            is IntArray -> JSONArray().apply { value.forEach { put(it) } }
            is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(convertNested(it)) } }
            else -> value
        }
    }

    private fun closeQuietly(obj: Any?) {
        if (obj == null) return
        when (obj) {
            is AutoCloseable -> runCatching { obj.close() }
            else -> {
                val close = runCatching { obj.javaClass.getMethod("close") }.getOrNull()
                if (close != null && close.parameterCount == 0) {
                    runCatching { close.invoke(obj) }
                }
            }
        }
    }

    private data class GenAiState(
        val modelClass: Class<*>,
        val tokenizerClass: Class<*>,
        val paramsClass: Class<*>,
        val generatorClass: Class<*>,
        val model: Any,
        val tokenizer: Any
    )
}
