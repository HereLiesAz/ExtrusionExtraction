package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

/** Independent tetrahedral fixtures with known boundaries and topology. */
class MeshRepairTest {
    private val vertices=listOf(Vec3(0f,0f,0f),Vec3(.04f,0f,0f),Vec3(0f,.04f,0f),Vec3(0f,0f,1f))
    private val closed=intArrayOf(0,2,1,0,1,3,1,2,3,2,0,3)
    private fun mesh(v:List<Vec3>,i:IntArray)=Mesh(v,List(v.size){Vec3(1f,0f,0f)},i)

    @Test fun preservesClosedMesh() {
        val result=repairMesh(mesh(vertices,closed))
        assertEquals(vertices,result.vertices)
        assertContentEquals(closed,result.indices)
    }
    @Test fun fillsSmallPlanarHole() {
        val result=repairMesh(mesh(vertices,closed.copyOfRange(3,12)))
        assertEquals(5,result.vertices.size)
        assertEquals(18,result.indices.size)
        assertEquals(0f,result.vertices.last().z)
        assertEquals(Vec3(1f,0f,0f),result.colors.last())
        assertEquals(.013333333f,result.vertices.last().x,.000001f)
        assertEquals(.013333333f,result.vertices.last().y,.000001f)
        assertContentEquals(intArrayOf(0,1,3,1,2,3,2,0,3,1,0,4,2,1,4,0,2,4),result.indices)
    }
    @Test fun removesDuplicatesAndDegenerates() {
        assertContentEquals(closed,repairMesh(mesh(vertices,closed+intArrayOf(1,2,0,0,0,1))).indices)
    }
    @Test fun reconcilesWinding() {
        assertContentEquals(closed,repairMesh(mesh(vertices,intArrayOf(0,2,1,0,3,1,1,2,3,2,0,3))).indices)
    }
    @Test fun rejectsLargeHole() {
        assertFailsWith<IllegalArgumentException>{repairMesh(mesh(vertices,closed.copyOfRange(0,9)))}
    }
    @Test fun rejectsNonManifoldEdge() {
        assertFailsWith<IllegalArgumentException>{repairMesh(mesh(vertices+Vec3(1f,1f,1f),closed+intArrayOf(0,1,4)))}
    }
    @Test fun rejectsPinchedVertex() {
        val pinched=vertices+listOf(Vec3(-1f,0f,0f),Vec3(0f,-1f,0f),Vec3(0f,0f,-1f))
        assertFailsWith<IllegalArgumentException>{
            repairMesh(mesh(pinched,closed+intArrayOf(0,5,4,0,4,6,4,5,6,5,0,6)))
        }
    }
}
