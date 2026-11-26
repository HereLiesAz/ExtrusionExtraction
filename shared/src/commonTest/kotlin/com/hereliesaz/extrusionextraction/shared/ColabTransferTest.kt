package com.hereliesaz.extrusionextraction.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColabTransferTest {
    @Test fun pairingPathIsExactlyNotebookDirectory() {
        assertEquals("content/ee-transfer-0123456789abcdef0123456789abcdef",
            colabPairPath("0123456789abcdef0123456789abcdef"))
    }

    @Test fun malformedPairingCodesCannotSelectArbitraryPaths() {
        for(code in listOf("", "../content", "0123456789ABCDEF0123456789ABCDEF",
            "0123456789abcdef0123456789abcdef/","0".repeat(31),"0".repeat(33))) {
            assertFailsWith<IllegalArgumentException>{colabPairPath(code)}
        }
    }
    @Test fun observedFilesReconcileReadyWithoutInventingCompletion() {
        val submitted=observedColabSnapshot(ColabJobState.READY,"2026-01-01T00:00:00Z","Waiting",listOf("job-a.eejob"))
        assertEquals(ColabJobState.QUEUED,submitted.state)
        assertFalse(submitted.canSubmit)
        assertTrue(submitted.canCancel)
        val ambiguous=observedColabSnapshot(ColabJobState.READY,"time","Waiting",listOf("job-a.eejob","job-b.eejob"))
        assertEquals(ColabJobState.AMBIGUOUS,ambiguous.state)
        assertFalse(ambiguous.canSubmit)
        assertFalse(ambiguous.canCancel)
    }

    @Test fun cancelRequestIsNotConfirmedAndPartialResultsRemainAvailable() {
        val running=observedColabSnapshot(ColabJobState.RUNNING,"time","stage",listOf("cancel.txt","reconstruction.eeresult"))
        assertEquals(ColabJobState.RUNNING,running.state)
        assertTrue(running.cancelRequested)
        assertTrue(running.resultAvailable)
        assertFalse(running.canCancel)
        val terminal=observedColabSnapshot(ColabJobState.CANCELLED,"time","done",listOf("claimed.eejob","reconstruction.eeresult"))
        assertEquals(ColabJobState.CANCELLED,terminal.state)
        assertTrue(terminal.resultAvailable)
        assertFalse(terminal.canSubmit)
    }
}
