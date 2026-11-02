// The terminal view: libghostty-vt drawn by an Android View. The native side
// (native/: the JNI bridge in Zig, with ghostty's `ghostty-vt` module from the
// `ghostty` submodule) is built here with Zig, one `zig build` per ABI. The
// view started from expo-libghostty 0.8.1 (MIT), without its Expo wrapper.

plugins {
    alias(libs.plugins.android.library)
}

/** Each ABI's Zig target: the API level is minSdk, but x86_64 (emulators
 * only) needs 29, where Bionic first exports the TLS helper Zig's code uses. */
val ZIG_TARGETS = mapOf("arm64-v8a" to "aarch64-linux-android.26", "x86_64" to "x86_64-linux-android.29")

/** Where each ABI's `libherdrterminal.so` lands, for packaging. */
val jniOut = layout.buildDirectory.dir("jniLibs")

android {
    namespace = "dev.herdr.terminal"
    compileSdk = 37
    // For its Bionic headers and libraries; there's no C/C++ compiled with it.
    ndkVersion = "27.1.12297006"
    defaultConfig {
        minSdk = 26
        // The only ABIs built; 32-bit isn't supported.
        ndk {
            abiFilters += ZIG_TARGETS.keys
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories += jniOut.get().asFile.path
            // Fonts for what the system fallback lacks: Nerd Font glyphs (Symbols
            // Nerd Font Mono) and other symbols (a Noto Sans Symbols 2 subset).
            assets.directories += "vendor/fonts"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.core.ktx)
}

// Zig comes from mise.toml; ghostty fetches its own Zig packages on the first build.
val zigBuilds = ZIG_TARGETS.map { (abi, target) ->
    tasks.register<Exec>("zigJni-$abi") {
        val out = jniOut.map { it.dir(abi) }
        val cache = layout.buildDirectory.dir("zig-cache")
        val ndk = androidComponents.sdkComponents.ndkDirectory
        workingDir = file("native")
        inputs.files(fileTree("native"), fileTree("ghostty") { exclude(".zig-cache/**", "zig-out/**") })
        outputs.dir(out)
        doFirst { environment("ANDROID_NDK_HOME", ndk.get().asFile.absolutePath) }
        commandLine(
            System.getenv("ZIG") ?: "zig", "build",
            "-Dtarget=$target", "-Doptimize=ReleaseFast",
            "--prefix", layout.buildDirectory.dir("zig-out/$abi").get().asFile.absolutePath,
            "--prefix-lib-dir", out.get().asFile.absolutePath,
            "--cache-dir", cache.get().asFile.absolutePath,
        )
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(zigBuilds) }
