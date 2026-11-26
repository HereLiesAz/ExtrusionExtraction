import java.util.Properties
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val state = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val parts = listOf("Major", "Minor", "Patch", "Build").map { state.getProperty("version" + it).toInt() }
// Central prereleases use the monotonically increasing desktop workflow run
// number as Android's upgrade code. Local builds retain the checked-in baseline.
val releaseCode = providers.gradleProperty("eeReleaseVersionCode").orNull?.let {
    it.toIntOrNull()?.takeIf { code -> code in 1..2100000000 }
        ?: error("eeReleaseVersionCode must be a positive Android version code")
}
android {
    namespace = "com.hereliesaz.extrusionextraction"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hereliesaz.extrusionextraction"
        minSdk = 28
        targetSdk = 35
        versionCode = releaseCode ?: (parts[3] + 1)
        versionName = parts.joinToString(".")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
