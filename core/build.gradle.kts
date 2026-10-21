// Pure Kotlin, for two phones. No Android imports are allowed in this module,
// which is what lets `./gradlew :core:jvmTest` run on a laptop or the Pi with
// no SDK installed - and no java.* imports in commonMain either, which is what
// lets the same code compile for the iPhone. okio and kotlinx-datetime stand
// in for java.io and java.time; the few things with no common equivalent are
// expect/actual in jvmMain and iosMain.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Kotlin/Native has no linux-arm64 host, so the Pi cannot compile the iOS
// targets at all. They are declared on a Mac (CI) or when asked for with
// -Pios, and the Pi builds and tests the JVM half as it always has.
val withIos = System.getProperty("os.name").startsWith("Mac") || providers.gradleProperty("ios").isPresent

kotlin {
    jvmToolchain(17)
    jvm()
    if (withIos) {
        iosArm64()
        iosSimulatorArm64()
        iosX64()
    }

    sourceSets {
        commonMain.dependencies {
            // Runtime library only (no compiler plugin): the JSONL ride log.
            implementation(libs.kotlinx.serialization.json)
            api(libs.okio)
            api(libs.kotlinx.datetime)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.sqlite.jdbc)
            // Gradle 9 no longer supplies the JUnit Platform launcher implicitly.
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
