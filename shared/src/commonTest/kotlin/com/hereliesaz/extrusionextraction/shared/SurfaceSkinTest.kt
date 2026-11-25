package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class SurfaceSkinTest {
    private val triangle=listOf(Vec3(0f,0f,0f),Vec3(1f,0f,0f),Vec3(0f,1f,0f))
    private val parallel=triangle.map{it+Vec3(0f,0f,.01f)}
    private fun mesh(v:List<Vec3>,i:IntArray)=Mesh(v,List(v.size){Vec3(1f,1f,1f)},i)
    private fun weight(r:Rig,v:Int,j:Int)=(0..3).sumOf{
        if(r.indices[v*4+it]==j)r.weights[v*4+it].toDouble() else 0.0
    }
    @Test fun disconnectedNearbySurfacesDoNotShareInfluence() {
        val m=mesh(triangle+parallel,intArrayOf(0,1,2,3,4,5))
        val joints=listOf(Joint("a",-1,triangle[0]),Joint("b",0,parallel[0]))
        val r=humanoidRig(m,joints)
        for(v in 0..5) {
            assertEquals(1.0,weight(r,v,v/3))
            assertEquals(0.0,weight(r,v,1-v/3))
        }
    }
    @Test fun unseededComponentBindsRigidly() {
        val m=mesh(triangle+parallel,intArrayOf(0,1,2,3,4,5))
        val r=humanoidRig(m,listOf(Joint("a",-1,triangle[0])))
        for(v in 0..5) assertEquals(1.0,weight(r,v,0))
    }
    @Test fun analyticWeightsOnTriangle() {
        val r=humanoidRig(mesh(triangle,intArrayOf(0,1,2)),
            listOf(Joint("a",-1,triangle[0]),Joint("b",0,triangle[1])))
        assertTrue(weight(r,0,0)>.999)
        assertTrue(weight(r,1,1)>.999)
        // At vertex 2 the distances are 1 and sqrt(2), giving a 4:1 weight ratio.
        assertEquals(.8,weight(r,2,0),1e-6)
        assertEquals(.2,weight(r,2,1),1e-6)
    }
    @Test fun templateHasNormalizedDistinctInfluences() {
        val m=mesh(listOf(Vec3(-.5f,0f,0f),Vec3(.5f,0f,0f),Vec3(0f,1f,0f)),intArrayOf(0,1,2))
        val r=humanoidRig(m)
        assertEquals(19,r.joints.size)
        for(v in m.vertices.indices) {
            assertEquals(1f,r.weights.sliceArray(v*4 until v*4+4).sum(),1e-6f)
            assertEquals(4,r.indices.sliceArray(v*4 until v*4+4).toSet().size)
        }
    }
    @Test fun checkpointPropagates() {
        assertFailsWith<IllegalStateException>{
            humanoidRig(mesh(triangle,intArrayOf(0,1,2)),listOf(Joint("a",-1,triangle[0]))) {error("cancel")}
        }
    }
}
