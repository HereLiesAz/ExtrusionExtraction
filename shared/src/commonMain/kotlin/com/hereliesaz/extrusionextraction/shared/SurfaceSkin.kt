package com.hereliesaz.extrusionextraction.shared

import kotlin.math.sqrt

/** Bind a fixed skeleton using shortest paths along mesh edges rather than
 * straight-line distances through empty space. Each joint seeds its closest
 * surface vertex; propagation never crosses disconnected components.
 * Unseeded components are bound rigidly to their nearest joint. This is a
 * geometric approximation, not learned or volumetric skinning.
 */
internal fun surfaceSkin(mesh:Mesh,joints:List<Joint>,checkpoint:()->Unit):Rig {
    require(joints.size in 1..65535 && joints.all{it.position.finite()})
    val n=mesh.vertices.size
    val adjacent=Array(n){mutableSetOf<Int>()}
    for(i in mesh.indices.indices step 3) {
        if(i%3072==0) checkpoint()
        for(k in 0..2) {
            val a=mesh.indices[i+k];val b=mesh.indices[i+(k+1)%3]
            if(a!=b) {adjacent[a].add(b);adjacent[b].add(a)}
        }
    }
    fun distance(a:Vec3,b:Vec3):Float {val d=a-b;return sqrt(d.dot(d))}
    val neighbors=Array(n){adjacent[it].toIntArray()}
    val lengths=Array(n){v->FloatArray(neighbors[v].size){distance(mesh.vertices[v],mesh.vertices[neighbors[v][it]])}}
    val nearestJoint=IntArray(n)
    val nearestGap=FloatArray(n){Float.POSITIVE_INFINITY}
    val seeds=IntArray(joints.size)
    val seedGap=FloatArray(joints.size)
    for((j,joint) in joints.withIndex()) {
        var best=Float.POSITIVE_INFINITY;var seed=0
        for(v in 0 until n) {
            if(v%2048==0) checkpoint()
            val d=distance(mesh.vertices[v],joint.position)
            if(d<nearestGap[v]) {nearestGap[v]=d;nearestJoint[v]=j}
            if(d<best) {best=d;seed=v}
        }
        require(best.isFinite()) { "Invalid joint-to-surface distance." }
        seeds[j]=seed;seedGap[j]=best
    }
    val ids=IntArray(n*4)
    val best=FloatArray(n*4){Float.POSITIVE_INFINITY}
    for(j in joints.indices) {
        checkpoint()
        val dist=FloatArray(n){Float.POSITIVE_INFINITY}
        val heap=SkinDistanceHeap(dist)
        val seed=seeds[j];dist[seed]=seedGap[j];heap.offer(seed)
        var visited=0
        while(!heap.empty) {
            if(visited++%2048==0) checkpoint()
            val v=heap.pop();val d=dist[v]
            val base=v*4
            for(k in 0..3) if(d<best[base+k]) {
                for(m in 3 downTo k+1) {best[base+m]=best[base+m-1];ids[base+m]=ids[base+m-1]}
                best[base+k]=d;ids[base+k]=j;break
            }
            for(k in neighbors[v].indices) {
                val u=neighbors[v][k];val candidate=d+lengths[v][k]
                if(candidate<dist[u]) {dist[u]=candidate;heap.offer(u)}
            }
        }
    }
    // Every disconnected unseeded component receives one rigid binding, chosen
    // by the globally closest vertex/joint pair in that component.
    val visited=BooleanArray(n);val queue=ArrayDeque<Int>()
    val weights=FloatArray(n*4)
    for(v in 0 until n) {
        if(v%2048==0) checkpoint()
        val base=v*4
        if(best[base].isFinite()) {
            val minimum=best[base].coerceAtLeast(.005f)
            var total=0f
            for(k in 0..3) if(best[base+k].isFinite()) {
                val ratio=minimum/best[base+k].coerceAtLeast(.005f)
                val squared=ratio*ratio;weights[base+k]=squared*squared;total+=weights[base+k]
            }
            for(k in 0..3) weights[base+k]/=total
        } else if(!visited[v]) {
            val component=mutableListOf<Int>();var closest=v
            queue.add(v);visited[v]=true
            while(queue.isNotEmpty()) {
                val u=queue.removeFirst();component.add(u)
                if(component.size%2048==0) checkpoint()
                if(nearestGap[u]<nearestGap[closest]) closest=u
                for(w in neighbors[u]) if(!visited[w]) {visited[w]=true;queue.add(w)}
            }
            for(u in component) {ids[u*4]=nearestJoint[closest];weights[u*4]=1f}
        }
    }
    return Rig(joints,ids,weights)
}

/** Indexed min-heap with one entry per vertex and explicit decrease-key.
 * Distances are nonnegative; a settled vertex never needs to be reinserted.
 */
private class SkinDistanceHeap(private val distance:FloatArray) {
    private val vertices=IntArray(distance.size)
    private val position=IntArray(distance.size){-1}
    private var size=0
    val empty:Boolean get()=size==0
    private fun swap(a:Int,b:Int) {
        val v=vertices[a];vertices[a]=vertices[b];vertices[b]=v
        position[vertices[a]]=a;position[vertices[b]]=b
    }
    fun offer(v:Int) {
        var i=position[v]
        if(i<0) {i=size++;vertices[i]=v;position[v]=i}
        while(i>0) {
            val p=(i-1)/2
            if(distance[vertices[p]]<=distance[vertices[i]]) break
            swap(p,i);i=p
        }
    }
    fun pop():Int {
        check(size>0)
        val v=vertices[0];size--;position[v]=-1
        if(size>0) {
            vertices[0]=vertices[size];position[vertices[0]]=0
            var i=0
            while(i*2+1<size) {
                val a=i*2+1;val b=a+1
                val child=if(b<size && distance[vertices[b]]<distance[vertices[a]]) b else a
                if(distance[vertices[i]]<=distance[vertices[child]]) break
                swap(i,child);i=child
            }
        }
        return v
    }
}
