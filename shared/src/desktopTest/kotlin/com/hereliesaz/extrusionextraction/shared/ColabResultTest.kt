package com.hereliesaz.extrusionextraction.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColabResultTest {
    private val mesh=Mesh(listOf(Vec3(0f,0f,0f),Vec3(1f,0f,0f),Vec3(0f,1f,0f)),
        listOf(Vec3(1f,0f,0f),Vec3(0f,1f,0f),Vec3(0f,0f,1f)),intArrayOf(0,1,2))
    private val rig=Rig(listOf(Joint("root",-1,Vec3(0f,0f,0f))),IntArray(12),FloatArray(12){if(it%4==0)1f else 0f})

    private fun bundle(models:Map<RigMode,GeneratedModel>):ByteArray {
        val dir=Files.createTempDirectory("ee-result-test-").toFile()
        try {
            val packets=models.mapValues { (mode,model)->
                File(dir,mode.name).also { file->DataOutputStream(file.outputStream()).use{writeModelPacket(it,model)} }
            }
            return ByteArrayOutputStream().also{writeColabResult(it,packets)}.toByteArray()
        } finally { dir.deleteRecursively() }
    }

    @Test fun completedVariantsPreservePreviewAndExactExport() {
        val atlas=bakeTextureAtlas(mesh)!!
        val base=GeneratedModel(mesh,null,atlas,writeGlb(mesh,null,atlas))
        val template=GeneratedModel(mesh,rig,null,writeGlb(mesh,rig,null))
        val bytes=bundle(mapOf(RigMode.NONE to base,RigMode.TEMPLATE to template))
        assertContentEquals(byteArrayOf(69,69,82,49,0,0,0,2,0,0,0,0),bytes.copyOfRange(0,12))
        val decoded=readColabResult(ByteArrayInputStream(bytes))
        assertEquals(listOf(RigMode.NONE,RigMode.TEMPLATE),decoded.keys.toList())
        val actual=decoded.getValue(RigMode.NONE)
        assertEquals(mesh.vertices,actual.mesh.vertices)
        assertContentEquals(intArrayOf(0,1,2),actual.mesh.indices)
        assertNull(actual.rig)
        assertContentEquals(base.glb,actual.glb)
        assertContentEquals(atlas.png,actual.atlas!!.png)
        assertContentEquals(atlas.uv,actual.atlas!!.uv)
        assertEquals("root",decoded.getValue(RigMode.TEMPLATE).rig!!.joints.single().name)
        assertContentEquals(floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f,0f,0f,0f),decoded.getValue(RigMode.TEMPLATE).rig!!.weights)
        assertContentEquals(template.glb,decoded.getValue(RigMode.TEMPLATE).glb)
    }

    @Test fun invalidEnvelopeAndPayloadAreRejected() {
        val base=GeneratedModel(mesh,null,null,writeGlb(mesh,null))
        val bytes=bundle(mapOf(RigMode.NONE to base))
        for(corrupt in listOf(bytes.copyOf(bytes.size-1),bytes+0.toByte(),
            bytes.copyOf().apply{this[3]=50},bytes.copyOf().apply{this[7]=4},
            bytes.copyOf().apply{ByteBuffer.wrap(this).putLong(12,Long.MAX_VALUE)},
            bytes.copyOf().apply{ByteBuffer.wrap(this).putInt(20,Int.MAX_VALUE)},
            bytes.copyOf().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()})) {
            assertFails{readColabResult(ByteArrayInputStream(corrupt))}
        }
        assertFailsWith<IllegalArgumentException>{bundle(mapOf(RigMode.TEMPLATE to base))}
        val mismatched=bundle(mapOf(RigMode.NONE to base,RigMode.LEARNED to base))
        assertFailsWith<IllegalArgumentException>{readColabResult(ByteArrayInputStream(mismatched))}
    }

    @Test fun privateWorkerPayloadRemainsCompatible() {
        val glb=writeGlb(mesh,null)
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{writeModelPacket(it,GeneratedModel(mesh,null,null,glb))}
        val data=bytes.toByteArray()
        assertContentEquals(byteArrayOf(0,0,0,3,0,0,0,0),data.copyOfRange(0,8))
        val decoded=readModelPacket(ByteArrayInputStream(data),data.size.toLong())
        assertContentEquals(glb,decoded.glb)
        assertTrue(decoded.mesh.indices.contentEquals(intArrayOf(0,1,2)))
    }
}
