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
    val releaseStore = providers.environmentVariable("URBANMOOD_STORE_FILE").orNull
    val releasePassword = providers.environmentVariable("URBANMOOD_STORE_PASSWORD").orNull
    val privateRelease = if (!releaseStore.isNullOrBlank() && !releasePassword.isNullOrBlank()) {
        signingConfigs.create("privateRelease") {
            storeFile = file(releaseStore)
            storePassword = releasePassword
            keyAlias = "urbanmood-release"
            keyPassword = releasePassword
        }
    } else null
    if (privateRelease == null && gradle.startParameter.taskNames.any {
        it.endsWith("assembleRelease") || it.endsWith("bundleRelease") || it.endsWith("packageRelease")
    }) throw GradleException("Use scripts/build-release.ps1 -Module pleos-preview to sign with a private local key.")
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = privateRelease
        }
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
