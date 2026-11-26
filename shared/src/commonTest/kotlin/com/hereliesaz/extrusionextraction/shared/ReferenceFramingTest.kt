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
    @Test fun onlySmallDisconnectedEdgeFragmentsAreOfferedForRemoval() {
        val pixels=IntArray(IMAGE_SIZE*IMAGE_SIZE)
        for(y in 100..399) for(x in 100..399) pixels[y*IMAGE_SIZE+x]=-1
        for(y in 30..39) for(x in 0..9) pixels[y*IMAGE_SIZE+x]=-1
        for(y in 20..29) for(x in 20..29) pixels[y*IMAGE_SIZE+x]=-1
        for(y in 200..204) for(x in 399 until IMAGE_SIZE) pixels[y*IMAGE_SIZE+x]=-1
        val image=ViewImage("fragment fixture",pixels)
        val fragments=findEdgeFragments(image)
        assertEquals(1,fragments.count)
        assertEquals(100,fragments.indices.size)
        val cleaned=removeEdgeFragments(image,fragments)
        assertEquals(-1,image.argb[30*IMAGE_SIZE])
        assertEquals(0,cleaned.argb[30*IMAGE_SIZE])
        assertEquals(-1,cleaned.argb[20*IMAGE_SIZE+20])
        assertEquals(-1,cleaned.argb[200*IMAGE_SIZE+503])
        assertEquals(0,findEdgeFragments(cleaned).count)
    }
    @Test fun interpolationDoesNotPullRgbFromFullyTransparentPixels() {
        val image=fitReferenceImage("edge",2,2,intArrayOf(0xffff0000.toInt(),0x000000ff,0xffff0000.toInt(),0x000000ff))
        val edge=image.argb[252*IMAGE_SIZE+252]
        assertTrue(edge.ushr(24) in 1..254)
        assertEquals(0x00ff0000,edge and 0x00ffffff)
    }
}
