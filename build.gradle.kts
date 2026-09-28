// Declared once here so every module resolves the same plugin versions.
// AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no
// kotlin-android plugin; the compose plugin still pins the Kotlin version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
}
