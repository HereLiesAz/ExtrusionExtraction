package com.hereliesaz.extrusionextraction.shared

import kotlin.math.sqrt

/** Reduce redundant triangles with conservative, independent edge collapses.
 * Only near-planar, similar-color one-rings are eligible. The closed-manifold
 * link condition, surviving face orientation and an accumulated displacement
 * bound constrain each collapse. Disconnected shells and sharp features are
 * retained. Optional protected regions preserve existing incident triangles.
 * This is joint-aware triangle simplification, not quad retopology.
 * The caller must validate the output against geometric intersections.
 */
fun simplifySurface(mesh:Mesh, maxDisplacement:Float=.015f, passes:Int=3,
                    protected:(Vec3)->Boolean={false},checkpoint:()->Unit={}):Mesh {
    require(maxDisplacement.isFinite() && maxDisplacement>0f && passes in 1..8)
    val vertices=mesh.vertices.toMutableList();val colors=mesh.colors.toMutableList()
    val radius=FloatArray(vertices.size)
    var faces=List(mesh.indices.size/3){i->IntArray(3){mesh.indices[i*3+it]}}
    fun length(v:Vec3)=sqrt(v.dot(v))
    fun normal(f:IntArray, replacement:Int=-1,other:Int=-1,p:Vec3=Vec3(0f,0f,0f)):Vec3 {
        fun at(i:Int)=if(i==replacement || i==other) p else vertices[i]
        return (at(f[1])-at(f[0])).cross(at(f[2])-at(f[0]))
    }
    repeat(passes) {
        checkpoint()
        val incident=Array(vertices.size){mutableListOf<Int>()}
        val neighbors=Array(vertices.size){mutableSetOf<Int>()}
        val normals=Array(faces.size){i->normal(faces[i])}
        for((i,f) in faces.withIndex()) {
            if(i%1024==0) checkpoint()
            for(j in 0..2) {
                incident[f[j]].add(i)
                neighbors[f[j]].add(f[(j+1)%3]);neighbors[f[j]].add(f[(j+2)%3])
            }
        }
        // A face normal cone protects corners, creases and narrow high-curvature features.
        val flat=BooleanArray(vertices.size){v->
            val ring=incident[v]
            if(ring.isEmpty()) false else {
                val reference=normals[ring.first()].unit()
                ring.all { f->normals[f].dot(normals[f])>1e-20f && normals[f].unit().dot(reference)>=.97f }
            }
        }
        val protectedVertex=BooleanArray(vertices.size){protected(vertices[it])}
        val protectedRing=BooleanArray(vertices.size){v->
            protectedVertex[v] || neighbors[v].any{protectedVertex[it]}
        }
        val candidates=mutableListOf<Triple<Float,Int,Int>>()
        for(a in vertices.indices) {
            if(a%1024==0) checkpoint()
            if(!flat[a] || protectedRing[a]) continue
            for(b in neighbors[a]) if(b>a && flat[b] && !protectedRing[b]) {
                val distance=length(vertices[a]-vertices[b])
                if(distance*.5f+maxOf(radius[a],radius[b])<=maxDisplacement &&
                    length(colors[a]-colors[b])<=.08f) candidates.add(Triple(distance,a,b))
            }
        }
        candidates.sortWith(compareBy<Triple<Float,Int,Int>>{it.first}.thenBy{it.second}.thenBy{it.third})
        val blocked=BooleanArray(vertices.size);val remap=IntArray(vertices.size){it}
        var count=0
        for((_,a,b) in candidates) {
            if(count%256==0) checkpoint()
            if(blocked[a] || blocked[b]) continue
            val shared=neighbors[a].intersect(neighbors[b])
            val edgeFaces=incident[a].filter{b in faces[it]}
            if(shared.size!=2 || edgeFaces.size!=2) continue
            if(edgeFaces.flatMap{faces[it].toList()}.filter{it!=a && it!=b}.toSet()!=shared) continue
            // Full link condition: two common neighbors alone is insufficient for a tetrahedron.
            if(incident[a].any{f->shared.all{it in faces[f]}} &&
                incident[b].any{f->shared.all{it in faces[f]}}) continue
            val midpoint=(vertices[a]+vertices[b])*.5f
            if(protected(midpoint)) continue
            val newRadius=maxOf(radius[a]+length(vertices[a]-midpoint),radius[b]+length(vertices[b]-midpoint))
            if(newRadius>maxDisplacement) continue
            val affected=(incident[a]+incident[b]).distinct().filter{it !in edgeFaces}
            if(affected.any { i->
                val before=normals[i];val after=normal(faces[i],a,b,midpoint)
                after.dot(after)<=1e-20f || before.unit().dot(after.unit())<.95f || length(after)<length(before)*.20f
            }) continue
            // Block the entire affected one-ring so batch edits do not interact.
            blocked[a]=true;blocked[b]=true
            for(v in neighbors[a]) blocked[v]=true
            for(v in neighbors[b]) blocked[v]=true
            vertices[a]=midpoint;colors[a]=(colors[a]+colors[b])*.5f;radius[a]=newRadius
            remap[b]=a;count++
        }
        if(count==0) return@repeat
        faces=faces.mapNotNull{f->
            val a=remap[f[0]];val b=remap[f[1]];val c=remap[f[2]]
            if(a==b || b==c || c==a) null else intArrayOf(a,b,c)
        }
    }
    if(faces.size*3==mesh.indices.size) return mesh
    val used=BooleanArray(vertices.size);faces.forEach{f->f.forEach{used[it]=true}}
    val remap=IntArray(vertices.size){-1};val compact=mutableListOf<Vec3>();val rgb=mutableListOf<Vec3>()
    for(i in vertices.indices) if(used[i]) {remap[i]=compact.size;compact.add(vertices[i]);rgb.add(colors[i])}
    return Mesh(compact,rgb,IntArray(faces.size*3){remap[faces[it/3][it%3]]})
}
