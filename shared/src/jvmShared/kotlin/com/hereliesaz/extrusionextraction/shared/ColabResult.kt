package com.hereliesaz.extrusionextraction.shared

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

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
    return readColabEnvelope(input::readInt,input::readLong,{ length->readModelPacket(input,length,external=true) },
        {input.read()==-1})
}
