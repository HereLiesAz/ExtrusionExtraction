package com.hereliesaz.extrusionextraction.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** Independent byte-level fixtures establish ordering and strict decoding. */
class ColabJobTest {
    private val refs=List(4){i->ViewImage("duplicate",IntArray(504*504){if(it==0) 0x12345678+i else 0})}

    @Test fun wireHeaderAndPixelsAreStable() {
        val bytes=encodeColabJob(ColabJob(refs,72,true,false,true))
        assertEquals(4064272,bytes.size)
        assertContentEquals(byteArrayOf(69,69,74,49,0,0,0,72,0,0,0,5,0,0,0,4,18,52,86,120),bytes.copyOfRange(0,20))
        assertContentEquals(byteArrayOf(18,52,86,121),bytes.copyOfRange(1016080,1016084))
        val decoded=decodeColabJob(bytes)
        assertEquals(72,decoded.cells)
        assertEquals(true,decoded.mask)
        assertEquals(false,decoded.template)
        assertEquals(true,decoded.learned)
        assertEquals(listOf(0x12345678,0x12345679,0x1234567a,0x1234567b),decoded.images.map{it.argb[0]})
    }

    @Test fun malformedJobsAreRejected() {
        val bytes=encodeColabJob(ColabJob(refs,32,false,true,false))
        assertFailsWith<IllegalArgumentException>{decodeColabJob(bytes.copyOf(bytes.size-1))}
        assertFailsWith<IllegalArgumentException>{decodeColabJob(bytes.copyOf(bytes.size+1))}
        for((offset,value) in listOf(3 to 50,7 to 31,11 to 8,15 to 25)){
            val corrupt=bytes.copyOf().apply{this[offset]=value.toByte()}
            assertFailsWith<IllegalArgumentException>{decodeColabJob(corrupt)}
        }
    }
}
