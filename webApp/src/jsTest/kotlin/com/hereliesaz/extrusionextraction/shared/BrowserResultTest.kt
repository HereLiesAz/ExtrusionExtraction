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

    /** Independently constructed minimal GLB fixture: one RGB triangle with explicit normals. */
    @Test fun embeddedTriangleMatchesPacketAndAlteredPositionDoesNot() {
        val mesh=Mesh(listOf(Vec3(0f,0f,0f),Vec3(1f,0f,0f),Vec3(0f,1f,0f)),
            listOf(Vec3(1f,0f,0f),Vec3(0f,1f,0f),Vec3(0f,0f,1f)),intArrayOf(0,1,2))
        val json="""{"asset":{"version":"2.0","generator":"ExtrusionExtraction"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"COLOR_0":3},"indices":2}]}],"buffers":[{"byteLength":120}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},{"buffer":0,"byteOffset":36,"byteLength":36,"target":34962},{"buffer":0,"byteOffset":72,"byteLength":12,"target":34963},{"buffer":0,"byteOffset":84,"byteLength":36,"target":34962}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":2,"componentType":5125,"count":3,"type":"SCALAR"},{"bufferView":3,"componentType":5126,"count":3,"type":"VEC3"}]}"""
        val raw=json.encodeToByteArray()
        val padded=raw.size+(4-raw.size%4)%4
        val bytes=ByteArray(28+padded+120)
        fun put(at:Int,value:Int) { for(k in 0..3) bytes[at+k]=(value ushr (k*8)).toByte() }
        put(0,0x46546c67);put(4,2);put(8,bytes.size);put(12,padded);put(16,0x4e4f534a)
        bytes.fill(32,20,20+padded);raw.copyInto(bytes,20)
        put(20+padded,120);put(24+padded,0x004e4942)
        val start=28+padded
        val words=intArrayOf(0,0,0,0x3f800000,0,0,0,0x3f800000,0,
            0,0,0x3f800000,0,0,0x3f800000,0,0,0x3f800000,0,1,2,
            0x3f800000,0,0,0,0x3f800000,0,0,0,0x3f800000)
        words.forEachIndexed{i,value->put(start+i*4,value)}
        validateBrowserGlb(GeneratedModel(mesh,null,null,bytes))
        val changed=bytes.copyOf()
        changed[start+3]=63
        assertFails {validateBrowserGlb(GeneratedModel(mesh,null,null,changed))}
    }
}
