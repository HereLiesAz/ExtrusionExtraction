package com.hereliesaz.extrusionextraction.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertContentEquals

class BrowserResultTest {
    @Test fun boundedReaderRejectsTruncationAndKeepsSlicesIndependent() {
        val input=ArrayPacketReader(byteArrayOf(0,0,0,7,0,0,0,9))
        val slice=input.slice(4)
        assertEquals(7,slice.readInt())
        assertFails{slice.readInt()}
        assertEquals(9,input.readInt())
        assertEquals(0L,input.remaining)
    }
    @Test fun malformedEnvelopeDoesNotReachModelDecoder() {
        val bytes=byteArrayOf(69,69,82,49,0,0,0,1,0,0,0,0,127,0,0,0,0,0,0,0)
        assertFails {readColabResultBytes(bytes){error("Decoder must not run")}}
    }
    @Test fun modifiedUtfAsciiJointNameDecodes() {
        val input=ArrayPacketReader(byteArrayOf(0,4,114,111,111,116))
        assertEquals("root",input.readUTF())
        assertEquals(0L,input.remaining)
    }
}
