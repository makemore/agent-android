plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Same GROUP / VERSION scheme as the other modules (see RELEASING.md), so
// agent-kokoro ships from the same tag as agent-frontend.
group = System.getenv("GROUP") ?: "com.makemore"
version = System.getenv("VERSION")
    ?: providers.gradleProperty("agentVersion").getOrElse("0.0.0-SNAPSHOT")

// ONNX Runtime, pinned. 1.28.0 is the newest Android build without the
// telemetry client that 1.29+ start from a manifest ContentProvider (see
// README "On-device neural voice" > Licences and privacy). MIT licence.
val onnxRuntimeVersion = "1.28.0"

// Local copy of the Kokoro asset folder (tools/kokoro-assets/build/kokoro/v1)
// for the golden and end-to-end tests; they skip with a message when unset.
//   ./gradlew :agent-kokoro:testDebugUnitTest -PkokoroAssets=/path/to/kokoro/v1
// or KOKORO_ASSETS=/path/to/kokoro/v1 in the environment.
val kokoroAssets: String = providers.gradleProperty("kokoroAssets")
    .orElse(providers.environmentVariable("KOKORO_ASSETS"))
    .getOrElse("")

android {
    namespace = "com.makemore.agentfrontend.voice.kokoro"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Provider/engine tests run on the JVM with fakes for audio and network;
        // stubbed android.* calls return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            test.systemProperty("kokoro.assets", kokoroAssets)
            test.maxHeapSize = "3g"
            test.testLogging {
                events("skipped", "failed")
                showStandardStreams = true
            }
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// JVM unit tests use ONNX Runtime's desktop artifact (same ai.onnxruntime API,
// with macOS/Linux/Windows natives) instead of the Android AAR.
configurations.configureEach {
    if (name.contains("UnitTest")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "agent-kokoro"
                pom {
                    name.set("agent-kokoro")
                    description.set(
                        "Optional on-device neural text-to-speech (Kokoro-82M on ONNX Runtime) " +
                            "for agent-frontend's voice output.",
                    )
                    url.set("https://github.com/makemore/agent-android")
                }
            }
        }
    }
}

dependencies {
    // TTSProvider / LocalTTSEngine / VoiceDescriptor live in agent-frontend.
    api(project(":"))

    // ONNX Runtime for Android (MIT): full build (the Kokoro model needs
    // com.microsoft contrib ops), arm64-v8a, armeabi-v7a, x86, x86_64.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntimeVersion")
    // VoiceController (agent-frontend) exposes Compose state; the end-to-end
    // chunk-order test drives it on the JVM.
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.runtime:runtime")

    // On-device test with the real model (see KokoroOnDeviceTest).
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
