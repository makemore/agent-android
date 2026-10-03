pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "9.0.1"
        id("com.android.application") version "9.0.1"
        id("org.jetbrains.kotlin.android") version "2.1.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx's Android AAR (used only by :agent-kokoro) is served
        // by JitPack — the same repository consumers already use for this
        // library. Scoped so nothing else resolves from it.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

rootProject.name = "agent-frontend"

// Core protocol/transport library — zero Compose dependencies.
include(":agent-client")

// Optional on-device neural TTS (Kokoro-82M via sherpa-onnx). A separate
// artifact so apps that don't use it don't ship its native libraries.
include(":agent-kokoro")

// Sample host app — manual scenario launcher for the chat widget. Mirrors
// `clients/agent-ios/Example`. Open this directory in Android Studio and
// run the `:example` configuration to launch the launcher on a device or
// emulator. The library itself is consumed via `project(":")`.
include(":example")

