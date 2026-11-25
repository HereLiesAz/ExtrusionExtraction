import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}
val state = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
compose.desktop {
    application {
        mainClass = "com.hereliesaz.extrusionextraction.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ExtrusionExtraction"
            packageVersion = listOf("Major", "Minor", "Patch").joinToString(".") { state.getProperty("version" + it) }
            modules("java.desktop", "java.logging", "jdk.unsupported")
        }
    }
}
