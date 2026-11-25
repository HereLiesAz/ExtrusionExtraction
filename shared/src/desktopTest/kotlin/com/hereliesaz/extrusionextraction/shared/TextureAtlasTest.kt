package com.hereliesaz.extrusionextraction.shared

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.*

class TextureAtlasTest {
    private val mesh=Mesh(listOf(Vec3(0f,0f,0f),Vec3(1f,0f,0f),Vec3(0f,1f,0f)),
        listOf(Vec3(1f,0f,0f),Vec3(0f,1f,0f),Vec3(0f,0f,1f)),intArrayOf(0,1,2))
    @Test fun cornerColorsAndPadding() {
        val a=assertNotNull(bakeTextureAtlas(mesh))
        val image=ImageIO.read(ByteArrayInputStream(a.png))
        assertEquals(32,image.width);assertEquals(32,image.height)
        assertContentEquals(floatArrayOf(1.5f/32,1.5f/32,30.5f/32,1.5f/32,1.5f/32,30.5f/32),a.uv)
        assertEquals(0xffff0000.toInt(),image.getRGB(1,1))
        assertEquals(0xff00ff00.toInt(),image.getRGB(30,1))
        assertEquals(0xff0000ff.toInt(),image.getRGB(1,30))
        assertEquals(0xffff0000.toInt(),image.getRGB(0,0))
        assertEquals(0xff00ff00.toInt(),image.getRGB(31,0))
    }
    @Test fun distinctCharts() {
        val a=assertNotNull(bakeTextureAtlas(Mesh(mesh.vertices,mesh.colors,intArrayOf(0,1,2,2,1,0))))
        assertEquals(64,a.size);assertTrue(a.uv[6]>a.uv[2])
    }
    @Test fun texturedGlbContainsEmbeddedImageAndSkin() {
        val a=assertNotNull(bakeTextureAtlas(mesh))
        val bytes=writeGlb(mesh,humanoidRig(mesh),a)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546c67,b.int);assertEquals(2,b.int);assertEquals(bytes.size,b.int)
        val length=b.int;assertEquals(0x4e4f534a,b.int)
        val json=ByteArray(length).also{b.get(it)}.decodeToString()
        for(key in listOf("TEXCOORD_0","JOINTS_0","WEIGHTS_0","baseColorTexture","image/png","materials"))
            assertTrue(json.contains(key),key)
        assertFalse(json.contains("COLOR_0"))
        assertEquals(b.remaining()-8,b.int);assertEquals(0x004e4942,b.int)
        assertTrue(bytes.asList().windowed(8).any{it==a.png.take(8)})
    }
}
