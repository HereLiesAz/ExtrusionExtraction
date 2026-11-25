package com.hereliesaz.extrusionextraction.shared

import kotlin.math.abs

/** Explicit choice; learned inference never silently falls back to template rigging. */
enum class RigMode { NONE, TEMPLATE, LEARNED }

/** Validate the version-one JNI rig packet: version, joint count, then parent and
 * XYZ per joint, followed by four joint indices and four weights per source vertex.
 * The existing GLB exporter requires one parent-first hierarchy and translation-only rest joints.
 * Stable synthetic names avoid transferring untrusted strings into GLB JSON.
 */
internal fun decodeLearnedRig(packet:FloatArray,vertexCount:Int):Rig {
    require(vertexCount>0 && packet.size>=2 && packet[0]==1f) { "Invalid learned rig packet version." }
    fun integer(value:Float,min:Int,max:Int):Int {
        require(value.isFinite() && value>=min && value<=max && value==value.toInt().toFloat()) {
            "Invalid learned rig integer."
        }
        return value.toInt()
    }
    val count=integer(packet[1],1,65535)
    require(packet.size.toLong()==2L+4L*count+8L*vertexCount) { "Learned rig packet length mismatch." }
    val joints=List(count){i->
        val start=2+4*i
        val parent=integer(packet[start],if(i==0)-1 else 0,i-1)
        val p=Vec3(packet[start+1],packet[start+2],packet[start+3])
        require(p.finite()) { "Non-finite learned joint position." }
        Joint("joint_$i",parent,p)
    }
    val ids=IntArray(vertexCount*4);val weights=FloatArray(ids.size)
    for(v in 0 until vertexCount) {
        val start=2+4*count+8*v
        var sum=0f
        val seen=mutableSetOf<Int>()
        for(k in 0..3) {
            val id=integer(packet[start+k],0,count-1)
            val weight=packet[start+4+k]
            require(weight.isFinite() && weight in 0f..1f) { "Invalid learned skin weight." }
            require(weight==0f || seen.add(id)) { "Duplicate positive learned influence." }
            ids[v*4+k]=id;weights[v*4+k]=weight;sum+=weight
        }
        require(abs(sum-1f)<=.001f) { "Learned weights are not normalized." }
        for(k in 0..3) weights[v*4+k]/=sum
    }
    return Rig(joints,ids,weights)
}
