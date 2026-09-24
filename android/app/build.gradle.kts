plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dungeonwithin.savemanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dungeonwithin.savemanager"
        minSdk = 26
        targetSdk = 34

        // Monotonic build number based on git commit count with base offset.
        // Legacy CI run numbers ranged from 1 to 28 across separate workflows.
        // Base 1000 guarantees ANY new build (CI or local) is strictly higher
        // than legacy builds, allowing seamless updates without uninstalling.
        val gitCommitCount = runCatching {
            val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .directory(rootDir)
                .start()
            val text = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0) text.toIntOrNull() else null
        }.getOrNull()

        val baseOffset = 1000
        val computedVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
            ?: if (gitCommitCount != null && gitCommitCount > 0) {
                baseOffset + gitCommitCount
            } else {
                val runNum = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
                baseOffset + runNum
            }

        versionCode = computedVersionCode
        versionName = "1.0.$computedVersionCode"
    }

    signingConfigs {
        // Pinned debug key: throwaway CI runners generate a fresh key every
        // build, which changes the APK signature and forces uninstalls.
        // (Debug-only key for sideloading; never use for store releases.)
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    base {
        // Output is DungeonWithinSaveManager-debug.apk instead of app-debug.apk.
        archivesName.set("DungeonWithinSaveManager")
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
    // Shizuku privileged shell access (non-root path to Android/data).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Material You (dynamic color, DayNight): app + overlay buttons.
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
