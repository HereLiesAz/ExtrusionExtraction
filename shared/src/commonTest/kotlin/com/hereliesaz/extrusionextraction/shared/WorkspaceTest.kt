package com.hereliesaz.extrusionextraction.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class WorkspaceTest {
    @Test fun progressUsesMeasuredBytesAndUnknownTotals() {
        assertEquals(0.25f,TransferProgress("DA3",25,100).fraction)
        assertEquals(1f,TransferProgress("DA3",101,100).fraction)
        assertNull(TransferProgress("DA3",25,null).fraction)
        assertNull(TransferProgress("DA3",0,0).fraction)
    }

    @Test fun referenceMovesPreserveIdentityIncludingDuplicateNames() {
        val a=ViewImage("same",IntArray(IMAGE_SIZE*IMAGE_SIZE))
        val b=ViewImage("same",IntArray(IMAGE_SIZE*IMAGE_SIZE))
        val c=ViewImage("third",IntArray(IMAGE_SIZE*IMAGE_SIZE))
        val refs=listOf(a,b,c)
        assertEquals(listOf(a,c,b),moveReference(refs,b,1))
        assertEquals(listOf(c,a,b),moveReference(refs,c,-2))
        assertSame(refs,moveReference(refs,a,-1))
        assertSame(refs,moveReference(refs,ViewImage("same",IntArray(IMAGE_SIZE*IMAGE_SIZE)),1))
    }
}
