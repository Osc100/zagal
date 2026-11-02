// The protocol client: framing, JSON-RPC, events, model. No UI and no
// platform imports, so it runs on the JVM for tests and on iOS later.
// iOS sources compile on Linux too, which keeps commonMain honest; only
// linking and running iOS tests needs macOS.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Built by whatever JDK runs Gradle (mise.toml); Java 17 bytecode, like the Android modules.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val fixtures = layout.projectDirectory.dir("fixtures").asFile.absolutePath
tasks.withType<Test>().configureEach { environment("HERDR_FIXTURES", fixtures) }
tasks.withType<KotlinNativeTest>().configureEach { environment("HERDR_FIXTURES", fixtures) }
