package com.hereliesaz.extrusionextraction.shared

import kotlin.math.*

/** A right-handed three-dimensional vector. Camera coordinates use positive depth. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(v: Vec3) = Vec3(x+v.x,y+v.y,z+v.z)
    operator fun minus(v: Vec3) = Vec3(x-v.x,y-v.y,z-v.z)
    operator fun times(s: Float) = Vec3(x*s,y*s,z*s)
    fun dot(v: Vec3) = x*v.x+y*v.y+z*v.z
    fun cross(v: Vec3) = Vec3(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
    fun unit(): Vec3 { val d=sqrt(dot(this)); return if(d>1e-10f) this*(1f/d) else Vec3(0f,1f,0f) }
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
}

/** Input image resampled onto the inference grid; ARGB alpha excludes transparent background. */
class ViewImage(val name: String, val argb: IntArray) {
    init { require(argb.size == IMAGE_SIZE * IMAGE_SIZE) }
}
const val IMAGE_SIZE = 504
const val VIEW_COUNT = 4

/** DA3 depth, confidence, intrinsics and world-to-camera matrix for one input view. */
class CameraView(val image: ViewImage, val depth: FloatArray, val confidence: FloatArray,
                 val k: FloatArray, val extrinsic: FloatArray) {
    init {
        require(depth.size == IMAGE_SIZE*IMAGE_SIZE && confidence.size == depth.size)
        require(k.size == 9 && extrinsic.size == 12 && k.all { it.isFinite() } && extrinsic.all { it.isFinite() })
        require(k[0]>0 && k[4]>0)
    }
    fun camera(world: Vec3) = Vec3(
        extrinsic[0]*world.x+extrinsic[1]*world.y+extrinsic[2]*world.z+extrinsic[3],
        extrinsic[4]*world.x+extrinsic[5]*world.y+extrinsic[6]*world.z+extrinsic[7],
        extrinsic[8]*world.x+extrinsic[9]*world.y+extrinsic[10]*world.z+extrinsic[11])
    fun world(x: Int,y: Int,d: Float): Vec3 {
        val yy=(y-k[5])*d/k[4]
        val xx=((x-k[2])*d-k[1]*yy)/k[0]
        val a=xx-extrinsic[3]; val b=yy-extrinsic[7]; val c=d-extrinsic[11]
        return Vec3(extrinsic[0]*a+extrinsic[4]*b+extrinsic[8]*c,
            extrinsic[1]*a+extrinsic[5]*b+extrinsic[9]*c,
            extrinsic[2]*a+extrinsic[6]*b+extrinsic[10]*c)
    }
    fun foreground(i: Int) = image.argb[i].ushr(24)>127
    fun reliable(i: Int) = foreground(i) && depth[i].isFinite() && depth[i]>0f &&
        confidence[i].isFinite() && confidence[i]>0f
    fun pixel(p: Vec3): Int {
        if(p.z<=0f || !p.finite()) return -1
        val x=((k[0]*p.x+k[1]*p.y)/p.z+k[2]).roundToInt()
        val y=(k[4]*p.y/p.z+k[5]).roundToInt()
        return if(x in 0 until IMAGE_SIZE && y in 0 until IMAGE_SIZE) y*IMAGE_SIZE+x else -1
    }
}

/** Indexed triangular mesh with linear RGB vertex colors. */
class Mesh(val vertices: List<Vec3>, val colors: List<Vec3>, val indices: IntArray) {
    init { require(vertices.isNotEmpty() && vertices.size==colors.size && indices.isNotEmpty() &&
        indices.size%3==0 && indices.all { it in vertices.indices } &&
        vertices.all { it.finite() } && colors.all { it.finite() }) }
}
internal fun linear(c: Int): Float { val v=c/255f; return if(v<=.04045f) v/12.92f else ((v+.055f)/1.055f).pow(2.4f) }

/** Confidence-weighted projective TSDF fusion, with transparent silhouette carving.
 * A bounded voxel grid controls memory use equally on Android and desktop. Marching
 * tetrahedra shares indexed edge vertices, avoiding per-view disconnected surfaces.
 * Unknown space is not treated as observed occupied volume.
 * The returned calibration frame retains the exact inverse of mesh normalization.
 */
fun fuse(views: List<CameraView>, resolution: Int = 72, checkpoint: () -> Unit = {}): ReconstructedSurface {
    require(views.size in VIEW_COUNT..MAX_REFERENCES && resolution in 32..128)
    val samples=mutableListOf<Vec3>()
    for(v in views) for(y in 0 until IMAGE_SIZE step 8) for(x in 0 until IMAGE_SIZE step 8) {
        val i=y*IMAGE_SIZE+x
        if(v.reliable(i)) v.world(x,y,v.depth[i]).let { if(it.finite()) samples.add(it) }
    }
    require(samples.size>=32) { "Insufficient valid foreground depth." }
    fun limits(values: List<Float>): Pair<Float,Float> {
        val s=values.sorted()
        return s[s.size/100] to s[s.size-1-s.size/100]
    }
    val (lx,hx)=limits(samples.map { it.x }); val (ly,hy)=limits(samples.map { it.y }); val (lz,hz)=limits(samples.map { it.z })
    val span=max(hx-lx,max(hy-ly,hz-lz))
    require(span.isFinite() && span>1e-6f) { "Degenerate reconstruction bounds." }
    val n=resolution+1
    val spacing=span/(resolution-6)
    val center=Vec3((lx+hx)/2,(ly+hy)/2,(lz+hz)/2)
    val origin=center-Vec3(1f,1f,1f)*(spacing*resolution/2)
    val trunc=spacing*3
    val field=FloatArray(n*n*n){1f}
    fun index(x:Int,y:Int,z:Int)=(z*n+y)*n+x
    fun point(i:Int)=origin+Vec3((i%n).toFloat(),(i/n%n).toFloat(),(i/n/n).toFloat())*spacing
    for(z in 1 until resolution) {
        checkpoint()
        for(y in 1 until resolution) for(x in 1 until resolution) {
            val i=index(x,y,z); val world=point(i)
            var sum=0f; var weight=0f; var carved=false
            for(v in views) {
                val camera=v.camera(world); val px=v.pixel(camera)
                if(px<0) continue
                if(!v.foreground(px)) { carved=true; break }
                if(!v.reliable(px)) continue
                val sdf=v.depth[px]-camera.z
                if(sdf < -trunc) continue
                val w=v.confidence[px].coerceIn(.01f,10f)
                sum+=(sdf/trunc).coerceIn(-1f,1f)*w
                weight+=w
            }
            if(!carved && weight>0f) field[i]=sum/weight
        }
    }
    val vertices=mutableListOf<Vec3>(); val colors=mutableListOf<Vec3>(); val triangles=mutableListOf<Int>()
    val cache=mutableMapOf<Long,Int>()
    fun color(world:Vec3):Vec3 {
        var best=Float.POSITIVE_INFINITY; var rgb=0xffaaaaaa.toInt()
        for(v in views) {
            val c=v.camera(world); val px=v.pixel(c)
            if(px>=0 && v.reliable(px)) {
                val err=abs(v.depth[px]-c.z)
                if(err<best) { best=err; rgb=v.image.argb[px] }
            }
        }
        return Vec3(linear(rgb ushr 16 and 255),linear(rgb ushr 8 and 255),linear(rgb and 255))
    }
    fun edge(a:Int,b:Int):Int {
        val low=min(a,b); val high=max(a,b); val key=(low.toLong() shl 32) or high.toLong()
        return cache.getOrPut(key) {
            val t=(field[a]/(field[a]-field[b])).coerceIn(.0001f,.9999f)
            val world=point(a)+(point(b)-point(a))*t
            val v=views.first().camera(world)
            vertices.add(Vec3(v.x,-v.y,-v.z)); colors.add(color(world)); vertices.lastIndex
        }
    }
    // The same body diagonal in every cube ensures shared-face triangulation.
    val tetra=arrayOf(intArrayOf(0,5,1,6),intArrayOf(0,1,2,6),intArrayOf(0,2,3,6),
        intArrayOf(0,3,7,6),intArrayOf(0,7,4,6),intArrayOf(0,4,5,6))
    fun triangle(a:Int,b:Int,c:Int,out:Vec3) {
        val normal=(vertices[b]-vertices[a]).cross(vertices[c]-vertices[a])
        if(normal.dot(normal)<1e-20f) return
        val camera=views.first()
        val direction=camera.camera(out)-camera.camera(Vec3(0f,0f,0f))
        val outward=Vec3(direction.x,-direction.y,-direction.z)
        triangles.add(a)
        if(normal.dot(outward)>=0) { triangles.add(b);triangles.add(c) }
        else { triangles.add(c);triangles.add(b) }
    }
    for(z in 0 until resolution) {
        checkpoint()
        for(y in 0 until resolution) for(x in 0 until resolution) {
            val corners=intArrayOf(index(x,y,z),index(x+1,y,z),index(x+1,y+1,z),index(x,y+1,z),
                index(x,y,z+1),index(x+1,y,z+1),index(x+1,y+1,z+1),index(x,y+1,z+1))
            if(corners.all { field[it]>=0 } || corners.all { field[it]<0 }) continue
            for(t in tetra) {
                val inside=t.map { corners[it] }.filter { field[it]<0 }
                val outside=t.map { corners[it] }.filter { field[it]>=0 }
                if(inside.isEmpty() || outside.isEmpty()) continue
                val out=outside.map(::point).reduce(Vec3::plus)*(1f/outside.size)-
                    inside.map(::point).reduce(Vec3::plus)*(1f/inside.size)
                if(inside.size==1) triangle(edge(inside[0],outside[0]),edge(inside[0],outside[1]),edge(inside[0],outside[2]),out)
                else if(outside.size==1) triangle(edge(inside[0],outside[0]),edge(inside[1],outside[0]),edge(inside[2],outside[0]),out)
                else {
                    val a=edge(inside[0],outside[0]); val b=edge(inside[0],outside[1])
                    val c=edge(inside[1],outside[1]); val d=edge(inside[1],outside[0])
                    triangle(a,b,c,out); triangle(a,c,d,out)
                }
            }
        }
    }
    require(triangles.isNotEmpty()) { "No surface recovered. Use overlapping, isolated reference views." }
    val minY=vertices.minOf { it.y }; val height=vertices.maxOf { it.y }-minY
    require(height>1e-6f)
    val midX=(vertices.minOf { it.x }+vertices.maxOf { it.x })/2
    val midZ=(vertices.minOf { it.z }+vertices.maxOf { it.z })/2
    val offset=Vec3(midX,minY,midZ)
    val mesh=Mesh(vertices.map { (it-offset)*(1f/height) },colors,triangles.toIntArray())
    return ReconstructedSurface(mesh,ReconstructionFrame(views.first(),offset,height,spacing))
}
