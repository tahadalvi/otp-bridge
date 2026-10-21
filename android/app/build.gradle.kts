import java.util.Properties

// Signing config lives outside the repo. keystore.properties is gitignored and
// the keystore itself sits in %LOCALAPPDATA%\otp-bridge. Without it the release
// build still runs, it just produces an unsigned APK.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Written by scripts/build-apk.ps1, which mints a pairing token from the relay.
// Absent is fine — the app then falls back to manual token entry in Settings.
val pairPropsFile = rootProject.file("../../otp-bridge-state/pair.properties")
val pairProps = Properties().apply {
    if (pairPropsFile.exists()) pairPropsFile.inputStream().use { load(it) }
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.taha.otpbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.taha.otpbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode")?.toIntOrNull() ?: 1
        versionName = versionProps.getProperty("versionName") ?: "1.0"

        // A pairing token, not the phone token: multi-use but capped and
        // time-limited, and redeemed server-side. A leaked APK goes dead.
        buildConfigField(
            "String", "PAIR_TOKEN",
            "\"${pairProps.getProperty("pairToken").orEmpty()}\"",
        )
        buildConfigField(
            "String", "RELAY_URL",
            "\"${pairProps.getProperty("relayUrl") ?: "https://relay.example.com/otp-bridge"}\"",
        )
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// These are deliberately NOT the newest published versions. Current AndroidX
// releases (core-ktx 1.19, lifecycle 2.11, …) require AGP 9.1+ and compileSdk 37,
// which would mean a new Gradle distribution and a new SDK platform download.
// This set is the newest that builds against the toolchain already on this
// machine: AGP 8.13.2, Gradle 8.14.3, compileSdk 36.
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended is deliberately omitted: Icons.Default.Add and
    // Icons.Default.Delete both live in the core icon set that material3 pulls in.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
