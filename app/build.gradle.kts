plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "online.iamprettyfamous.navi"
    // Compose BOM 2026.09.00 and core-ktx 1.19.1 refuse to compile against
    // less than 37. Compile only: targetSdk stays 36 until 37's runtime
    // behaviour changes have been read.
    compileSdk = 37

    defaultConfig {
        // A new id, so this installs beside the Flet app
        // (online.iamprettyfamous.agedashii.navi) while it is being built.
        applicationId = "online.iamprettyfamous.agedashii.navikt"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Both phones are arm64; x86_64 is only for the emulator on Tower.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    // foundation and ui only: no Material, the look is hers.
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.android)

    // The raw SDK, hosted in an AndroidView: its LocationComponent does GPS
    // follow and draws the bike, and pmtiles://file:// is documented for it.
    implementation(libs.maplibre.android)
}
