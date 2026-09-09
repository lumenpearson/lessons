// AGP 9 compiles Kotlin itself, so `org.jetbrains.kotlin.android` must NOT be
// applied here or in any Android module - applying it is a hard build failure,
// not a warning. Pure-JVM modules still need `kotlin.jvm`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
