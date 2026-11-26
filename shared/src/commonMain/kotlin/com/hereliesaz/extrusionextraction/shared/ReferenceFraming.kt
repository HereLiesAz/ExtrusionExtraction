package com.hereliesaz.extrusionextraction.shared

import kotlin.math.floor
import kotlin.math.roundToInt

/** Half-open area containing original image samples on the square inference grid. */
data class ReferenceBounds(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    init { require(left>=0 && top>=0 && right in left+1..IMAGE_SIZE && bottom in top+1..IMAGE_SIZE) }
    fun contains(x:Int,y:Int):Boolean = x in left until right && y in top until bottom
}

/** Fit decoded pixels without stretching the subject. Non-square images get a
 * small centered margin. Interpolation is alpha-premultiplied to avoid dark
 * halos around cutouts; transparent padding is tracked independently of source
 * transparency so it cannot carve geometry or suppress foreground inference.
 */
fun fitReferenceImage(name:String,width:Int,height:Int,argb:IntArray):ViewImage {
    require(width>0 && height>0 && width.toLong()*height==argb.size.toLong()) { "Invalid reference dimensions." }
    val masked=argb.any { it.ushr(24)<128 }
    val clipped=masked && (
        (0 until width).any { argb[it].ushr(24)>=128 || argb[(height-1)*width+it].ushr(24)>=128 } ||
        (0 until height).any { argb[it*width].ushr(24)>=128 || argb[it*width+width-1].ushr(24)>=128 }
    )
    val span=if(width==height) IMAGE_SIZE else IMAGE_SIZE-48
    val scale=span.toDouble()/maxOf(width,height)
    val w=(width*scale).roundToInt().coerceIn(1,IMAGE_SIZE)
    val h=(height*scale).roundToInt().coerceIn(1,IMAGE_SIZE)
    val left=(IMAGE_SIZE-w)/2;val top=(IMAGE_SIZE-h)/2
    val bounds=ReferenceBounds(left,top,left+w,top+h)
    val pixels=IntArray(IMAGE_SIZE*IMAGE_SIZE)
    for(y in 0 until h) {
        val sy=((y+.5)*height/h-.5).coerceIn(0.0,(height-1).toDouble())
        val y0=floor(sy).toInt();val y1=minOf(y0+1,height-1);val fy=sy-y0
        for(x in 0 until w) {
            val sx=((x+.5)*width/w-.5).coerceIn(0.0,(width-1).toDouble())
            val x0=floor(sx).toInt();val x1=minOf(x0+1,width-1);val fx=sx-x0
            val p00=argb[y0*width+x0];val p10=argb[y0*width+x1]
            val p01=argb[y1*width+x0];val p11=argb[y1*width+x1]
            val a00=p00.ushr(24)*(1-fx)*(1-fy);val a10=p10.ushr(24)*fx*(1-fy)
            val a01=p01.ushr(24)*(1-fx)*fy;val a11=p11.ushr(24)*fx*fy
            val alpha=a00+a10+a01+a11
            val a=alpha.roundToInt().coerceIn(0,255)
            if(a==0) continue
            fun channel(shift:Int):Int = (((p00.ushr(shift) and 255)*a00+(p10.ushr(shift) and 255)*a10+
                (p01.ushr(shift) and 255)*a01+(p11.ushr(shift) and 255)*a11)/alpha).roundToInt().coerceIn(0,255)
            pixels[(top+y)*IMAGE_SIZE+left+x]=(a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
    return ViewImage(name,pixels,bounds,masked,clipped)
}

/** Review candidates only: disconnected alpha islands touching the source crop
 * and no larger than 2% of the largest component. Never removed automatically.
 */
internal class EdgeFragments(val count:Int,val indices:IntArray)

internal fun findEdgeFragments(image:ViewImage,checkpoint:()->Unit={}):EdgeFragments {
    if(!image.sourceMask) return EdgeFragments(0,IntArray(0))
    val seen=BooleanArray(image.argb.size)
    val queue=IntArray(image.argb.size)
    val candidates=mutableListOf<IntArray>()
    val bounds=image.sourceBounds
    var largest=0
    for(start in image.argb.indices) {
        if(start%4096==0) checkpoint()
        if(seen[start] || !image.observed(start) || image.argb[start].ushr(24)==0) continue
        var head=0;var tail=1;var edge=false
        queue[0]=start;seen[start]=true
        while(head<tail) {
            if(head%4096==0) checkpoint()
            val p=queue[head++];val x=p%IMAGE_SIZE;val y=p/IMAGE_SIZE
            if(x==bounds.left || x==bounds.right-1 || y==bounds.top || y==bounds.bottom-1) edge=true
            for(dy in -1..1) for(dx in -1..1) {
                if(dx==0 && dy==0) continue
                val nx=x+dx;val ny=y+dy
                if(!bounds.contains(nx,ny)) continue
                val next=ny*IMAGE_SIZE+nx
                if(seen[next] || image.argb[next].ushr(24)==0) continue
                seen[next]=true;queue[tail++]=next
            }
        }
        largest=maxOf(largest,tail)
        if(edge) candidates.add(queue.copyOf(tail))
    }
    val small=candidates.filter { it.size.toLong()*50<=largest.toLong() }
    return EdgeFragments(small.size,small.flatMap { it.asIterable() }.toIntArray())
}

/** Apply only the reviewed candidates, leaving all other source pixels unchanged. */
internal fun removeEdgeFragments(image:ViewImage,fragments:EdgeFragments):ViewImage {
    if(fragments.count==0) return image
    val pixels=image.argb.copyOf()
    for(index in fragments.indices) pixels[index]=0
    return ViewImage(image.name,pixels,image.sourceBounds,image.sourceMask,image.croppedForeground)
}
