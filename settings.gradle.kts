pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "8.7.3"
        id("com.android.application") version "8.7.3"
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
    }
}

rootProject.name = "agent-frontend"

// Core protocol/transport library — zero Compose dependencies.
include(":agent-client")

// Sample host app — manual scenario launcher for the chat widget. Mirrors
// `clients/agent-ios/Example`. Open this directory in Android Studio and
// run the `:example` configuration to launch the launcher on a device or
// emulator. The library itself is consumed via `project(":")`.
include(":example")

