plugins { kotlin("multiplatform") version "2.1.21" }
kotlin {
    js(IR) {
        browser {
            commonWebpackConfig { outputFileName = "webApp.js" }
        }
        binaries.executable()
    }
    sourceSets {
        val jsMain by getting {
            kotlin.srcDir("../shared/src/commonMain/kotlin")
            kotlin.exclude("**/App.kt")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation("org.jetbrains.kotlin:kotlin-dom-api-compat:2.1.21")
            }
        }
        val jsTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}
