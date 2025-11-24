// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    // 启用 KSP (Kotlin Symbol Processing)
    alias(libs.plugins.ksp) apply false
}