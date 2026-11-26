package com.hereliesaz.extrusionextraction.shared

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Independent model-stage devices. CPU is the unchanged local and headless default.
 * CUDA applies only to ONNX inference, not TSDF, simplification, texture baking or export.
 */
enum class OnnxDevice { CPU, CUDA }
enum class LearnedRigDevice { CPU, VULKAN }
data class ReconstructionAcceleration(val onnx:OnnxDevice=OnnxDevice.CPU,
                                      val learnedRig:LearnedRigDevice=LearnedRigDevice.CPU)

/** Strict selection: invalid values never silently become CPU. */
fun parseReconstructionAcceleration(onnx:String?,learnedRig:String?):ReconstructionAcceleration =
    ReconstructionAcceleration(
        when(onnx) { null,"cpu"->OnnxDevice.CPU;"cuda"->OnnxDevice.CUDA;else->error("Expected ONNX device cpu or cuda.") },
        when(learnedRig) { null,"cpu"->LearnedRigDevice.CPU;"vulkan"->LearnedRigDevice.VULKAN;else->error("Expected SkinTokens device cpu or vulkan.") })

/** CPU thread budget remains identical. CUDA registration failure is propagated;
 * ONNX Runtime may place individual unsupported operators on CPU in production graphs.
 */
internal fun OrtSession.SessionOptions.configureReconstruction(device:OnnxDevice) {
    setIntraOpNumThreads(minOf(4,Runtime.getRuntime().availableProcessors()))
    if(device==OnnxDevice.CUDA) addCUDA(0)
}

/** Real one-element ONNX Add graph (IR 9, opset 13). A CUDA probe disables CPU EP fallback
 * for this tiny graph, ensuring successful registration alone is not mistaken for execution.
 * Actual DA3/U²-Net throughput and parity require model-backed benchmarking.
 */
fun checkOnnxExecution(device:OnnxDevice) {
    val graph=byteArrayOf(8,9,58,82,10,14,10,1,88,10,1,89,18,1,90,34,3,65,100,100,18,13,101,101,95,99,117,100,97,95,112,114,111,98,101,90,15,10,1,88,18,10,10,8,8,1,18,4,10,2,8,1,90,15,10,1,89,18,10,10,8,8,1,18,4,10,2,8,1,98,15,10,1,90,18,10,10,8,8,1,18,4,10,2,8,1,66,2,16,13)
    val env=OrtEnvironment.getEnvironment()
    OrtSession.SessionOptions().use { options->
        options.configureReconstruction(device)
        if(device==OnnxDevice.CUDA) options.addConfigEntry("session.disable_cpu_ep_fallback","1")
        env.createSession(graph,options).use { session->
            OnnxTensor.createTensor(env,FloatBuffer.wrap(floatArrayOf(2f)),longArrayOf(1)).use { x->
                OnnxTensor.createTensor(env,FloatBuffer.wrap(floatArrayOf(3f)),longArrayOf(1)).use { y->
                    session.run(mapOf("X" to x,"Y" to y)).use { result->
                        val output=(result.get(0) as OnnxTensor).floatBuffer
                        check(output.remaining()==1 && output.get()==5f) { "ONNX device probe returned an unexpected result." }
                    }
                }
            }
        }
    }
}
