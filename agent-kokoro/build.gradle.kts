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

android {
    namespace = "com.makemore.agentfrontend.voice.kokoro"
    compileSdk = 35

    defaultConfig {
        // sherpa-onnx's AAR declares minSdk 21; the widget needs 26.
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
        // Provider/model-manager tests run on the JVM with fakes for the
        // engine, audio output and network; stubbed android.* calls (only
        // android.util.Log on those paths) return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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
                        "Optional on-device neural text-to-speech (Kokoro-82M via sherpa-onnx) " +
                            "for agent-frontend's voice output."
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

    // sherpa-onnx Android AAR (Apache-2.0): Kotlin OfflineTts API + JNI and
    // onnxruntime for arm64-v8a, armeabi-v7a, x86, x86_64. JitPack serves
    // upstream's release AAR unchanged under this coordinate. Do NOT use
    // `com.github.k2-fsa:sherpa-onnx` — that aggregate also drags in the
    // desktop JVM natives.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8")

    // .tar.bz2 extraction of the downloaded model (Apache-2.0).
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // VoiceController (agent-frontend) exposes Compose state; the end-to-end
    // chunk-order test drives it on the JVM.
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.runtime:runtime")

    // Opt-in on-device smoke test with the real model (see KokoroOnDeviceSmokeTest).
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
