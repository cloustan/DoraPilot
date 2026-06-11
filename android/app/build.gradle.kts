import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

fun configValue(name: String, default: String = ""): String {
    val fromLocal = localProps.getProperty(name)?.trim().orEmpty()
    if (fromLocal.isNotEmpty()) return fromLocal
    val fromProject = (project.findProperty(name) as String?)?.trim().orEmpty()
    if (fromProject.isNotEmpty()) return fromProject
    val fromEnv = System.getenv(name)?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return fromEnv
    return default
}

fun quoted(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
fun floatLiteral(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.endsWith("f", ignoreCase = true)) trimmed else "${trimmed}f"
}
val localGenAiSourceDir = configValue("DORA_LOCAL_GENAI_SOURCE_DIR", "")
val includeLocalGenAiAar = configValue("DORA_INCLUDE_LOCAL_GENAI_AAR", "false")
    .toBooleanStrictOrNull() ?: false
val legacyNativePackaging = configValue("DORA_LEGACY_NATIVE_PACKAGING", "false")
    .toBooleanStrictOrNull() ?: false

android {
    namespace = "com.dorapilot"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            if (localGenAiSourceDir.isNotBlank()) {
                assets.srcDir(localGenAiSourceDir)
            }
        }
    }

    defaultConfig {
        applicationId = "com.dorapilot"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "DORA_BACKEND_ENDPOINT",
            quoted(configValue("DORA_BACKEND_ENDPOINT", "https://api.openai.com/v1/chat/completions"))
        )
        buildConfigField(
            "String",
            "DORA_BACKEND_MODEL",
            quoted(configValue("DORA_BACKEND_MODEL", "gpt-4o-mini"))
        )
        buildConfigField(
            "String",
            "DORA_BACKEND_API_KEY",
            quoted(configValue("DORA_BACKEND_API_KEY", ""))
        )
        buildConfigField(
            "String",
            "DORA_BACKEND_HEADERS_JSON",
            quoted(configValue("DORA_BACKEND_HEADERS_JSON", "{}"))
        )
        buildConfigField(
            "boolean",
            "DORA_VOICE_RESPONSES_ENABLED",
            configValue("DORA_VOICE_RESPONSES_ENABLED", "false").lowercase()
        )
        buildConfigField(
            "boolean",
            "DORA_LOCAL_AI_ENABLED",
            configValue("DORA_LOCAL_AI_ENABLED", "false").lowercase()
        )
        buildConfigField(
            "String",
            "DORA_LOCAL_ONNX_MODEL_ASSET",
            quoted(configValue("DORA_LOCAL_ONNX_MODEL_ASSET", ""))
        )
        buildConfigField(
            "String",
            "DORA_LOCAL_ONNX_INPUT_NAME",
            quoted(configValue("DORA_LOCAL_ONNX_INPUT_NAME", "input"))
        )
        buildConfigField(
            "String",
            "DORA_LOCAL_ONNX_OUTPUT_NAME",
            quoted(configValue("DORA_LOCAL_ONNX_OUTPUT_NAME", "output"))
        )
        buildConfigField(
            "String",
            "DORA_LOCAL_GENAI_MODEL_ASSET_DIR",
            quoted(configValue("DORA_LOCAL_GENAI_MODEL_ASSET_DIR", "qwen2.5-coder-1.5b-instruct-onnx-genai-int4"))
        )
        buildConfigField(
            "String",
            "DORA_LOCAL_GENAI_MODEL_FILES_DIR",
            quoted(configValue("DORA_LOCAL_GENAI_MODEL_FILES_DIR", "models/qwen2.5-coder-1.5b-instruct-onnx-genai-int4"))
        )
        buildConfigField(
            "int",
            "DORA_LOCAL_GENAI_MAX_TOKENS",
            configValue("DORA_LOCAL_GENAI_MAX_TOKENS", "48")
        )
        buildConfigField(
            "float",
            "DORA_LOCAL_GENAI_TEMPERATURE",
            floatLiteral(configValue("DORA_LOCAL_GENAI_TEMPERATURE", "0.7"))
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // Keep native libraries uncompressed and zip-aligned by AGP. This is
            // required for Android devices with 16 KB memory pages, but each
            // native library must still be built with 16 KB ELF LOAD alignment.
            useLegacyPackaging = legacyNativePackaging
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    if (includeLocalGenAiAar) {
        // Optional local GenAI runtime. Only use an AAR whose bundled .so files
        // pass 16 KB ELF alignment checks; older public GenAI AARs fail Play's
        // 16 KB page-size validation.
        implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("onnxruntime-genai-android*.aar"))))
    }
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    // Encrypted on-device store for personal context (SQLCipher + Keystore-backed key).
    // sqlcipher-android 4.16.0 ships 16 KB page-size compatible native libraries.
    implementation("net.zetetic:sqlcipher-android:4.16.0")
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
