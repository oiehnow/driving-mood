plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.drivingmood.pleospreview"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.drivingmood.pleospreview"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
