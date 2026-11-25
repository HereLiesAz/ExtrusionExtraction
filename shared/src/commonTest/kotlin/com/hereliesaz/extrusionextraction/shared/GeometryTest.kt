package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class GeometryTest {
    @Test fun calibratedProjection() {
        val pixels=IMAGE_SIZE*IMAGE_SIZE
        val view=CameraView(ViewImage("front",IntArray(pixels){-1}),FloatArray(pixels){2f},FloatArray(pixels){1f},
            floatArrayOf(100f,0f,252f,0f,100f,252f,0f,0f,1f),
            floatArrayOf(1f,0f,0f,1f,0f,1f,0f,2f,0f,0f,1f,3f))
        assertEquals(Vec3(-1f,-2f,-1f),view.world(252,252,2f))
        assertEquals(Vec3(0f,0f,2f),view.camera(Vec3(-1f,-2f,-1f)))
        assertEquals(252*IMAGE_SIZE+302,view.pixel(Vec3(1f,0f,2f)))
    }
    @Test fun normalizedSkin() {
        val mesh=Mesh(listOf(Vec3(-.5f,0f,0f),Vec3(.5f,0f,0f),Vec3(0f,1f,0f)),
            List(3){Vec3(1f,1f,1f)},intArrayOf(0,1,2))
        val rig=humanoidRig(mesh)
        assertEquals(19,rig.joints.size)
        assertEquals(-1,rig.joints.first().parent)
        assertTrue(rig.joints.drop(1).withIndex().all { (i,j)->j.parent in 0..i })
        for(i in 0..2) assertEquals(1f,rig.weights.sliceArray(i*4 until i*4+4).sum(),.00001f)
        assertTrue(rig.indices.all { it in 0..18 })
    }
    @Test fun emptyDepthRejected() {
        val count=IMAGE_SIZE*IMAGE_SIZE
        val view=CameraView(ViewImage("empty",IntArray(count)),FloatArray(count),FloatArray(count),
            floatArrayOf(100f,0f,252f,0f,100f,252f,0f,0f,1f),
            floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f))
        assertFailsWith<IllegalArgumentException> { fuse(List(4){view},32) }
    }
}
