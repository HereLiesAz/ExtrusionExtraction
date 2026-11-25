package com.hereliesaz.extrusionextraction.shared

import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import kotlin.test.*

class PhotoTextureTest {
    private val n=IMAGE_SIZE*IMAGE_SIZE
    private val k=floatArrayOf(100f,0f,252f,0f,100f,252f,0f,0f,1f)
    private val e=floatArrayOf(1f,0f,0f,1f,0f,1f,0f,2f,0f,0f,1f,3f)
    private fun view(color:Int,depth:Float=2f,confidence:Float=1f)=CameraView(
        ViewImage("test",IntArray(n){color}),FloatArray(n){depth},FloatArray(n){confidence},k,e)
    private val red=view(0xffff0000.toInt())
    private val blue=view(0xff0000ff.toInt())
    private val occluded=view(0xff00ff00.toInt(),1f,10f)
    private val frame=ReconstructionFrame(red,Vec3(0f,0f,-2f),2f,.01f)
    private val a=Vec3(0f,0f,0f);private val b=Vec3(.1f,0f,0f);private val c=Vec3(0f,.1f,0f)
    @Test fun inverseNormalization() {
        assertEquals(Vec3(-1f,-2f,-1f),frame.world(a))
        assertEquals(Vec3(0f,0f,-1f),frame.direction(Vec3(0f,0f,1f)))
        val rotated=CameraView(red.image,red.depth,red.confidence,k,
            floatArrayOf(0f,-1f,0f,1f,1f,0f,0f,2f,0f,0f,1f,3f))
        assertEquals(Vec3(-2f,1f,-1f),ReconstructionFrame(rotated,Vec3(0f,0f,-2f),2f,.01f).world(a))
    }
    @Test fun blendAndRejectOcclusion() {
        assertEquals(Vec3(1f,0f,0f),PhotoTexture(frame,listOf(red,occluded)).forFace(a,b,c)(a))
        val mixed=assertNotNull(PhotoTexture(frame,listOf(red,blue)).forFace(a,b,c)(a))
        assertEquals(.5f,mixed.x,.000001f);assertEquals(0f,mixed.y);assertEquals(.5f,mixed.z,.000001f)
        assertNull(PhotoTexture(frame,listOf(occluded)).forFace(a,b,c)(a))
    }
    @Test fun rejectBackfaceAndTransparentPixels() {
        assertNull(PhotoTexture(frame,listOf(red)).forFace(a,c,b)(a))
        assertNull(PhotoTexture(frame,listOf(view(0x000000ff))).forFace(a,b,c)(a))
    }
    @Test fun decodedAtlasUsesPhotoAndFallsBack() {
        val mesh=Mesh(listOf(a,b,c),List(3){Vec3(0f,1f,0f)},intArrayOf(0,1,2))
        fun pixel(photo:PhotoTexture)=ImageIO.read(ByteArrayInputStream(
            assertNotNull(bakeTextureAtlas(mesh,photo)).png)).getRGB(1,1)
        assertEquals(0xffff0000.toInt(),pixel(PhotoTexture(frame,listOf(red))))
        assertEquals(0xff00ff00.toInt(),pixel(PhotoTexture(frame,listOf(occluded))))
    }
}
