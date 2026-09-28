// Pure Kotlin/JVM. No Android imports are allowed in this module, which is what
// lets `./gradlew :core:test` run on a laptop or the Pi with no SDK installed.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Runtime library only (no compiler plugin): the JSONL ride log.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    // Gradle 9 no longer supplies the JUnit Platform launcher implicitly.
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
