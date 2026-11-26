// Standalone JVM build: no Android SDK, Compose plugin or desktop window dependencies.
plugins {
    kotlin("jvm") version "2.1.21"
    application
}
kotlin {
    jvmToolchain(17)
    sourceSets {
        val main by getting {
            kotlin.srcDirs("../shared/src/commonMain/kotlin", "../shared/src/jvmShared/kotlin", "../desktopApp/src/main/kotlin")
            kotlin.exclude("**/App.kt", "**/Main.kt", "**/GoogleColabTransfer.kt", "**/DesktopColabAuth.kt")
        }
        val test by getting {
            kotlin.srcDirs("../shared/src/commonTest/kotlin", "../shared/src/desktopTest/kotlin", "../desktopApp/src/test/kotlin")
            kotlin.include("**/ColabJobTest.kt", "**/HeadlessTest.kt", "**/ColabResultTest.kt")
        }
    }
}
// Resolve the pinned CUDA artifact separately; never put both ONNX JNI jars on one classpath.
val colabCudaRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    colabCudaRuntime("com.microsoft.onnxruntime:onnxruntime_gpu:1.22.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    testImplementation(kotlin("test"))
}
application {
    mainClass.set("com.hereliesaz.extrusionextraction.desktop.HeadlessKt")
}
tasks.named<JavaExec>("run") {
    systemProperty("java.awt.headless", "true")
    providers.gradleProperty("skinTokensNativeDir").orNull?.let {
        systemProperty("compose.application.resources.dir", file(it).resolve("common").absolutePath)
    }
}

/** Optional precompiled CUDA overlay; ordinary headless/local builds still use CPU ORT. */
tasks.register<Copy>("stageColabCuda") {
    from(colabCudaRuntime)
    into(layout.buildDirectory.dir("colab-cuda"))
}
