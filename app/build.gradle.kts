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
        versionCode = 9
        versionName = "0.9.0"
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
    androidResources {
        noCompress += listOf("pmtiles")
        // -PnoVoice: the app WITHOUT her Arnold set, to hand to someone else.
        // Her words: "i just need the option there so i can ship it without".
        // The clips stay on the build machine; the app falls through to the
        // phone's own voice and Settings > Import voice takes a voicepack.
        if (providers.gradleProperty("noVoice").isPresent) {
            ignoreAssetsPatterns += listOf("voice")
        }
    }

    // Robolectric needs the real resources to render Compose for the screenshots.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Her map data INSIDE the APK, so the phone needs one download and no cable:
// build with -PnaviData=<dir>, a directory holding data/ as
// tools/make-datapack.py writes it (map, router tiles, places list and a
// MANIFEST). 217 MB, never in git. Without the property the APK carries no
// data and the app says what is missing, as before.
providers.gradleProperty("naviData").orNull?.let { dir ->
    // Fail HERE, not on her phone. The app reads assets/data/MANIFEST, so the
    // folder given must HOLD data/: pointing at data/ itself put every file one
    // level too high, and a clean install asked her to copy the map by cable
    // (navi-kotlin-20260928.apk, caught on emulator-5556, 2026-09-28).
    require(file(dir).resolve("data/MANIFEST").isFile) {
        "-PnaviData=$dir holds no data/MANIFEST: pass the folder that CONTAINS data/, not data/ itself"
    }
    // An APK she installs carries her built-in voice (tools/stage-voice.sh);
    // a fresh checkout has none, because the clips are never committed.
    // A build FOR HER must carry her voice - shipping an APK that silently
    // had none happened once already (navi v1.16.0, Flet) and is caught
    // here rather than on her phone. A -PnoVoice build is the deliberate
    // exception: that one is for other people.
    require(providers.gradleProperty("noVoice").isPresent ||
        file("src/main/assets/voice").listFiles().orEmpty().any { it.isFile }) {
        "no built-in voice in app/src/main/assets/voice: run tools/stage-voice.sh " +
            "before building her APK, or pass -PnoVoice to build one without it"
    }
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
