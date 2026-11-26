package com.hereliesaz.extrusionextraction.desktop

import com.hereliesaz.extrusionextraction.shared.RigMode
import com.hereliesaz.extrusionextraction.shared.OnnxDevice
import com.hereliesaz.extrusionextraction.shared.LearnedRigDevice
import com.hereliesaz.extrusionextraction.shared.parseReconstructionAcceleration
import com.hereliesaz.extrusionextraction.shared.checkOnnxExecution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tests independent variant orchestration without requiring neural models. */
class HeadlessTest {
    @Test fun deviceSelectionIsExplicitAndIndependent() {
        val defaults=parseReconstructionAcceleration(null,null)
        assertEquals(OnnxDevice.CPU,defaults.onnx)
        assertEquals(LearnedRigDevice.CPU,defaults.learnedRig)
        val gpu=parseReconstructionAcceleration("cuda","vulkan")
        assertEquals(OnnxDevice.CUDA,gpu.onnx)
        assertEquals(LearnedRigDevice.VULKAN,gpu.learnedRig)
        assertEquals(LearnedRigDevice.CPU,parseReconstructionAcceleration("cuda","cpu").learnedRig)
        assertFailsWith<IllegalStateException>{parseReconstructionAcceleration("auto","cpu")}
        assertFailsWith<IllegalStateException>{parseReconstructionAcceleration("cpu","cuda")}
    }

    @Test fun cpuProbeExecutesRealOnnxGraph() {
        checkOnnxExecution(OnnxDevice.CPU)
    }

    @Test fun failedVariantDoesNotPreventAnother() = runBlocking {
        val completed = mutableListOf<RigMode>()
        val failures = runRigVariants(listOf(RigMode.LEARNED, RigMode.TEMPLATE)) {
            if (it == RigMode.LEARNED) error("fixture failure")
            completed.add(it)
        }
        assertEquals(listOf(RigMode.TEMPLATE), completed)
        assertEquals(listOf("LEARNED: fixture failure"), failures)
    }

    @Test fun cancellationDoesNotStartLaterVariant() = runBlocking {
        val attempted = mutableListOf<RigMode>()
        assertFailsWith<CancellationException> {
            runRigVariants(listOf(RigMode.LEARNED, RigMode.TEMPLATE)) {
                attempted.add(it)
                throw CancellationException("fixture cancellation")
            }
        }
        assertEquals(listOf(RigMode.LEARNED), attempted)
    }
}
