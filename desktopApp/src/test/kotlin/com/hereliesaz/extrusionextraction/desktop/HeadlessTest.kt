package com.hereliesaz.extrusionextraction.desktop

import com.hereliesaz.extrusionextraction.shared.RigMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tests independent variant orchestration without requiring neural models. */
class HeadlessTest {
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
