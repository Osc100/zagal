plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.herdr.mobile"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.osc100.zagal"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
        // The terminal's libghostty-vt is only built for these.
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }
    // Release signing, done by CI (release.yml): the keystore's path and its
    // one password (PKCS12), key alias `zagal`. Without them a release build
    // comes out unsigned, never signed with the debug key.
    val keystore = System.getenv("ZAGAL_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("ZAGAL_PASSWORD")
            keyAlias = "zagal"
            keyPassword = storePassword
        }
    }
    // Debug installs beside a release, as its own app: "Zagal dev", its own id
    // and its own link scheme, so neither answers the other's links.
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Zagal dev")
            manifestPlaceholders["scheme"] = "zagal-dev"
            buildConfigField("String", "SCHEME", "\"zagal-dev\"")
        }
        // End-to-end runs (e2e/run.sh) clear its data every time: its own app,
        // so a debug install keeps its hosts and its key.
        create("e2e") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".e2e"
            versionNameSuffix = "-e2e"
            resValue("string", "app_name", "Zagal e2e")
            manifestPlaceholders["scheme"] = "zagal-e2e"
            buildConfigField("String", "SCHEME", "\"zagal-e2e\"")
            matchingFallbacks += "debug"
        }
        release {
            resValue("string", "app_name", "Zagal")
            manifestPlaceholders["scheme"] = "zagal"
            buildConfigField("String", "SCHEME", "\"zagal\"")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.md")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":terminal"))
    implementation(project(":ssh"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    testImplementation(kotlin("test-junit"))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)
    implementation(libs.biometric)
    implementation(libs.fragment)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidsvg)
}
