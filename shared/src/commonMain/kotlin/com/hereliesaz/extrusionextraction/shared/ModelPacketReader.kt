package com.hereliesaz.extrusionextraction.shared

import kotlin.math.abs

/** Binary packet reader shared by the bounded JVM stream and browser byte-array adapters. */
internal interface ModelPacketReader {
    val remaining:Long
    fun readUnsignedByte():Int
    fun readInt():Int
    fun readFloat():Float=Float.fromBits(readInt())
    fun readLong():Long=(readInt().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)
    fun readFully(destination:ByteArray)
    fun readUTF():String
}

/** The portable EER1 external format only accepts ASCII joint names. JVM private IPC retains
 * DataInputStream's modified UTF decoder. Slices share storage and have independent bounds.
 */
internal class ArrayPacketReader(private val data:ByteArray,private var offset:Int=0,
                                 length:Int=data.size):ModelPacketReader {
    init { require(offset>=0 && length>=0 && offset.toLong()+length<=data.size) }
    private val end=offset+length
    override val remaining:Long get()=(end-offset).toLong()
    override fun readUnsignedByte():Int {
        require(offset<end) { "Truncated model packet." }
        return data[offset++].toInt() and 255
    }
    override fun readInt():Int {
        var result=0
        repeat(4) { result=(result shl 8) or readUnsignedByte() }
        return result
    }
    override fun readFully(destination:ByteArray) {
        require(destination.size.toLong()<=remaining) { "Truncated model packet." }
        data.copyInto(destination,0,offset,offset+destination.size)
        offset+=destination.size
    }
    override fun readUTF():String {
        val length=(readUnsignedByte() shl 8) or readUnsignedByte()
        require(length in 1..128 && length.toLong()<=remaining) { "Invalid joint name length." }
        return buildString(length) {
            repeat(length) {
                val value=readUnsignedByte()
                require(value in 1..127) { "Invalid joint name encoding." }
                append(value.toChar())
            }
        }
    }
    fun slice(length:Long):ArrayPacketReader {
        require(length in 0..remaining) { "Truncated result packet." }
        val result=ArrayPacketReader(data,offset,length.toInt())
        offset+=length.toInt()
        return result
    }
}

/** Decode the existing model payload. External packets have explicit allocation and content
 * bounds, including atlas dimensions, parent-first joints and exact GLB/preview consistency.
 * Trusted same-version local IPC retains its existing size range.
 */
internal fun readModelPacketPayload(input:ModelPacketReader,external:Boolean,verifyGlb:(GeneratedModel)->Unit):GeneratedModel {
    fun count(unit:Int,limit:Int=Int.MAX_VALUE,exact:Int?=null):Int {
        val n=input.readInt()
        require(n>=0 && n<=limit && (exact==null || n==exact) &&
            n.toLong()*unit<=input.remaining) { "Invalid model packet length." }
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
    require(input.remaining==0L) { "Trailing model packet data." }
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
            val header=ArrayPacketReader(png,8,png.size-8)
            require(header.readInt()==13 && header.readInt()==0x49484452 &&
                header.readInt()==it.size && header.readInt()==it.size &&
                png[24].toInt()==8 && png[25].toInt()==2 &&
                png[26].toInt()==0 && png[27].toInt()==0 && png[28].toInt()==0) { "Invalid texture PNG header." }
        }
    }
    val model=GeneratedModel(mesh,rig,atlas,glb)
    if(external) verifyGlb(model)
    return model
}
