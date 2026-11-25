package com.hereliesaz.extrusionextraction.shared

import kotlin.math.ln
import kotlin.test.*

class ExposureTest {
    @Test fun achromaticExposureAndRejectedSamples() {
        assertEquals(ln(2f),assertNotNull(exposureRatio(Vec3(.4f,.2f,.1f),Vec3(.2f,.1f,.05f))),.00001f)
        assertNull(exposureRatio(Vec3(.4f,.2f,.1f),Vec3(.2f,.2f,.1f)))
        assertNull(exposureRatio(Vec3(.99f,.2f,.1f),Vec3(.2f,.1f,.05f)))
        assertNull(exposureRatio(Vec3(.01f,.2f,.1f),Vec3(.2f,.1f,.05f)))
    }
    @Test fun rejectsOutliersAndInsufficientOverlap() {
        val robust=assertNotNull(summarizeExposure(0,1,List(20){ln(1.5f)}+List(5){-3f}))
        assertEquals(20,robust.weight)
        assertEquals(ln(1.5f),robust.logRatio,.00001f)
        assertNull(summarizeExposure(0,1,List(11){0f}))
        assertNull(summarizeExposure(0,1,List(12){-1f}+List(12){1f}))
    }
    @Test fun connectedAndDisconnectedViewsWithBounds() {
        val gains=exposureGains(5,listOf(ExposureOverlap(0,1,ln(1.5f),20),ExposureOverlap(1,2,ln(.5f),20),
            ExposureOverlap(0,2,ln(.75f),20),ExposureOverlap(3,4,ln(4f),20)))
        listOf(1f,1.5f,.75f,1f,2f).forEachIndexed{i,v->assertEquals(v,gains[i],.00001f)}
        assertContentEquals(floatArrayOf(1f,1f,1f),exposureGains(3,emptyList()))
    }
    @Test fun conflictingCycleUsesLeastSquares() {
        val gains=exposureGains(3,listOf(ExposureOverlap(0,1,0f,1),ExposureOverlap(0,2,0f,1),ExposureOverlap(1,2,ln(2f),1)))
        assertEquals(.7937005f,gains[1],.00001f)
        assertEquals(1.2599211f,gains[2],.00001f)
    }
}
