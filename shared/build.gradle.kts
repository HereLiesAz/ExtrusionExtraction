plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
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
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
