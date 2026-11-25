package com.hereliesaz.extrusionextraction.shared

import kotlin.math.sqrt

private data class Point(val x:Double,val y:Double,val z:Double) {
    operator fun minus(b:Point)=Point(x-b.x,y-b.y,z-b.z)
    fun dot(b:Point)=x*b.x+y*b.y+z*b.z
    fun cross(b:Point)=Point(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
    fun axis(i:Int)=when(i){0->x;1->y;else->z}
}
private class Bounds(val low:Point,val high:Point) {
    fun overlaps(b:Bounds,e:Double)= (0..2).all {
        low.axis(it)<=b.high.axis(it)+e && b.low.axis(it)<=high.axis(it)+e
    }
    fun union(b:Bounds)=Bounds(Point(minOf(low.x,b.low.x),minOf(low.y,b.low.y),minOf(low.z,b.low.z)),
        Point(maxOf(high.x,b.high.x),maxOf(high.y,b.high.y),maxOf(high.z,b.high.z)))
    fun center(axis:Int)=low.axis(axis)+(high.axis(axis)-low.axis(axis))/2
}
private class Triangle(val id:Int,val ids:IntArray,val points:Array<Point>) {
    val edges=Array(3){points[(it+1)%3]-points[it]}
    val normal=edges[0].cross(edges[1])
    val bounds=Bounds(Point(points.minOf{it.x},points.minOf{it.y},points.minOf{it.z}),
        Point(points.maxOf{it.x},points.maxOf{it.y},points.maxOf{it.z}))
}
private class Node(val bounds:Bounds,val start:Int,val end:Int,val left:Node?=null,val right:Node?=null)

/** Triangle separating-axis test. Extra in-plane axes handle coplanar triangles.
 * Project relative to a local origin in double precision; contact counts as overlap.
 */
private fun intersects(a:Triangle,b:Triangle,tolerance:Double):Boolean {
    val origin=a.points[0]
    fun separates(axis:Point):Boolean {
        val length=sqrt(axis.dot(axis))
        if(length==0.0) return false
        val normal=Point(axis.x/length,axis.y/length,axis.z/length)
        var amin=Double.POSITIVE_INFINITY;var amax=Double.NEGATIVE_INFINITY
        var bmin=Double.POSITIVE_INFINITY;var bmax=Double.NEGATIVE_INFINITY
        for(i in 0..2) {
            val av=(a.points[i]-origin).dot(normal);val bv=(b.points[i]-origin).dot(normal)
            amin=minOf(amin,av);amax=maxOf(amax,av);bmin=minOf(bmin,bv);bmax=maxOf(bmax,bv)
        }
        return amax<bmin-tolerance || bmax<amin-tolerance
    }
    if(separates(a.normal)||separates(b.normal)) return false
    for(e in a.edges) {
        if(separates(a.normal.cross(e))) return false
        for(f in b.edges) if(separates(e.cross(f))) return false
    }
    for(f in b.edges) if(separates(b.normal.cross(f))) return false
    return true
}

/** First intersecting pair of triangle indices (zero-based), or null if none found.
 * A median-split bounding-volume hierarchy prunes distant pairs. Each unordered
 * pair is tested at most once. Contact between faces with no shared vertex is
 * treated as invalid; pairs sharing a vertex are deliberately excluded because
 * their intended topological contact needs a separate adjacency-aware test.
 * The scale-relative tolerance is conservative; this is not an exact predicate.
 */
fun firstNonAdjacentIntersection(mesh:Mesh,checkpoint:()->Unit={}):Pair<Int,Int>? {
    val points=mesh.vertices.map{Point(it.x.toDouble(),it.y.toDouble(),it.z.toDouble())}
    val triangles=Array(mesh.indices.size/3){i->
        val ids=IntArray(3){mesh.indices[i*3+it]}
        Triangle(i,ids,Array(3){points[ids[it]]})
    }
    fun build(start:Int,end:Int):Node {
        checkpoint()
        var bounds=triangles[start].bounds
        for(i in start+1 until end) bounds=bounds.union(triangles[i].bounds)
        if(end-start<=8) return Node(bounds,start,end)
        val axis=(0..2).maxBy{bounds.high.axis(it)-bounds.low.axis(it)}
        triangles.sortWith(compareBy<Triangle>{it.bounds.center(axis)}.thenBy{it.id},start,end)
        val mid=(start+end)/2
        return Node(bounds,start,end,build(start,mid),build(mid,end))
    }
    val root=build(0,triangles.size)
    val span=root.bounds.high-root.bounds.low
    val tolerance=sqrt(span.dot(span))*1e-9
    fun visit(a:Triangle,node:Node):Int? {
        if(!a.bounds.overlaps(node.bounds,tolerance)) return null
        val left=node.left
        if(left!=null) return visit(a,left) ?: visit(a,checkNotNull(node.right))
        for(i in node.start until node.end) {
            val b=triangles[i]
            if(b.id<=a.id || a.ids.any{it in b.ids} || !a.bounds.overlaps(b.bounds,tolerance)) continue
            if(intersects(a,b,tolerance)) return b.id
        }
        return null
    }
    for(a in triangles) {
        checkpoint()
        val b=visit(a,root)
        if(b!=null) return a.id to b
    }
    return null
}
