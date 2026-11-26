package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class ModelPreviewTest {
    @Test fun textureWireAndSkeletonAreIndependentLayers() {
        val mesh=Mesh(listOf(Vec3(-1f,-1f,0f),Vec3(1f,-1f,0f),Vec3(0f,1f,0f)),
            List(3){Vec3(0f,0f,1f)},intArrayOf(0,1,2))
        val atlas=TextureAtlas(floatArrayOf(0f,0f,1f,0f,.5f,1f),byteArrayOf(),2)
        val rig=Rig(listOf(Joint("root",-1,Vec3(0f,0f,0f))),IntArray(12),FloatArray(12))
        val model=GeneratedModel(mesh,rig,atlas,byteArrayOf())
        val red=0xffff0000.toInt()
        val texture=IntArray(4){red}
        fun render(t:Boolean,w:Boolean,s:Boolean)=renderModelPreview(model,texture,0f,0f,t,w,s).argb
        val center=(IMAGE_SIZE/2)*IMAGE_SIZE+IMAGE_SIZE/2
        assertEquals(red,render(true,false,false)[center])
        assertEquals(0xffa3a9af.toInt(),render(false,false,false)[center])
        assertEquals(0xffffb74d.toInt(),render(true,false,true)[center])
        val combined=render(true,true,true)
        assertTrue(combined.contains(red))
        assertTrue(combined.contains(0xff86e1d2.toInt()))
        assertTrue(combined.contains(0xffffb74d.toInt()))
        assertFalse(render(false,true,false).contains(red))
    }
}
