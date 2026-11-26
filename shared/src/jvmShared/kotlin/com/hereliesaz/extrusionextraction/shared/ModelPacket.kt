package com.hereliesaz.extrusionextraction.shared

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Shared binary model payload for the private desktop IPC and versioned Colab result envelope. */
fun writeModelPacket(out:DataOutputStream,model:GeneratedModel) {
    fun ints(values:IntArray) { out.writeInt(values.size);values.forEach(out::writeInt) }
    fun floats(values:FloatArray) { out.writeInt(values.size);values.forEach(out::writeFloat) }
    fun bytes(values:ByteArray) { out.writeInt(values.size);out.write(values) }
    fun vector(v:Vec3) { out.writeFloat(v.x);out.writeFloat(v.y);out.writeFloat(v.z) }
    val mesh=model.mesh
    out.writeInt(mesh.vertices.size)
    mesh.vertices.forEach(::vector);mesh.colors.forEach(::vector)
    ints(mesh.indices)
    val rig=model.rig
    out.writeBoolean(rig!=null)
    if(rig!=null) {
        out.writeInt(rig.joints.size)
        for(joint in rig.joints) {
            out.writeUTF(joint.name);out.writeInt(joint.parent);vector(joint.position)
        }
        ints(rig.indices);floats(rig.weights)
    }
    val atlas=model.atlas
    out.writeBoolean(atlas!=null)
    if(atlas!=null) { out.writeInt(atlas.size);floats(atlas.uv);bytes(atlas.png) }
    bytes(model.glb)
}

/** A length-limited view of a stream, without closing or consuming the next packet. */
private class PacketInput(private val source:InputStream,var remaining:Long):InputStream() {
    override fun read():Int {
        if(remaining==0L) return -1
        val value=source.read()
        if(value<0) throw EOFException("Truncated model packet.")
        remaining--
        return value
    }
    override fun read(b:ByteArray,off:Int,len:Int):Int {
        if(len==0) return 0
        if(remaining==0L) return -1
        val count=source.read(b,off,minOf(len.toLong(),remaining).toInt())
        if(count<0) throw EOFException("Truncated model packet.")
        remaining-=count
        return count
    }
}

/** Decode the existing model payload. External packets have explicit allocation and content
 * bounds, including atlas dimensions, parent-first joints and exact GLB/preview consistency.
 * Trusted same-version local IPC retains its existing size range.
 */
fun readModelPacket(source:InputStream,length:Long,external:Boolean=false):GeneratedModel {
    require(length>0)
    val bounded=PacketInput(source,length)
    val input=DataInputStream(bounded)
    fun count(unit:Int,limit:Int=Int.MAX_VALUE,exact:Int?=null):Int {
        val n=input.readInt()
        require(n>=0 && n<=limit && (exact==null || n==exact) &&
            n.toLong()*unit<=bounded.remaining) { "Invalid model packet length." }
        return n
    }
    fun ints(limit:Int=Int.MAX_VALUE,exact:Int?=null)=IntArray(count(4,limit,exact)){input.readInt()}
    fun floats(limit:Int=Int.MAX_VALUE,exact:Int?=null)=FloatArray(count(4,limit,exact)){input.readFloat()}
    fun bytes(limit:Int=Int.MAX_VALUE)=ByteArray(count(1,limit)).also{input.readFully(it)}
    fun vector()=Vec3(input.readFloat(),input.readFloat(),input.readFloat())
    fun flag():Boolean {
        val v=input.readUnsignedByte()
        require(v in 0..1) { "Invalid model packet flag." }
        return v==1
    }
    val vertices=count(24,if(external)1_000_000 else Int.MAX_VALUE)
    val mesh=Mesh(List(vertices){vector()},List(vertices){vector()},ints(if(external)6_000_000 else Int.MAX_VALUE))
    val rig=if(flag()) {
        val joints=List(count(18,if(external)1024 else Int.MAX_VALUE)){
            Joint(input.readUTF(),input.readInt(),vector())
        }
        val expected=if(external) vertices*4 else null
        Rig(joints,ints(exact=expected),floats(exact=expected))
    } else null
    val atlas=if(flag()) {
        val size=input.readInt()
        if(external) require(size in 1..4096 && size and (size-1)==0) { "Invalid texture atlas dimensions." }
        TextureAtlas(floats(exact=if(external)mesh.indices.size*2 else null),
            bytes(if(external)64*1024*1024 else Int.MAX_VALUE),size)
    } else null
    val glb=bytes(if(external)MAX_COLAB_MODEL_BYTES.toInt() else Int.MAX_VALUE)
    require(bounded.remaining==0L) { "Trailing model packet data." }
    if(external) {
        require(mesh.colors.all { it.x in 0f..1f && it.y in 0f..1f && it.z in 0f..1f }) { "Invalid vertex colors." }
        rig?.let {
            require(it.joints.isNotEmpty() && it.joints.map{j->j.name}.distinct().size==it.joints.size)
            for((i,joint) in it.joints.withIndex()) {
                require(joint.name.matches(Regex("[A-Za-z0-9_]{1,128}")) && joint.position.finite() &&
                    (if(i==0)joint.parent==-1 else joint.parent in 0 until i)) { "Invalid joint hierarchy." }
            }
            require(it.indices.all { id->id in it.joints.indices }) { "Invalid joint influence." }
            for(i in it.weights.indices step 4) {
                var sum=0f
                for(k in 0..3) {
                    val w=it.weights[i+k]
                    require(w.isFinite() && w in 0f..1f) { "Invalid skin weight." }
                    sum+=w
                }
                require(abs(sum-1f)<=.002f) { "Unnormalized skin weights." }
            }
        }
        atlas?.let {
            require(it.uv.all { uv->uv.isFinite() && uv in 0f..1f }) { "Invalid atlas UVs." }
            val png=it.png
            val signature=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
            require(png.size>=33 && png.copyOfRange(0,8).contentEquals(signature)) { "Invalid texture PNG." }
            val header=ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
            require(header.getInt(8)==13 && header.getInt(12)==0x49484452 &&
                header.getInt(16)==it.size && header.getInt(20)==it.size &&
                png[24].toInt()==8 && png[25].toInt()==2 &&
                png[26].toInt()==0 && png[27].toInt()==0 && png[28].toInt()==0) { "Invalid texture PNG header." }
        }
        // EER1 uses this exact exporter. Reject unrelated or modified GLBs; retain the original bytes.
        require(writeGlb(mesh,rig,atlas).contentEquals(glb)) { "GLB differs from the preview payload." }
    }
    return GeneratedModel(mesh,rig,atlas,glb)
}
