package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class ReferenceFramingTest {
    @Test fun portraitCropKeepsProportionsAndOriginalAlpha() {
        val pixels=IntArray(58*187){0xffd08040.toInt()}
        pixels[0]=0
        val image=fitReferenceImage("portrait",58,187,pixels)
        assertEquals(ReferenceBounds(181,24,322,480),image.sourceBounds)
        assertTrue(image.sourceMask)
        assertTrue(image.croppedForeground)
        assertFalse(image.observed(0))
        assertEquals(0,image.argb[0])
        assertEquals(0xffd08040.toInt(),image.argb[252*IMAGE_SIZE+252])
        assertEquals(0,image.argb[24*IMAGE_SIZE+181])
    }
    @Test fun landscapePaddingDoesNotPretendToBeAProvidedSilhouette() {
        val image=fitReferenceImage("scene",4,2,IntArray(8){-1})
        assertEquals(ReferenceBounds(24,138,480,366),image.sourceBounds)
        assertFalse(hasForegroundMask(image))
        assertFalse(image.croppedForeground)
        assertFalse(image.observed(137*IMAGE_SIZE+252))
        assertTrue(image.observed(138*IMAGE_SIZE+252))
    }
    @Test fun interpolationDoesNotPullRgbFromFullyTransparentPixels() {
        val image=fitReferenceImage("edge",2,2,intArrayOf(0xffff0000.toInt(),0x000000ff,0xffff0000.toInt(),0x000000ff))
        val edge=image.argb[252*IMAGE_SIZE+252]
        assertTrue(edge.ushr(24) in 1..254)
        assertEquals(0x00ff0000,edge and 0x00ffffff)
    }
}
