// Isolated verification module using the application analysis sources.
plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
}
android {
    namespace = "kr.urbanmood.vision.verification"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets {
        getByName("main") {
            java.srcDirs("../../../app/src/main/java/kr/urbanmood/vision", "../../../app/src/main/java/kr/urbanmood/contracts")
            assets.srcDirs("../../../app/src/main/assets")
        }
        getByName("test").java.srcDirs("../../../app/src/test/java/kr/urbanmood/vision", "src/test/kotlin")
    }
}
tasks.withType<Test>().configureEach {
    systemProperty("visionEvaluationDir", layout.buildDirectory.dir("offline").get().asFile.absolutePath)
    systemProperty("visionEvaluationReport", file("../evaluation/pixel-results.tsv").absolutePath)
}
dependencies {
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
