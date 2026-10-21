pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "agedashii-navi"

// :core is pure Kotlin with no Android in it, so its tests run on any laptop.
include(":core")

// The iPhone app's Kotlin half (Compose Multiplatform over :core). Kotlin/Native
// has no linux-arm64 host, so it is only included on a Mac or with -Pios.
val mac = System.getProperty("os.name").startsWith("Mac")
if (mac || providers.gradleProperty("ios").isPresent) include(":ios")

// -PiosOnly leaves the Android app out, so the Mac that builds the iPhone app
// needs no Android SDK.
if (!providers.gradleProperty("iosOnly").isPresent) include(":app")
