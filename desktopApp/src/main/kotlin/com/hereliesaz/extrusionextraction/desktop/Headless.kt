package com.hereliesaz.extrusionextraction.desktop

import com.hereliesaz.extrusionextraction.shared.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking

/** Headless notebook entry point using the production reconstruction engine and session. */
fun main(args: Array<String>) = runBlocking {
    if(args.contentEquals(arrayOf("--check"))) {
        checkReconstructionRuntime()
        check(SkinTokensEngine.available()) { "SkinTokens native runtime unavailable." }
        println("EE_WORKER_READY")
        return@runBlocking
    }
    val fromJob=args.firstOrNull()=="--job"
    require(if(fromJob) args.size==4 else args.size in (5+VIEW_COUNT)..(5+MAX_REFERENCES)) {
        "Usage: --job <model-dir> <new-output-dir> <eejob-file> OR <model-dir> <new-output-dir> <cells> <mask|no-mask> <none|template|learned|both> <ordered-images...>"
    }
    val modelDir=File(args[if(fromJob)1 else 0]).absoluteFile
    val output=File(args[if(fromJob)2 else 1]).absoluteFile
    require(!output.exists()) { "Choose a fresh output directory." }
    val job=if(fromJob) {
        val file=File(args[3])
        require(file.isFile && file.length() in 16L..MAX_COLAB_JOB_BYTES.toLong()) { "Invalid job file size." }
        // Bound the read even if an external writer changes the file after the size check.
        val bytes=file.inputStream().use{it.readNBytes(MAX_COLAB_JOB_BYTES+1)}
        decodeColabJob(bytes)
    } else {
        val cells=args[2].toInt()
        require(cells in 32..96) { "Mesh detail must be 32–96 cells per axis." }
        val mask=when(args[3]) {
            "mask" -> true
            "no-mask" -> false
            else -> error("Specify mask or no-mask.")
        }
        val rig=args[4]
        require(rig in listOf("none","template","learned","both")) { "Unknown rig selection." }
        val refs=args.drop(5).map{File(it).canonicalFile}
        require(refs.distinct().size==refs.size && refs.all{it.isFile}) { "Provide distinct, ordered reference files." }
        ColabJob(refs.map(::decodeReference),cells,mask,rig=="template" || rig=="both",rig=="learned" || rig=="both")
    }
    val cells=job.cells
    val mask=job.mask
    val images=job.images
    val modes=buildList {
        if(job.template) add(RigMode.TEMPLATE)
        if(job.learned) add(RigMode.LEARNED)
    }
    val model = File(modelDir, "da3.onnx")
    fun report(message: String) { println(message) }
    fun transfer(progress:TransferProgress) {
        report(progress.label+": "+progress.completed+" bytes"+(progress.total?.let{" / "+it} ?: ""))
    }
    if (model.isFile) {
        report("Verifying DA3")
        installModel(model.inputStream(), model)
    } else downloadModel(model, ::report, ::transfer)
    if (mask && !images.all(::hasForegroundMask)) {
        val foreground = segmentationFile(model)
        if (foreground.isFile) installSegmentation(foreground.inputStream(), foreground)
        else downloadSegmentation(foreground, ::transfer)
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
                SkinTokensEngine.download(model, ::transfer)
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
