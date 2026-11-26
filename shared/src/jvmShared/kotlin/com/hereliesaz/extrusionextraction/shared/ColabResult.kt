package com.hereliesaz.extrusionextraction.shared

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private const val RESULT_MAGIC=0x45455231 // EER1
/** External import budgets: each variant and the complete envelope are bounded separately. */
const val MAX_COLAB_MODEL_BYTES=256L*1024*1024
const val MAX_COLAB_RESULT_BYTES=512L*1024*1024

/** Stable protocol IDs, independent of enum declaration order. */
private fun modeId(mode:RigMode)=when(mode) { RigMode.NONE->0;RigMode.TEMPLATE->1;RigMode.LEARNED->2 }
private fun modeFor(id:Int)=when(id) { 0->RigMode.NONE;1->RigMode.TEMPLATE;2->RigMode.LEARNED;else->error("Unknown rig variant.") }

/** Write an EER1 envelope from staged payloads without retaining all variant meshes in the worker.
 * Callers publish the completed envelope atomically. No partial packet is exposed to the user.
 */
fun writeColabResult(destination:OutputStream,packets:Map<RigMode,File>) {
    require(packets.size in 1..3 && RigMode.NONE in packets) { "Missing unrigged result." }
    val lengths=packets.mapValues { (_,file)->file.length().also {
        require(file.isFile && it in 1..MAX_COLAB_MODEL_BYTES) { "Result variant exceeds import budget." }
    } }
    require(8L+12L*packets.size+lengths.values.sum()<=MAX_COLAB_RESULT_BYTES) { "Result exceeds import budget." }
    val out=DataOutputStream(destination)
    out.writeInt(RESULT_MAGIC);out.writeInt(packets.size)
    for((mode,file) in packets.entries.sortedBy { modeId(it.key) }) {
        val length=lengths.getValue(mode)
        out.writeInt(modeId(mode));out.writeLong(length)
        file.inputStream().use { input->
            val buffer=ByteArray(64*1024)
            var remaining=length
            while(remaining>0) {
                val n=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt())
                require(n>0) { "Staged result changed." }
                out.write(buffer,0,n);remaining-=n
            }
            require(input.read()==-1) { "Staged result changed." }
        }
    }
    out.flush()
}

/** Read and validate all variants before publishing any workspace state. The caller owns the stream. */
fun readColabResult(source:InputStream):Map<RigMode,GeneratedModel> {
    val input=DataInputStream(source)
    require(input.readInt()==RESULT_MAGIC) { "Unsupported Colab result format." }
    val count=input.readInt()
    require(count in 1..3) { "Invalid result variant count." }
    var consumed=8L
    val result=linkedMapOf<RigMode,GeneratedModel>()
    repeat(count) {
        val mode=modeFor(input.readInt())
        require(mode !in result) { "Duplicate result variant." }
        val length=input.readLong()
        require(length in 1..MAX_COLAB_MODEL_BYTES) { "Invalid result packet size." }
        consumed+=12L+length
        require(consumed<=MAX_COLAB_RESULT_BYTES) { "Result exceeds import budget." }
        val model=readModelPacket(input,length,external=true)
        require((mode==RigMode.NONE)==(model.rig==null)) { "Rig variant does not match payload." }
        result[mode]=model
    }
    require(RigMode.NONE in result && input.read()==-1) { "Missing unrigged result or trailing data." }
    return result.toMap()
}
