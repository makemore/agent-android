plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

// JitPack injects GROUP (com.github.makemore.agent-android) and VERSION (the
// git tag) as env vars; deriving from them keeps the published POMs — and the
// agent-frontend → agent-client inter-module dependency — resolvable from
// JitPack. Locally we fall back to the canonical group / agentVersion property.
group = System.getenv("GROUP") ?: "com.makemore"
version = System.getenv("VERSION")
    ?: providers.gradleProperty("agentVersion").getOrElse("0.0.0-SNAPSHOT")

android {
    namespace = "com.makemore.agentclient"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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

    // Publish only the `release` variant as a Maven artifact, with sources.
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
                artifactId = "agent-client"
                pom {
                    name.set("agent-client")
                    description.set(
                        "Product-neutral runtime client, models, SSE transport and " +
                            "headless reducer primitives for AI agent frontends (Android)."
                    )
                    url.set("https://github.com/makemore/agent-android")
                }
            }
        }
        // No remote `repositories` block: distribution is via JitPack, which
        // builds the public repo on a tag and runs `publishToMavenLocal`.
    }
}

dependencies {
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
