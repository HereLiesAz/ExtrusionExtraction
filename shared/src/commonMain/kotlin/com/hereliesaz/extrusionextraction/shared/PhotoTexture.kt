package com.hereliesaz.extrusionextraction.shared

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Invert the first-camera normalization applied to the fused mesh. */
class ReconstructionFrame(private val first:CameraView,private val offset:Vec3,
                          val height:Float,val voxelSpacing:Float) {
    init { require(height.isFinite() && height>0f && voxelSpacing.isFinite() && voxelSpacing>0f) }
    private fun inverseRotation(v:Vec3):Vec3 {
        val e=first.extrinsic
        return Vec3(e[0]*v.x+e[4]*v.y+e[8]*v.z,e[1]*v.x+e[5]*v.y+e[9]*v.z,e[2]*v.x+e[6]*v.y+e[10]*v.z)
    }
    /** Map normalized model position back to the shared DA3 world. */
    fun world(p:Vec3):Vec3 {
        val v=p*height+offset;val e=first.extrinsic
        return inverseRotation(Vec3(v.x-e[3],-v.y-e[7],-v.z-e[11]))
    }
    /** Rotate an upright model direction back to DA3 world without translation or scale. */
    fun direction(p:Vec3):Vec3 = inverseRotation(Vec3(p.x,-p.y,-p.z))
}

/** Keep calibration attached to the surface through downstream geometry processing. */
class ReconstructedSurface(val mesh:Mesh,val frame:ReconstructionFrame)

/** Depth-consistent, front-facing photographic sampling in linear RGB.
 * Select up to three views per triangle by the best observed score at its center
 * and corners. Every texel is independently checked again; missing samples fall
 * back to the atlas's interpolated vertex colors. No inferred texture is invented.
 */
class PhotoTexture(private val frame:ReconstructionFrame,private val views:List<CameraView>) {
    private data class Sample(val rgb:Vec3,val weight:Float)
    private var gains=FloatArray(views.size){1f}
    private fun sample(view:CameraView,world:Vec3,normal:Vec3):Sample? {
        val p=view.camera(world)
        if(!p.finite() || p.z<=0f) return null
        val e=view.extrinsic
        val n=Vec3(e[0]*normal.x+e[1]*normal.y+e[2]*normal.z,
            e[4]*normal.x+e[5]*normal.y+e[6]*normal.z,e[8]*normal.x+e[9]*normal.y+e[10]*normal.z)
        val facing=-n.dot(p)/sqrt(p.dot(p))
        if(!facing.isFinite() || facing<=.15f) return null
        val k=view.k
        val x=(k[0]*p.x+k[1]*p.y)/p.z+k[2];val y=k[4]*p.y/p.z+k[5]
        if(!x.isFinite() || !y.isFinite() || x<0f || y<0f || x>IMAGE_SIZE-1 || y>IMAGE_SIZE-1) return null
        val x0=floor(x).toInt();val y0=floor(y).toInt()
        val x1=minOf(x0+1,IMAGE_SIZE-1);val y1=minOf(y0+1,IMAGE_SIZE-1)
        val tx=x-x0;val ty=y-y0
        val tolerance=frame.voxelSpacing*2f
        var sum=0f;var coverage=0f;var rgb=Vec3(0f,0f,0f)
        fun tap(px:Int,py:Int,bilinear:Float) {
            if(bilinear<=0f) return
            val i=py*IMAGE_SIZE+px
            if(!view.reliable(i)) return
            val residual=abs(view.depth[i]-p.z)
            if(residual>=tolerance) return
            val w=bilinear*view.confidence[i].coerceIn(.01f,10f)*(1f-residual/tolerance)
            val c=view.image.argb[i]
            rgb=rgb+Vec3(linear(c ushr 16 and 255),linear(c ushr 8 and 255),linear(c and 255))*w
            sum+=w;coverage+=bilinear
        }
        tap(x0,y0,(1-tx)*(1-ty));tap(x1,y0,tx*(1-ty));tap(x0,y1,(1-tx)*ty);tap(x1,y1,tx*ty)
        if(coverage<.5f || sum<=0f) return null
        return Sample(rgb*(1f/sum),sum*facing*facing)
    }
    /** Estimate exposure from at most 2048 deterministically spaced face centers.
     * Only mutually visible depth-consistent, front-facing samples contribute.
     * Run once before atlas baking; subsequent sampling uses the fixed gains.
     */
    fun matchExposure(mesh:Mesh,checkpoint:()->Unit={}) {
        if(views.size<2) return
        val count=mesh.indices.size/3
        val observations=Array(views.size){Array(views.size){mutableListOf<Float>()}}
        val samples=minOf(count,2048)
        for(i in 0 until samples) {
            if(i%32==0) checkpoint()
            val face=(i.toLong()*count/samples).toInt()*3
            val a=mesh.vertices[mesh.indices[face]]
            val b=mesh.vertices[mesh.indices[face+1]]
            val c=mesh.vertices[mesh.indices[face+2]]
            val world=frame.world((a+b+c)*(1f/3f))
            val normal=frame.direction((b-a).cross(c-a).unit())
            val observed=views.map{sample(it,world,normal)}
            for(left in observed.indices) {
                val first=observed[left] ?: continue
                if(first.weight<.05f) continue
                for(right in left+1 until observed.size) {
                    val second=observed[right] ?: continue
                    if(second.weight<.05f) continue
                    exposureRatio(first.rgb,second.rgb)?.let{observations[left][right].add(it)}
                }
            }
        }
        val overlaps=mutableListOf<ExposureOverlap>()
        for(i in views.indices) for(j in i+1 until views.size)
            summarizeExposure(i,j,observations[i][j])?.let{overlaps.add(it)}
        gains=exposureGains(views.size,overlaps)
    }
    /** Prepare a bounded per-triangle sampler using original calibrated reference pixels. */
    fun forFace(a:Vec3,b:Vec3,c:Vec3):(Vec3)->Vec3? {
        val wa=frame.world(a);val wb=frame.world(b);val wc=frame.world(c)
        val normal=frame.direction((b-a).cross(c-a).unit())
        val center=(wa+wb+wc)*(1f/3f)
        val candidates=views.mapIndexedNotNull { index,view ->
            val score=listOf(center,wa,wb,wc).maxOf { sample(view,it,normal)?.weight ?: 0f }
            if(score>0f) index to score else null
        }.sortedByDescending{it.second}.take(3).map{it.first}
        return { p ->
            val world=frame.world(p)
            var sum=0f;var rgb=Vec3(0f,0f,0f)
            for(index in candidates) sample(views[index],world,normal)?.let { s -> rgb=rgb+s.rgb*(s.weight*gains[index]);sum+=s.weight }
            if(sum>0f) rgb*(1f/sum) else null
        }
    }
}
