package com.hereliesaz.extrusionextraction.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
