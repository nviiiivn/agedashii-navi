plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "online.iamprettyfamous.navi"
    compileSdk = 36

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
}
