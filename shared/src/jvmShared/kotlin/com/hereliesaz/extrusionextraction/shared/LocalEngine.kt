package com.hereliesaz.extrusionextraction.shared

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copy a locally selected ONNX graph without loading hundreds of MB into JVM memory. */
fun installModel(input: InputStream, destination: File) {
    destination.parentFile?.mkdirs()
    val temporary=File.createTempFile("model-", ".part",destination.parentFile)
    try {
        input.use { source -> temporary.outputStream().use { output ->
            val buffer=ByteArray(65536)
            var total=0L
            while(true) {
                val count=source.read(buffer);if(count<0) break
                total+=count;require(total<=600_000_000) { "ONNX graph exceeds 600 MB." }
                output.write(buffer,0,count)
            }
            require(total>0) { "Empty model." }
        } }
        // Validate the IO contract before replacing an existing model.
        val env=OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            env.createSession(temporary.absolutePath, options).use { session ->
                require(session.inputNames==setOf("images") &&
                    session.outputNames.containsAll(setOf("depth","depth_conf","intrinsics","extrinsics"))) {
                    "Select the DA3-Base four-view ONNX graph."
                }
            }
        }
        java.nio.file.Files.move(temporary.toPath(),destination.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}

/** Both platforms run the same CPU ONNX session, preprocessing, fusion and skin export.
 * Four views are inferred jointly; no network requests or external worker exist.
 */
suspend fun generateLocal(model:File, images:List<ViewImage>, rig:Boolean, resolution:Int,
                          progress:(String)->Unit):ByteArray = withContext(Dispatchers.Default) {
    require(model.isFile) { "Import the DA3 four-view model first." }
    require(images.size==VIEW_COUNT) { "Exactly four overlapping views are required." }
    val context=currentCoroutineContext()
    progress("Estimating depth and cameras locally")
    val pixels=IMAGE_SIZE*IMAGE_SIZE
    val input=FloatArray(VIEW_COUNT*3*pixels)
    val mean=floatArrayOf(.485f,.456f,.406f); val std=floatArrayOf(.229f,.224f,.225f)
    for((v,image) in images.withIndex()) for(i in 0 until pixels) {
        val argb=image.argb[i]
        val alpha=(argb ushr 24)/255f
        for(c in 0..2) {
            val color=(argb ushr (16-c*8) and 255)/255f
            input[(v*3+c)*pixels+i]=(color*alpha+1f-alpha-mean[c])/std[c]
        }
    }
    val env=OrtEnvironment.getEnvironment()
    val views=OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(minOf(4,Runtime.getRuntime().availableProcessors()))
        env.createSession(model.absolutePath,options).use { session ->
            OnnxTensor.createTensor(env,FloatBuffer.wrap(input),longArrayOf(1,4,3,504,504)).use { tensor ->
                session.run(mapOf("images" to tensor)).use { result ->
                    fun read(name:String, count:Int):FloatArray {
                        val value=result.get(name).orElseThrow { IllegalArgumentException("Missing output: "+name) } as OnnxTensor
                        val buffer=value.floatBuffer
                        require(buffer.remaining()==count) { "Unexpected output shape: "+name }
                        return FloatArray(count).also { buffer.get(it) }
                    }
                    val depth=read("depth",4*pixels);val confidence=read("depth_conf",4*pixels)
                    val k=read("intrinsics",36);val extrinsic=read("extrinsics",48)
                    List(4) { i -> CameraView(images[i],depth.copyOfRange(i*pixels,(i+1)*pixels),
                        confidence.copyOfRange(i*pixels,(i+1)*pixels),k.copyOfRange(i*9,(i+1)*9),
                        extrinsic.copyOfRange(i*12,(i+1)*12)) }
                }
            }
        }
    }
    context.ensureActive()
    progress("Fusing calibrated depth")
    val mesh=fuse(views,resolution) { context.ensureActive() }
    progress("Writing GLB")
    writeGlb(mesh,if(rig) humanoidRig(mesh) else null)
}
