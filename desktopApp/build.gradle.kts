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
    testImplementation(kotlin("test"))
}
val state = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
// The product version remains canonical. macOS requires a positive installer major.
val productVersionParts = listOf("Major", "Minor", "Patch").map { state.getProperty("version" + it).toInt() }
val productVersion = productVersionParts.joinToString(".")
val macInstallerVersion = listOf(Math.addExact(productVersionParts[0], 1), productVersionParts[1], productVersionParts[2]).joinToString(".")
version = productVersion
compose.desktop {
    application {
        mainClass = "com.hereliesaz.extrusionextraction.desktop.MainKt"
        nativeDistributions {
            providers.gradleProperty("skinTokensNativeDir").orNull?.let { appResourcesRootDir.set(file(it)) }
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ExtrusionExtraction"
            packageVersion = productVersion
            macOS {
                packageVersion = macInstallerVersion
                iconFile.set(project.file("icons/icon.icns"))
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                // jpackage does not create a Start menu entry unless explicitly requested.
                menu = true
                menuGroup = "ExtrusionExtraction"
            }
            linux { iconFile.set(project.file("icons/icon.png")) }
            modules("java.desktop", "java.logging", "jdk.unsupported")
        }
    }
}
