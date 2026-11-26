package com.hereliesaz.extrusionextraction.desktop

import com.hereliesaz.extrusionextraction.shared.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking

/** Headless notebook entry point using the production reconstruction engine and session. */
fun main(args: Array<String>) = runBlocking {
    require(args.size in (5+VIEW_COUNT)..(5+MAX_REFERENCES)) {
        "Usage: <model-dir> <new-output-dir> <cells> <mask|no-mask> <none|template|learned|both> <ordered-images...>"
    }
    val modelDir = File(args[0]).absoluteFile
    val output = File(args[1]).absoluteFile
    val cells = args[2].toInt()
    require(cells in 32..96) { "Mesh detail must be 32–96 cells per axis." }
    val mask = when (args[3]) {
        "mask" -> true
        "no-mask" -> false
        else -> error("Specify mask or no-mask.")
    }
    val modes = when (args[4]) {
        "none" -> emptyList()
        "template" -> listOf(RigMode.TEMPLATE)
        "learned" -> listOf(RigMode.LEARNED)
        "both" -> listOf(RigMode.TEMPLATE, RigMode.LEARNED)
        else -> error("Unknown rig selection.")
    }
    val refs = args.drop(5).map { File(it).canonicalFile }
    require(refs.size in VIEW_COUNT..MAX_REFERENCES && refs.distinct().size == refs.size && refs.all { it.isFile }) {
        "Provide distinct, ordered reference files."
    }
    require(!output.exists()) { "Choose a fresh output directory." }
    val images = refs.map(::decodeReference)
    val model = File(modelDir, "da3.onnx")
    fun report(message: String) { println(message) }
    if (model.isFile) installModel(model.inputStream(), model) else downloadModel(model, ::report)
    if (mask && !images.all(::hasForegroundMask)) {
        val foreground = segmentationFile(model)
        if (foreground.isFile) installSegmentation(foreground.inputStream(), foreground)
        else downloadSegmentation(foreground)
    }
    check(output.mkdirs()) { "Cannot create output directory." }
    /** Publish only complete GLBs; preserve previous exports if a later variant fails. */
    fun publish(name: String, bytes: ByteArray) {
        val temporary = Files.createTempFile(output.toPath(), "model-", ".part")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, File(output, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
            report("Exported $name")
        } finally { Files.deleteIfExists(temporary) }
    }
    val session = generateLocal(model, images, cells, mask, ::report)
    publish("unrigged.glb", session.base.glb)
    val failures = runRigVariants(modes) { mode ->
        if (mode == RigMode.LEARNED) {
            check(SkinTokensEngine.available()) { "SkinTokens native runtime unavailable." }
            if (!SkinTokensEngine.installed(model)) {
                report("Acquiring verified SkinTokens models")
                SkinTokensEngine.download(model)
            }
        }
        val result = session.createVariant(mode, ::report)
        publish(if (mode == RigMode.TEMPLATE) "humanoid.glb" else "skintokens.glb", result.glb)
    }
    check(failures.isEmpty()) { failures.joinToString("; ") }
}

/** Independent variants preserve completed exports. Cancellation is never treated as an ordinary failure. */
internal suspend fun runRigVariants(modes: List<RigMode>, operation: suspend (RigMode) -> Unit): List<String> {
    val failures = mutableListOf<String>()
    for (mode in modes) {
        try { operation(mode) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val message = "$mode: ${e.message ?: e.javaClass.simpleName}"
            failures.add(message)
            System.err.println(message)
        }
    }
    return failures
}
