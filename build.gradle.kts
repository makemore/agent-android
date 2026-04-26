plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.makemore.agentfrontend"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        // Default stub-server URL for Level C streaming UI tests. The
        // standard Android emulator loopback maps `10.0.2.2` to the host
        // machine, where `clients/test-stub-server/server.py` runs on
        // port 8765. Override per-run with:
        //   ./gradlew connectedDebugAndroidTest \
        //     -Pandroid.testInstrumentationRunnerArguments.STUB_SERVER_URL=...
        testInstrumentationRunnerArguments["STUB_SERVER_URL"] =
            "http://10.0.2.2:8765"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Markdown rendering
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.35.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.35.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Video playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // The android.jar shipped to unit tests stubs out org.json; pull in the
    // real implementation so SSE fixture loaders can parse JSON on the JVM.
    testImplementation("org.json:json:20231013")

    // Instrumented tests (Level C streaming UI) — drive the real
    // ChatWidgetView composable through Compose semantics against the
    // Python stub server.
    // Espresso 3.7.0 / runner 1.7.0 are required for API 35+ emulators —
    // earlier releases reflect on `InputManager.getInstance()` (removed in
    // API 34) and throw `NoSuchMethodException` from `Espresso.onIdle()`
    // during every Compose UI test. 3.7.0 switched to `getSystemService`.
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

