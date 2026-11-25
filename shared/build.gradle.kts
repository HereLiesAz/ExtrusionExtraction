plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
val skinTokensSource = providers.gradleProperty("skinTokensSource").orNull
val skinTokensJsonDir = providers.gradleProperty("skinTokensJsonDir").orNull
kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            implementation(compose.material3)
            api(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val androidMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies { implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0") }
        }
        val desktopMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies { implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0") }
        }
    }
}
android {
    namespace = "com.hereliesaz.extrusionextraction.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        if (skinTokensSource != null) {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DSKINTOKENS_SOURCE_DIR=$skinTokensSource", "-DANDROID_STL=c++_shared")
                    if (skinTokensJsonDir != null) arguments += "-Dnlohmann_json_DIR=$skinTokensJsonDir"
                    targets += listOf("ee_skintokens", "skintokens")
                }
            }
        }
    }
    if (skinTokensSource != null) {
        ndkVersion = "28.2.13676358"
        externalNativeBuild { cmake { path = rootProject.file("native/skintokens/CMakeLists.txt"); version = "3.31.6" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/** Native validation is opt-in via the same staging property used by packaging.
 * The Linux packaging lane already calls desktopTest with this property.
 */
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val nativeStage = providers.gradleProperty("skinTokensNativeDir").orNull
    if (name == "desktopTest" && nativeStage != null) {
        systemProperty("ee.skintokens.smoke.native", file(nativeStage).resolve("common").absolutePath)
        systemProperty("ee.skintokens.smoke.reports", layout.buildDirectory.dir("reports/skintokens").get().asFile.absolutePath)
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    }
}
