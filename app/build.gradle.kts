plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Test only: screenshots of the dash (recordRoborazziDebug). It adds tasks,
    // not dependencies to the APK - checked with :app:dependencies.
    alias(libs.plugins.roborazzi)
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
        // Both phones are arm64; x86_64 is only for the emulator on Tower, and
        // -PnaviAbis=arm64-v8a leaves its 22 MB out of the APK she downloads.
        ndk {
            abiFilters += (providers.gradleProperty("naviAbis").orNull ?: "arm64-v8a,x86_64").split(",")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // The map tiles are gzip'd inside the archive already; squeezing them again
    // only slows the first-start unpack. The tar and the places list do shrink.
    androidResources { noCompress += listOf("pmtiles") }

    // Robolectric needs the real resources to render Compose for the screenshots.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Her map data INSIDE the APK, so the phone needs one download and no cable:
// build with -PnaviData=<dir>, a directory holding data/ as
// tools/make-datapack.py writes it (map, router tiles, places list and a
// MANIFEST). 217 MB, never in git. Without the property the APK carries no
// data and the app says what is missing, as before.
providers.gradleProperty("naviData").orNull?.let { dir ->
    androidComponents {
        onVariants { variant -> variant.sources.assets?.addStaticSourceDirectory(dir) }
    }
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

    // Offline routing: Valhalla 3.6.3 on the phone (MIT). The tiles are built
    // with exactly that Valhalla; TileVersionGuard refuses any other.
    implementation(libs.valhalla.mobile)
    implementation(libs.valhalla.models.config)

    // test only: none of these reach the APK.
    testImplementation(libs.junit4)
    // Reads the shipped map styles in DashOverMapTest; already :core's, so no new version.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
