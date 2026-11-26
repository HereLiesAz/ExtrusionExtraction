package com.hereliesaz.extrusionextraction.shared

import kotlin.math.abs
import kotlin.math.sqrt

/** A surface and its corresponding four-influence skin after conservative bend refinement. */
internal data class RefinedSkin(val mesh:Mesh,val rig:Rig,val adjustedVertices:Int,val splitEdges:Int)

/**
 * Reduce sampled linear-blend edge strain without moving rest vertices or joints.
 *
 * This is a bounded geometric postprocess of learned weights, not new inference.
 * At mixed vertices, transfer at most 0.10 mass between existing influences.
 * Accept a transfer only if every relevant single-subtree +/-90-degree rotation
 * (about each coordinate axis) has non-increasing incident squared edge strain
 * and worst incident strain, with a strict improvement in at least one pose.
 * Sequential updates make this a local descent on the original mesh.
 *
 * Then attempt conforming midpoint splits on long, high-gradient edges. Keep
 * at most four influences without meaningful mass loss; reject splits whose
 * child triangles increase the local worst strain in any sampled rotation.
 * The original rest surface, winding, colors and hierarchy are preserved.
 * Bounded work and triangle budgets are deliberate mobile/atlas constraints.
 * Multi-joint motion, volume loss, anatomy and self-collision are not certified.
 */
internal fun refineBends(mesh:Mesh,rig:Rig,maxTriangles:Int=Int.MAX_VALUE,
                         checkpoint:()->Unit={}):RefinedSkin {
    require(rig.indices.size==mesh.vertices.size*4 && rig.weights.size==rig.indices.size)
    require(rig.joints.isNotEmpty() && rig.joints.first().parent==-1)
    require(rig.joints.indices.drop(1).all{rig.joints[it].parent in 0 until it})
    checkpoint()
    val n=mesh.vertices.size
    val faceCount=mesh.indices.size/3
    // Avoid allocating large adjacency maps or imposing unbounded postprocess latency.
    if(n>50_000 || faceCount>100_000 || rig.joints.size>128 || rig.joints.size==1)
        return RefinedSkin(mesh,rig,0,0)
    val vertices=mesh.vertices.toMutableList()
    val colors=mesh.colors.toMutableList()
    val weights=ArrayList<FloatArray>(n)
    val ids=ArrayList<IntArray>(n)
    for(v in 0 until n) {
        if(v%1024==0) checkpoint()
        ids.add(rig.indices.copyOfRange(v*4,v*4+4))
        weights.add(rig.weights.copyOfRange(v*4,v*4+4))
        require(ids[v].all{it in rig.joints.indices} &&
            weights[v].all{it.isFinite() && it>=0f} && abs(weights[v].sum()-1f)<.001f)
    }
    val neighbors=Array(n){mutableSetOf<Int>()}
    val edges=linkedMapOf<Pair<Int,Int>,MutableList<Int>>()
    fun edge(a:Int,b:Int)=minOf(a,b) to maxOf(a,b)
    for(f in 0 until faceCount) {
        if(f%1024==0) checkpoint()
        for(k in 0..2) {
            val a=mesh.indices[f*3+k];val b=mesh.indices[f*3+(k+1)%3]
            require(a!=b)
            neighbors[a].add(b);neighbors[b].add(a)
            edges.getOrPut(edge(a,b)){mutableListOf()}.add(f)
        }
    }
    // Parent-first hierarchy permits bounded membership lookup.
    val descendant=Array(rig.joints.size){BooleanArray(rig.joints.size)}
    for(j in rig.joints.indices) {
        var a=j
        while(a>=0) { descendant[a][j]=true;a=rig.joints[a].parent }
    }
    fun influence(v:Int,j:Int,w:FloatArray=weights[v]):Float {
        var result=0f
        for(k in 0..3) if(descendant[j][ids[v][k]]) result+=w[k]
        return result.coerceIn(0f,1f)
    }
    fun relevant(vs:List<Int>):List<Int> = (1 until rig.joints.size).filter { j ->
        var low=1f;var high=0f
        for(v in vs) for(k in 0..3) if(weights[v][k]>0f) {
            val x=if(descendant[j][ids[v][k]]) 1f else 0f
            low=minOf(low,x);high=maxOf(high,x)
        }
        low!=high
    }
    fun pose(v:Int,j:Int,axis:Int,sign:Int,w:FloatArray=weights[v]):Vec3 {
        val p=vertices[v];val pivot=rig.joints[j].position;val d=p-pivot
        val turned=when(axis) {
            0->Vec3(d.x,-sign*d.z,sign*d.y)
            1->Vec3(sign*d.z,d.y,-sign*d.x)
            else->Vec3(-sign*d.y,sign*d.x,d.z)
        }
        return p+(pivot+turned-p)*influence(v,j,w)
    }
    fun strain(a:Vec3,b:Vec3,x:Vec3,y:Vec3):Double {
        val rest=a-b;val moved=x-y
        val den=rest.dot(rest).toDouble()
        if(den<=1e-16) return Double.POSITIVE_INFINITY
        return abs(sqrt(moved.dot(moved).toDouble()/den)-1.0)
    }
    var adjusted=0
    // Prioritize mixed vertices, then deterministic vertex index; no new support
    // is introduced and rigid vertices are untouched.
    val candidates=(0 until n).filter{v->weights[v].count{it>=.05f}>=2}
        .sortedWith(compareBy<Int>{weights[it].max()}.thenBy{it}).take(2048)
    for(v in candidates) {
        checkpoint()
        val near=neighbors[v].toList()
        if(near.isEmpty() || near.size>32) continue
        val w=weights[v]
        val slots=(0..3).filter{w[it]>=.05f}.sortedByDescending{w[it]}
        val joints=relevant(near+v)
        if(joints.isEmpty() || joints.size>16) continue
        val poses=joints.flatMap{j->(0..2).flatMap{axis->listOf(Triple(j,axis,-1),Triple(j,axis,1))}}
        val reference=poses.map{(j,axis,sign)->
            val points=near.map{pose(it,j,axis,sign)}
            val original=pose(v,j,axis,sign)
            val errors=near.indices.map{k->strain(vertices[v],vertices[near[k]],original,points[k])}
            Triple(points,errors.sumOf{it*it},errors.max())
        }
        var best:FloatArray?=null
        var bestGain=1e-7
        // Use the strongest two existing influences only; the 0.10 bound is
        // relative to the original weight, not a cumulative iterative drift.
        for((from,to) in listOf(slots[0] to slots[1],slots[1] to slots[0])) {
            for(amount in listOf(.05f,.10f)) {
                if(w[from]<amount+.00001f) continue
                val trial=w.copyOf()
                trial[from]-=amount;trial[to]+=amount
                var gain=0.0;var accepted=true
                for(i in poses.indices) {
                    val (j,axis,sign)=poses[i]
                    val (points,total,worst)=reference[i]
                    val moved=pose(v,j,axis,sign,trial)
                    val errors=near.indices.map{k->strain(vertices[v],vertices[near[k]],moved,points[k])}
                    val cost=errors.sumOf{it*it};val max=errors.max()
                    if(!cost.isFinite() || cost>total+1e-9 || max>worst+1e-9) {accepted=false;break}
                    gain+=total-cost
                }
                if(accepted && gain>bestGain){best=trial;bestGain=gain}
            }
        }
        if(best!=null){weights[v]=best;adjusted++}
    }
    // A single shared midpoint per edge; at most one selected edge per face.
    // Each split replaces two triangles with four without T junctions.
    val splitBudget=minOf(2048,faceCount/10,(maxTriangles-faceCount).coerceAtLeast(0)/2)
    val blocked=BooleanArray(faceCount)
    val splits=mutableMapOf<Pair<Int,Int>,Int>()
    fun gradient(a:Int,b:Int):Float {
        val union=(ids[a].toList()+ids[b].toList()).distinct()
        return union.sumOf{j->
            fun mass(v:Int)=(0..3).sumOf{k->if(ids[v][k]==j)weights[v][k].toDouble() else 0.0}
            abs(mass(a)-mass(b))
        }.toFloat()*.5f
    }
    val ranked=if(splitBudget==0) emptyList() else edges.entries.mapNotNull{(e,fs)->
        val delta=vertices[e.first]-vertices[e.second]
        val length=sqrt(delta.dot(delta))
        val variation=gradient(e.first,e.second)
        if(fs.size==2 && length>=.04f && variation>=.35f) Triple(e,fs,length*variation) else null
    }.sortedWith(compareByDescending<Triple<Pair<Int,Int>,MutableList<Int>,Float>>{it.third}
        .thenBy{it.first.first}.thenBy{it.first.second})
    for((e,fs,_) in ranked) {
        if(splits.size>=splitBudget) break
        checkpoint()
        if(fs.any{blocked[it]}) continue
        val (a,b)=e
        val opposite=fs.map{f->(0..2).map{mesh.indices[f*3+it]}.single{it!=a && it!=b}}
        if(opposite[0]==opposite[1]) continue
        val mix=mutableMapOf<Int,Float>()
        for(v in listOf(a,b)) for(k in 0..3) if(weights[v][k]>0f)
            mix[ids[v][k]]=(mix[ids[v][k]]?:0f)+weights[v][k]*.5f
        val top=mix.entries.sortedWith(compareByDescending<Map.Entry<Int,Float>>{it.value}.thenBy{it.key}).take(4)
        val mass=top.sumOf{it.value.toDouble()}.toFloat()
        if(mass<.999f) continue
        val m=vertices.size
        vertices.add((vertices[a]+vertices[b])*.5f);colors.add((colors[a]+colors[b])*.5f)
        ids.add(IntArray(4){top.getOrNull(it)?.key?:0})
        weights.add(FloatArray(4){(top.getOrNull(it)?.value?:0f)/mass})
        val local=listOf(a,b)+opposite
        val joints=relevant(local+m)
        var safe=joints.size<=16
        if(safe) outer@ for(j in joints) for(axis in 0..2) for(sign in listOf(-1,1)) {
            fun error(x:Int,y:Int)=strain(vertices[x],vertices[y],pose(x,j,axis,sign),pose(y,j,axis,sign))
            val before=maxOf(error(a,b),opposite.maxOf{maxOf(error(a,it),error(b,it))})
            val after=maxOf(error(a,m),error(m,b),opposite.maxOf{error(m,it)})
            if(!after.isFinite() || after>before+1e-6){safe=false;break@outer}
        }
        if(!safe) {
            vertices.removeAt(m);colors.removeAt(m);ids.removeAt(m);weights.removeAt(m)
            continue
        }
        splits[e]=m
        for(f in fs) blocked[f]=true
    }
    if(adjusted==0 && splits.isEmpty()) return RefinedSkin(mesh,rig,0,0)
    val indices=IntArray(mesh.indices.size+splits.size*6)
    var at=0
    fun face(a:Int,b:Int,c:Int){indices[at++]=a;indices[at++]=b;indices[at++]=c}
    for(f in 0 until faceCount) {
        if(f%1024==0) checkpoint()
        var divided=false
        for(k in 0..2) {
            val a=mesh.indices[f*3+k];val b=mesh.indices[f*3+(k+1)%3]
            val m=splits[edge(a,b)]?:continue
            val c=mesh.indices[f*3+(k+2)%3]
            face(a,m,c);face(m,b,c);divided=true;break
        }
        if(!divided) face(mesh.indices[f*3],mesh.indices[f*3+1],mesh.indices[f*3+2])
    }
    check(at==indices.size)
    val result=if(splits.isEmpty()) mesh else Mesh(vertices,colors,indices)
    val resultRig=Rig(rig.joints,IntArray(vertices.size*4){ids[it/4][it%4]},
        FloatArray(vertices.size*4){weights[it/4][it%4]})
    return RefinedSkin(result,resultRig,adjusted,splits.size)
}
