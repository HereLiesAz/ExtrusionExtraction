package com.hereliesaz.extrusionextraction.shared

import kotlin.math.abs
import kotlin.math.sqrt

private data class Edge(val a:Int,val b:Int)
private fun edge(a:Int,b:Int)=Edge(minOf(a,b),maxOf(a,b))
private data class Use(val face:Int,val from:Int,val to:Int)
private data class FaceKey(val a:Int,val b:Int,val c:Int)
private fun key(a:Int,b:Int,c:Int):FaceKey {
    val sorted=intArrayOf(a,b,c).sorted()
    return FaceKey(sorted[0],sorted[1],sorted[2])
}

/** Repair small defects in a triangle surface before skinning.
 * Preserve disconnected components and vertex colors; never bridge unrelated shells.
 * Only simple, approximately planar, convex holes up to 32 edges and 12% of
 * the model's bounding-box diagonal are capped. Reject larger defects, ambiguous
 * topology and non-orientable surfaces rather than silently inventing geometry.
 * This validates combinatorial manifoldness, not geometric self-intersections.
 */
fun repairMesh(mesh:Mesh, checkpoint:()->Unit = {}):Mesh {
    val vertices=mesh.vertices.toMutableList();val colors=mesh.colors.toMutableList()
    val extent=Vec3(vertices.maxOf{it.x}-vertices.minOf{it.x},
        vertices.maxOf{it.y}-vertices.minOf{it.y},vertices.maxOf{it.z}-vertices.minOf{it.z})
    val diagonal=sqrt(extent.dot(extent));require(diagonal.isFinite() && diagonal>0f)
    val faces=mutableListOf<IntArray>();val seen=mutableSetOf<FaceKey>()
    for(i in mesh.indices.indices step 3) {
        if(i%3072==0) checkpoint()
        val a=mesh.indices[i];val b=mesh.indices[i+1];val c=mesh.indices[i+2]
        if(a==b || b==c || c==a) continue
        val normal=(vertices[b]-vertices[a]).cross(vertices[c]-vertices[a])
        if(normal.dot(normal)<=1e-24f*diagonal*diagonal*diagonal*diagonal) continue
        if(seen.add(key(a,b,c))) faces.add(intArrayOf(a,b,c))
    }
    require(faces.isNotEmpty()) { "Mesh contains no valid faces." }
    fun incidence():Map<Edge,MutableList<Use>> {
        val edges=mutableMapOf<Edge,MutableList<Use>>()
        for((i,f) in faces.withIndex()) {
            if(i%1024==0) checkpoint()
            for(j in 0..2) {
                val a=f[j];val b=f[(j+1)%3]
                val uses=edges.getOrPut(edge(a,b)){mutableListOf()}
                uses.add(Use(i,a,b))
                require(uses.size<=2) { "Non-manifold edge; reconstruction requires better references." }
            }
        }
        return edges
    }
    // Orient neighboring triangles coherently, preserving the seed's original orientation.
    val adjacent=Array(faces.size){mutableListOf<Pair<Int,Boolean>>()}
    for(uses in incidence().values) if(uses.size==2) {
        val a=uses[0];val b=uses[1];val same=a.from==b.from
        adjacent[a.face].add(b.face to same);adjacent[b.face].add(a.face to same)
    }
    val flipped=IntArray(faces.size){-1};val queue=ArrayDeque<Int>()
    for(seed in faces.indices) {
        if(flipped[seed]>=0) continue
        flipped[seed]=0;queue.add(seed)
        while(queue.isNotEmpty()) {
            val i=queue.removeFirst();if(i%1024==0) checkpoint()
            for((j,same) in adjacent[i]) {
                val expected=flipped[i] xor if(same) 1 else 0
                if(flipped[j]<0) { flipped[j]=expected;queue.add(j) }
                else require(flipped[j]==expected) { "Non-orientable surface." }
            }
        }
    }
    for(i in faces.indices) if(flipped[i]==1) { val f=faces[i];val b=f[1];f[1]=f[2];f[2]=b }
    val boundary=mutableMapOf<Int,Int>();val incoming=mutableSetOf<Int>()
    for(uses in incidence().values) if(uses.size==1) {
        val u=uses.single()
        require(boundary.put(u.from,u.to)==null && incoming.add(u.to)) { "Ambiguous hole boundary." }
    }
    while(boundary.isNotEmpty()) {
        checkpoint()
        val start=boundary.keys.first();val loop=mutableListOf<Int>();var current=start
        do {
            loop.add(current)
            require(loop.size<=32) { "Hole exceeds safe repair edge limit." }
            current=boundary.remove(current) ?: error("Open boundary chain.")
        } while(current!=start)
        require(loop.size>=3)
        val center=loop.map{vertices[it]}.reduce(Vec3::plus)*(1f/loop.size)
        var diameter=0f
        for(a in loop) for(b in loop) { val d=vertices[a]-vertices[b];diameter=maxOf(diameter,sqrt(d.dot(d))) }
        require(diameter<=diagonal*.12f && diameter>0f) { "Hole exceeds safe repair size." }
        val offsets=loop.map{vertices[it]-center}
        val normal=offsets.indices.map{offsets[it].cross(offsets[(it+1)%offsets.size])}.reduce(Vec3::plus)
        require(normal.dot(normal)>1e-20f*diameter*diameter*diameter*diameter) { "Degenerate hole." }
        val n=normal.unit()
        require(offsets.all{abs(it.dot(n))<=diameter*.03f}) { "Non-planar hole." }
        // A convex, simple loop must keep every other vertex on the interior side of every edge.
        for(i in offsets.indices) {
            val a=offsets[i];val b=offsets[(i+1)%offsets.size]
            require(a.cross(b).dot(n)>1e-8f*diameter*diameter) { "Non-convex or degenerate hole." }
            for(j in offsets.indices) require((b-a).cross(offsets[j]-a).dot(n)>=-1e-6f*diameter*diameter) {
                "Non-convex or self-crossing hole."
            }
        }
        val id=vertices.size;vertices.add(center)
        colors.add(loop.map{colors[it]}.reduce(Vec3::plus)*(1f/loop.size))
        for(i in loop.indices) faces.add(intArrayOf(loop[(i+1)%loop.size],loop[i],id))
    }
    require(incidence().values.all{it.size==2 && it[0].from==it[1].to}) { "Mesh is not a closed oriented surface." }
    // The link of every used vertex must be one cycle, not two shells touching at a point.
    val links=Array(vertices.size){mutableMapOf<Int,Int>()}
    for((i,f) in faces.withIndex()) {
        if(i%1024==0) checkpoint()
        for(j in 0..2) require(links[f[j]].put(f[(j+1)%3],f[(j+2)%3])==null) { "Non-manifold vertex." }
    }
    for(link in links) if(link.isNotEmpty()) {
        val start=link.keys.first();var at=start;var count=0
        do { at=link[at] ?: error("Open vertex fan.");count++;require(count<=link.size) } while(at!=start)
        require(count==link.size) { "Disconnected vertex fan; non-manifold surface." }
    }
    val used=BooleanArray(vertices.size);faces.forEach{f->f.forEach{used[it]=true}}
    val remap=IntArray(vertices.size){-1};val positions=mutableListOf<Vec3>();val rgb=mutableListOf<Vec3>()
    for(i in vertices.indices) if(used[i]) {remap[i]=positions.size;positions.add(vertices[i]);rgb.add(colors[i])}
    return Mesh(positions,rgb,IntArray(faces.size*3){remap[faces[it/3][it%3]]})
}
