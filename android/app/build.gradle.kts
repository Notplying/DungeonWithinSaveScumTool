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
        // CI run number keeps every cloud build newer than the last, so
        // installs update instead of clashing. Local builds stay at 1.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0"
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
        release {
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
