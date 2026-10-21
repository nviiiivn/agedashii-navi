// The iPhone app's Kotlin half: the Ride screen in Compose Multiplatform, over
// the same :core the Android app uses. Built into XnavKit.framework, which the
// Swift app in iosApp/ hosts and hands the two things only Swift can reach -
// MapLibre's map view and Valhalla's router (IosHosts.kt).
//
// The pure-Compose screens are not rewritten: they are compiled straight from
// the Android app's sources (`sharedUi` below), so the dash, the turn banner,
// the button column and Where to are one file each for both phones. The platform
// objects they read (Navigator, PlacesStore, ...) have iOS twins here with the
// same names and shapes.
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Copied, not edited: these files are the Android app's own, byte for byte.
val sharedUi = tasks.register<Sync>("sharedUi") {
    from("../app/src/main/kotlin")
    include(
        "online/iamprettyfamous/navi/dash/Dash.kt",
        "online/iamprettyfamous/navi/dash/TurnBannerView.kt",
        "online/iamprettyfamous/navi/ui/ButtonColumn.kt",
        "online/iamprettyfamous/navi/ui/Help.kt",
        "online/iamprettyfamous/navi/ui/Palettes.kt",
        "online/iamprettyfamous/navi/ui/RouteBands.kt",
        "online/iamprettyfamous/navi/ui/WhereTo.kt",
    )
    into(layout.buildDirectory.dir("generated/sharedUi"))
}

kotlin {
    val xcf = XCFramework("XnavKit")
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "XnavKit"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        // The Android app's screens that touch nothing but Compose and :core,
        // compiled as iOS sources so they see the iOS twins of what they read.
        iosMain.get().kotlin.srcDir(sharedUi)

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}
