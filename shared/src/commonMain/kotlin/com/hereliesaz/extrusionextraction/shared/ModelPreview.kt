package com.hereliesaz.extrusionextraction.shared

import kotlin.math.*

/** Orthographic, depth-tested preview of the exact exported mesh and UV atlas.
 * Wire edges use projected pixel distances; the rig is an X-ray overlay.
 * A missing atlas uses the mesh's linear vertex colors rather than inventing a texture.
 */
internal fun renderModelPreview(model:GeneratedModel,texturePixels:IntArray?,yaw:Float,pitch:Float,
                                textured:Boolean,wire:Boolean,skeleton:Boolean,checkpoint:()->Unit={}):ViewImage {
    val mesh=model.mesh
    val n=IMAGE_SIZE
    val pixels=IntArray(n*n){0xff202428.toInt()}
    val depth=FloatArray(n*n){Float.NEGATIVE_INFINITY}
    val center=Vec3((mesh.vertices.minOf{it.x}+mesh.vertices.maxOf{it.x})/2,
        (mesh.vertices.minOf{it.y}+mesh.vertices.maxOf{it.y})/2,
        (mesh.vertices.minOf{it.z}+mesh.vertices.maxOf{it.z})/2)
    val extent=mesh.vertices.maxOf{val p=it-center;sqrt(p.dot(p))}.coerceAtLeast(.001f)
    val scale=n*.43f/extent
    val cy=cos(yaw);val sy=sin(yaw);val cp=cos(pitch);val sp=sin(pitch)
    fun project(v:Vec3):Vec3 {
        val p=v-center
        val x=p.x*cy+p.z*sy;val z=-p.x*sy+p.z*cy
        return Vec3(n/2f+x*scale,n/2f-(p.y*cp-z*sp)*scale,p.y*sp+z*cp)
    }
    val projected=mesh.vertices.map(::project)
    val atlas=model.atlas
    val sample=texturePixels?.takeIf { atlas!=null && it.size==atlas.size*atlas.size }
    fun edge(a:Vec3,b:Vec3,x:Float,y:Float)=(b.x-a.x)*(y-a.y)-(b.y-a.y)*(x-a.x)
    fun length(a:Vec3,b:Vec3)=sqrt((a.x-b.x).pow(2)+(a.y-b.y).pow(2)).coerceAtLeast(.001f)
    fun rgb(c:Vec3):Int {
        fun component(v:Float):Int {
            val x=v.coerceIn(0f,1f)
            return ((if(x<=.0031308f) 12.92f*x else 1.055f*x.pow(1f/2.4f)-.055f)*255).roundToInt()
        }
        return 0xff000000.toInt() or (component(c.x) shl 16) or (component(c.y) shl 8) or component(c.z)
    }
    for(i in mesh.indices.indices step 3) {
        if(i%768==0) checkpoint()
        val ia=mesh.indices[i];val ib=mesh.indices[i+1];val ic=mesh.indices[i+2]
        val a=projected[ia];val b=projected[ib];val c=projected[ic]
        val area=edge(a,b,c.x,c.y)
        if(abs(area)<.0001f) continue
        val minX=floor(minOf(a.x,b.x,c.x)).toInt().coerceIn(0,n-1)
        val maxX=ceil(maxOf(a.x,b.x,c.x)).toInt().coerceIn(0,n-1)
        val minY=floor(minOf(a.y,b.y,c.y)).toInt().coerceIn(0,n-1)
        val maxY=ceil(maxOf(a.y,b.y,c.y)).toInt().coerceIn(0,n-1)
        val ab=length(a,b);val bc=length(b,c);val ca=length(c,a)
        val caColor=mesh.colors[ia];val cbColor=mesh.colors[ib];val ccColor=mesh.colors[ic]
        for(y in minY..maxY) for(x in minX..maxX) {
            val px=x+.5f;val py=y+.5f
            val ea=edge(b,c,px,py);val eb=edge(c,a,px,py);val ec=edge(a,b,px,py)
            val u=ea/area;val v=eb/area;val w=ec/area
            if(u<0f || v<0f || w<0f) continue
            val d=a.z*u+b.z*v+c.z*w;val index=y*n+x
            if(d<depth[index]) continue
            depth[index]=d
            val onEdge=minOf(abs(ea)/bc,abs(eb)/ca,abs(ec)/ab)<.9f
            pixels[index]=when {
                wire && onEdge -> 0xff86e1d2.toInt()
                textured && atlas!=null && sample!=null -> {
                    val off=i*2
                    val tx=((atlas.uv[off]*u+atlas.uv[off+2]*v+atlas.uv[off+4]*w)*atlas.size).toInt().coerceIn(0,atlas.size-1)
                    val ty=((atlas.uv[off+1]*u+atlas.uv[off+3]*v+atlas.uv[off+5]*w)*atlas.size).toInt().coerceIn(0,atlas.size-1)
                    sample[ty*atlas.size+tx]
                }
                textured -> rgb(caColor*u+cbColor*v+ccColor*w)
                wire -> 0xff202428.toInt()
                else -> 0xffa3a9af.toInt()
            }
        }
    }
    if(skeleton) model.rig?.let { rig ->
        val joints=rig.joints.map { project(it.position) }
        fun point(x:Int,y:Int,r:Int) {
            for(dy in -r..r) for(dx in -r..r) if(dx*dx+dy*dy<=r*r && x+dx in 0 until n && y+dy in 0 until n)
                pixels[(y+dy)*n+x+dx]=0xffffb74d.toInt()
        }
        for((i,joint) in rig.joints.withIndex()) {
            val a=joints[i]
            if(joint.parent>=0) {
                val b=joints[joint.parent];val count=maxOf(abs(a.x-b.x),abs(a.y-b.y)).roundToInt().coerceAtLeast(1)
                for(k in 0..count) { val t=k.toFloat()/count;point((a.x+(b.x-a.x)*t).roundToInt(),(a.y+(b.y-a.y)*t).roundToInt(),1) }
            }
            point(a.x.roundToInt(),a.y.roundToInt(),3)
        }
    }
    return ViewImage("Model preview",pixels)
}
