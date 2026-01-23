// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // 🟢 确保 Google Services 插件版本是最新的，以支持 Firebase 功能
    id("com.google.gms.google-services") version "4.4.1" apply false
}