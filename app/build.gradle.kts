plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.urbanmood"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.urbanmood"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }) throw GradleException("Use scripts/build-release.ps1 to sign with a private local key.")
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = privateRelease
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    val cameraVersion = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("androidx.camera:camera-video:$cameraVersion")
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
