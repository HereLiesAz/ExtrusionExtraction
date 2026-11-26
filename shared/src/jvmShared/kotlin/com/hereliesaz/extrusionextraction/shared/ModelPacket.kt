package com.hereliesaz.extrusionextraction.shared

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.EOFException

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

/** Keep the desktop IPC stream and exact JVM GLB verification contract. */
fun readModelPacket(source:InputStream,length:Long,external:Boolean=false):GeneratedModel {
    require(length>0)
    val bounded=PacketInput(source,length)
    val input=DataInputStream(bounded)
    val reader=object:ModelPacketReader {
        override val remaining:Long get()=bounded.remaining
        override fun readUnsignedByte()=input.readUnsignedByte()
        override fun readInt()=input.readInt()
        override fun readFloat()=input.readFloat()
        override fun readFully(destination:ByteArray)=input.readFully(destination)
        override fun readUTF()=input.readUTF()
    }
    return readModelPacketPayload(reader,external) { model->
        require(writeGlb(model.mesh,model.rig,model.atlas).contentEquals(model.glb)) { "GLB differs from the preview payload." }
    }
}
