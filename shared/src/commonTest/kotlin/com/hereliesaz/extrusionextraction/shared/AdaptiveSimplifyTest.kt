package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class AdaptiveSimplifyTest {
    /** Closed, outward-oriented box with a planar 5x5 top grid and sharp rim. */
    private fun box():Mesh {
        val v=mutableListOf<Vec3>();val f=mutableListOf<Int>()
        fun face(a:Int,b:Int,c:Int){f.add(a);f.add(b);f.add(c)}
        for(y in 0..4) for(x in 0..4) v.add(Vec3(x*.02f,y*.02f,1f))
        for(y in 0..3) for(x in 0..3) {val a=y*5+x;face(a,a+1,a+6);face(a,a+6,a+5)}
        val rim=listOf(0,1,2,3,4,9,14,19,24,23,22,21,20,15,10,5)
        for(i in rim) {val p=v[i];v.add(Vec3(p.x,p.y,0f))}
        val center=v.size;v.add(Vec3(.04f,.04f,0f))
        for(j in rim.indices) {
            val k=(j+1)%rim.size;face(rim[k],rim[j],25+j);face(rim[k],25+j,25+k);face(center,25+k,25+j)
        }
        return Mesh(v,List(v.size){Vec3(.4f,.4f,.4f)},f.toIntArray())
    }
    private fun assertClosed(m:Mesh) {
        val edges=mutableMapOf<Pair<Int,Int>,Int>()
        for(i in m.indices.indices step 3) for(j in 0..2) {
            val a=m.indices[i+j];val b=m.indices[i+(j+1)%3];val key=minOf(a,b) to maxOf(a,b)
            edges[key]=(edges[key]?:0)+1
        }
        assertTrue(edges.values.all{it==2})
        assertEquals(2,m.vertices.size-edges.size+m.indices.size/3)
    }
    @Test fun reducesPlanarDensityPreservingTopologyAndCorners() {
        val original=box();assertClosed(original)
        val result=simplifySurface(original);assertClosed(result)
        assertEquals(80,original.indices.size/3)
        assertTrue(result.indices.size<original.indices.size)
        assertTrue(result.vertices.size<original.vertices.size)
        assertTrue(result.vertices.containsAll(listOf(original.vertices[0],original.vertices[4],original.vertices[20],original.vertices[24])))
        assertTrue(result.colors.all{it==Vec3(.4f,.4f,.4f)})
    }
    @Test fun protectedVertexKeepsItsIncidentTriangles() {
        val original=box();val p=original.vertices[12]
        fun facesAt(m:Mesh)=m.indices.toList().chunked(3).map{tri->tri.map{m.vertices[it]}}.filter{p in it}.toSet()
        val result=simplifySurface(original,protected={it==p})
        assertClosed(result)
        assertEquals(facesAt(original),facesAt(result))
        assertSame(original,simplifySurface(original,protected={true}))
    }
    @Test fun fixedSkeletonAndBoundedJointNeighborhood() {
        val joints=listOf(Joint("root",-1,Vec3(0f,0f,0f)),Joint("bend",0,Vec3(.1f,0f,0f)),
            Joint("tip",1,Vec3(.2f,0f,0f)),Joint("end",2,Vec3(.3f,0f,0f)))
        val guard=jointProtection(joints)
        assertTrue(guard(Vec3(.1f,.06f,0f)))
        assertFalse(guard(Vec3(.1f,.08f,0f)))
        val mesh=simplifySurface(box(),protected=guard)
        val bound=humanoidRig(mesh,joints)
        assertSame(joints,bound.joints)
        assertEquals(mesh.vertices.size*4,bound.weights.size)
        for(i in mesh.vertices.indices) assertEquals(1f,bound.weights.sliceArray(i*4 until i*4+4).sum(),.00001f)
    }
    @Test fun displacementLimitPreventsCollapse() {
        val original=box()
        assertSame(original,simplifySurface(original,.0001f))
    }
    @Test fun preservesDistinctColorBoundaries() {
        val original=box()
        val marked=Mesh(original.vertices,original.vertices.mapIndexed{i,_->if(i%2==0)Vec3(1f,0f,0f) else Vec3(0f,0f,1f)},original.indices)
        val reduced=simplifySurface(marked);assertClosed(reduced)
        assertTrue(reduced.colors.all{it==Vec3(1f,0f,0f)||it==Vec3(0f,0f,1f)})
    }
}
