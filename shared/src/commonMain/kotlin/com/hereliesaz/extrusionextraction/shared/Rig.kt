package com.hereliesaz.extrusionextraction.shared

import kotlin.math.pow

/** Joint position in model coordinates, with parent index preceding each child. */
data class Joint(val name:String,val parent:Int,val position:Vec3)
/** Four joint indices and normalized weights per vertex. */
class Rig(val joints:List<Joint>,val indices:IntArray,val weights:FloatArray)

/** Experimental humanoid T-pose template. Mesh-aware learned rigging is not implemented.
 * The first reference must face an upright subject with extended arms.
 */
fun humanoidJoints(mesh:Mesh):List<Joint> {
    val half=(mesh.vertices.maxOf { it.x }-mesh.vertices.minOf { it.x })/2
    require(half>.05f) { "Mesh too narrow for T-pose rig." }
    val j=mutableListOf<Joint>()
    fun add(name:String,parent:Int,x:Float,y:Float) { j.add(Joint(name,parent,Vec3(x*half,y,0f))) }
    add("hips",-1,0f,.52f);add("spine",0,0f,.65f);add("chest",1,0f,.76f)
    add("neck",2,0f,.86f);add("head",3,0f,.94f)
    for(s in listOf(-1f,1f)) {
        val side=if(s<0) "R" else "L"; val start=j.size
        add(side+"_shoulder",2,s*.30f,.78f);add(side+"_elbow",start,s*.63f,.77f)
        add(side+"_wrist",start+1,s*.90f,.76f);add(side+"_hand",start+2,s*.98f,.76f)
        add(side+"_hip",0,s*.22f,.49f);add(side+"_knee",start+4,s*.22f,.25f)
        add(side+"_ankle",start+5,s*.22f,.05f)
    }
    return j
}

/** Bind a fixed skeleton to the final mesh; topology changes do not move joints. */
fun humanoidRig(mesh:Mesh,j:List<Joint> = humanoidJoints(mesh)):Rig {
    require(j.size>=4)
    val indices=IntArray(mesh.vertices.size*4); val weights=FloatArray(indices.size)
    for((i,v) in mesh.vertices.withIndex()) {
        val nearest=j.indices.sortedBy { val d=v-j[it].position;d.dot(d) }.take(4)
        var sum=0f
        for((k,id) in nearest.withIndex()) {
            val d=v-j[id].position
            val w=1f/d.dot(d).coerceAtLeast(.000025f).pow(2)
            indices[i*4+k]=id;weights[i*4+k]=w;sum+=w
        }
        for(k in 0..3) weights[i*4+k]/=sum
    }
    return Rig(j,indices,weights)
}

/** Protect a bounded neighborhood around each provisional joint. Radius is based
 * on adjacent bone lengths in the normalized mesh frame, not a learned bend region.
 */
fun jointProtection(joints:List<Joint>):(Vec3)->Boolean {
    val radius=FloatArray(joints.size){.035f}
    for((i,joint) in joints.withIndex()) {
        if(joint.parent<0) continue
        require(joint.parent<i)
        val d=joint.position-joints[joint.parent].position
        val r=(kotlin.math.sqrt(d.dot(d))*.65f).coerceIn(.035f,.10f)
        radius[i]=maxOf(radius[i],r);radius[joint.parent]=maxOf(radius[joint.parent],r)
    }
    return { p -> joints.indices.any { i ->
        val d=p-joints[i].position
        d.dot(d)<=radius[i]*radius[i]
    } }
}
