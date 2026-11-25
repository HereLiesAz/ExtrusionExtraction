package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class MeshIntersectionsTest {
    private val base=listOf(Vec3(0f,0f,0f),Vec3(2f,0f,0f),Vec3(0f,2f,0f))
    private fun pair(other:List<Vec3>):Mesh = mesh(base+other,intArrayOf(0,1,2,3,4,5))
    private fun mesh(vertices:List<Vec3>,indices:IntArray)=Mesh(vertices,List(vertices.size){Vec3(1f,1f,1f)},indices)
    @Test fun crossingPlanes() {
        assertEquals(0 to 1,firstNonAdjacentIntersection(pair(listOf(Vec3(.5f,.5f,-1f),Vec3(.5f,.5f,1f),Vec3(.5f,1f,0f)))))
    }
    @Test fun coplanarOverlap() {
        assertEquals(0 to 1,firstNonAdjacentIntersection(pair(listOf(Vec3(.2f,.2f,0f),Vec3(.8f,.2f,0f),Vec3(.2f,.8f,0f)))))
    }
    @Test fun coplanarSeparationDespiteOverlappingBounds() {
        assertNull(firstNonAdjacentIntersection(pair(listOf(Vec3(1.1f,1.1f,0f),Vec3(2f,1.1f,0f),Vec3(1.1f,2f,0f)))))
    }
    @Test fun parallelPlanes() {
        assertNull(firstNonAdjacentIntersection(pair(base.map{it+Vec3(0f,0f,.01f)})))
    }
    @Test fun nonAdjacentContact() {
        assertEquals(0 to 1,firstNonAdjacentIntersection(pair(listOf(Vec3(2f,0f,0f),Vec3(3f,0f,0f),Vec3(3f,1f,0f)))))
    }
    @Test fun sharedEdgeIsExcluded() {
        assertNull(firstNonAdjacentIntersection(mesh(base+Vec3(0f,-1f,0f),intArrayOf(0,1,2,1,0,3))))
    }
    @Test fun hierarchyFindsCrossLeafIntersection() {
        val vertices=List(18){i->base.map{it+Vec3(i*5f,0f,0f)}}.flatten()+
            listOf(Vec3(85.5f,.5f,-1f),Vec3(85.5f,.5f,1f),Vec3(85.5f,1f,0f))
        assertEquals(17 to 18,firstNonAdjacentIntersection(mesh(vertices,IntArray(vertices.size){it})))
    }
}
