// Top-level build.gradle.kts

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
//    alias(libs.plugins.kotlin.kapt) apply false

    // Google Services (if you use Firebase)
    id("com.google.gms.google-services") version "4.4.3" apply false
}
