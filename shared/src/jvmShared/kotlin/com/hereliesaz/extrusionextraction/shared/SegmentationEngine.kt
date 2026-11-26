package com.hereliesaz.extrusionextraction.shared

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Apache-2.0 U²-Net portable graph, pinned to a byte-identical rembg release mirror. */
const val SEGMENT_URL="https://huggingface.co/edgetools/u2netp/resolve/25dee37ab19c5b6ad64ba6578eba63f1ae07720c/u2netp.onnx"
const val SEGMENT_SHA256="309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8"

/** Store each independently replaceable neural stage next to the DA3 model. */
fun segmentationFile(depthModel:File)=File(depthModel.parentFile,"u2netp.onnx")

/** Verify graph bytes and required tensor IO before installing the foreground model. */
fun installSegmentation(input:InputStream,destination:File,transfer:(Long)->Unit={}) =
    installVerifiedGraph(input,destination,SEGMENT_SHA256,4_574_861L,transfer) { session ->
        require(session.inputNames.size==1 && session.outputNames.isNotEmpty()) {
            "Invalid U²-Net foreground model."
        }
    }

/** Explicit one-time download; generation never downloads models. */
fun downloadSegmentation(destination:File,transfer:(TransferProgress)->Unit={}) =
    downloadVerifiedGraph(SEGMENT_URL) { input ->
        installSegmentation(input,destination) { bytes -> transfer(TransferProgress("Downloading U²-Net",bytes,4_574_861L)) }
    }

/** Segment only references lacking user-supplied alpha, using one local session.
 * Releases the segmentation session before DA3 starts to reduce peak RAM.
 */
suspend fun segmentForeground(model:File,images:List<ViewImage>,progress:(String)->Unit):List<ViewImage> {
    if(images.all(::hasForegroundMask)) return images
    require(model.isFile) { "Download the foreground model, or disable automatic masking." }
    val context=currentCoroutineContext()
    val env=OrtEnvironment.getEnvironment()
    return OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(minOf(4,Runtime.getRuntime().availableProcessors()))
        env.createSession(model.absolutePath,options).use { session ->
            val name=session.inputNames.single()
            var completed=0
            val required=images.count { !hasForegroundMask(it) }
            images.map { image ->
                context.ensureActive()
                if(hasForegroundMask(image)) image else {
                    progress("Isolating foreground: ${completed+1}/$required")
                    val input=segmentationInput(image)
                    OnnxTensor.createTensor(env,FloatBuffer.wrap(input),
                        longArrayOf(1,3,SEGMENT_SIZE.toLong(),SEGMENT_SIZE.toLong())).use { tensor ->
                        session.run(mapOf(name to tensor)).use { result ->
                            // Graph output zero is the fused saliency map, not a side output.
                            val output=result.get(0) as OnnxTensor
                            val buffer=output.floatBuffer
                            require(buffer.remaining()==SEGMENT_SIZE*SEGMENT_SIZE) { "Unexpected saliency shape." }
                            val saliency=FloatArray(buffer.remaining()).also { buffer.get(it) }
                            applyForeground(image,saliency).also {
                                completed++
                                progress("Foreground: $completed/$required images complete")
                            }
                        }
                    }
                }
            }
        }
    }
}
