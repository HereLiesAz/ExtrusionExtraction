package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class ForegroundTest {
    @Test fun solidRedHasExpectedNchwChannels() {
        val image=ViewImage("red",IntArray(IMAGE_SIZE*IMAGE_SIZE){0xffff0000.toInt()})
        val tensor=segmentationInput(image)
        assertEquals(307200,tensor.size)
        assertEquals(2.2489f,tensor[0],.0001f)
        assertEquals(-2.0357f,tensor[102400],.0001f)
        assertEquals(-1.8044f,tensor[204800],.0001f)
    }
    @Test fun alphaIsPreservedAndRgbUnchanged() {
        val rgb=0xff123456.toInt()
        val image=ViewImage("subject",IntArray(IMAGE_SIZE*IMAGE_SIZE){rgb})
        val prediction=FloatArray(SEGMENT_SIZE*SEGMENT_SIZE){if(it%SEGMENT_SIZE<160) .1f else .9f}
        val result=applyForeground(image,prediction)
        assertEquals(0x00123456,result.argb[0])
        assertEquals(rgb,result.argb[IMAGE_SIZE-1])
        assertEquals(rgb,image.argb[0])
        assertSame(result,applyForeground(result,FloatArray(0)))
    }
    @Test fun invalidAndFlatPredictionRejected() {
        val image=ViewImage("empty",IntArray(IMAGE_SIZE*IMAGE_SIZE){-1})
        assertFailsWith<IllegalArgumentException>{applyForeground(image,FloatArray(102400){.5f})}
        assertFailsWith<IllegalArgumentException>{applyForeground(image,FloatArray(102400){Float.NaN})}
        assertFailsWith<IllegalArgumentException>{applyForeground(image,FloatArray(0))}
    }
}
