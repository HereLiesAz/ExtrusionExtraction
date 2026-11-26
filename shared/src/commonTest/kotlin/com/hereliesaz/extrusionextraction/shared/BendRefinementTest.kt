package com.hereliesaz.extrusionextraction.shared

import kotlin.math.sqrt
import kotlin.test.*

class BendRefinementTest {
    private val vertices=listOf(Vec3(0f,0f,0f),Vec3(1f,0f,0f),Vec3(0f,1f,0f),Vec3(0f,0f,1f))
    private val mesh=Mesh(vertices,List(4){Vec3(.2f,.4f,.6f)},intArrayOf(0,2,1,0,1,3,0,3,2,1,2,3))
    private val joints=listOf(Joint("root",-1,Vec3(0f,0f,0f)),Joint("bend",0,Vec3(0f,0f,0f)))
    private fun rig(mass:List<Float>)=Rig(joints,IntArray(16){if(it%4==1)1 else 0},
        FloatArray(16){i->when(i%4){0->1f-mass[i/4];1->mass[i/4];else->0f}})
    @Test fun rigidWeightsAndRestGeometryAreUnchanged() {
        val rigid=rig(List(4){1f})
        val r=refineBends(mesh,rigid)
        assertSame(mesh,r.mesh);assertSame(rigid,r.rig)
        assertEquals(0,r.adjustedVertices);assertEquals(0,r.splitEdges)
    }
    @Test fun boundedTransfersCannotIncreaseSampledEdgeStrain() {
        val original=rig(listOf(.5f,.5f,.5f,.5f))
        val result=refineBends(mesh,original,maxTriangles=4)
        assertSame(mesh,result.mesh);assertSame(joints,result.rig.joints)
        assertContentEquals(original.indices,result.rig.indices)
        for(v in vertices.indices) {
            assertEquals(1f,result.rig.weights.copyOfRange(v*4,v*4+4).sum(),.000001f)
            assertTrue(kotlin.math.abs(result.rig.weights[v*4+1]-.5f)<=.100001f)
        }
        // A 90-degree Z rotation mixes orthogonal unit vectors at weight 1/2:
        // the original x edge length becomes sqrt(1/2), independently known.
        val baseline=1.0-sqrt(.5)
        val w=result.rig.weights[5].toDouble()
        val changed=1.0-sqrt((1-w)*(1-w)+w*w)
        assertTrue(changed<=baseline+1e-6)
        for(axis in 0..2) for(sign in listOf(-1,1)) {
            fun score(r:Rig):Double {
                val pos=vertices.mapIndexed{v,p->
                    val x=p.x.toDouble();val y=p.y.toDouble();val z=p.z.toDouble()
                    val rotated=when(axis){0->listOf(x,-sign*z,sign*y);1->listOf(sign*z,y,-sign*x);else->listOf(-sign*y,sign*x,z)}
                    val w=r.weights[v*4+1].toDouble()
                    listOf(x,y,z).mapIndexed{k,c->c+(rotated[k]-c)*w}
                }
                return (0..3).sumOf{a->(a+1..3).sumOf{b->
                    val len=sqrt((0..2).sumOf{k->val d=pos[a][k]-pos[b][k];d*d})
                    val rest=if(a==0)1.0 else sqrt(2.0)
                    val e=len/rest-1.0;e*e
                }}
            }
            assertTrue(score(result.rig)<=score(original)+1e-6)
        }
    }
    @Test fun asymmetricBlendHasStrictAcceptedImprovement() {
        val original=rig(listOf(.1f,.1f,.1f,.25f))
        val result=refineBends(mesh,original,maxTriangles=4)
        assertEquals(1,result.adjustedVertices)
        assertEquals(.15f,result.rig.weights[13],.000001f)
        assertSame(mesh,result.mesh)
        assertContentEquals(original.indices,result.rig.indices)
        assertEquals(.25f,original.weights[13])
    }
    @Test fun conformingSplitPreservesDisconnectedClosedSurfacesAndBudget() {
        // Three separated tetrahedra provide a 12-face fixture and a one-split
        // budget. Only the first shell has a weight transition.
        val v=(0..2).flatMap{s->vertices.map{it+Vec3(s*3f,0f,0f)}}
        val original=Mesh(v,List(v.size){Vec3(.2f,.4f,.6f)},
            (0..2).flatMap{s->mesh.indices.map{it+s*4}}.toIntArray())
        val fixedJoints=listOf(joints[0],Joint("bend",0,Vec3(0f,0f,.5f)))
        val bound=Rig(fixedJoints,IntArray(v.size*4){if(it%4==1)1 else 0},
            FloatArray(v.size*4){i->if(i==13 || (i%4==0 && i/4!=3))1f else 0f})
        val result=refineBends(original,bound,maxTriangles=14)
        assertEquals(1,result.splitEdges)
        assertEquals(0,result.adjustedVertices)
        assertEquals(13,result.mesh.vertices.size)
        assertEquals(42,result.mesh.indices.size)
        assertEquals(v,result.mesh.vertices.take(12))
        assertEquals(fixedJoints,result.rig.joints)
        assertEquals(Vec3(.2f,.4f,.6f),result.mesh.colors.last())
        // The appended position must bisect one of the three original edges
        // incident to vertex 3; no edge between shells is legal.
        assertTrue((0..2).any{result.mesh.vertices.last()==(v[it]+v[3])*.5f})
        assertEquals(.5f,result.rig.weights[48],.000001f)
        assertEquals(.5f,result.rig.weights[49],.000001f)
        assertContentEquals(result.mesh.indices,repairMesh(result.mesh).indices)
        assertNull(firstNonAdjacentIntersection(result.mesh))
        val capped=refineBends(original,bound,maxTriangles=12)
        assertSame(original,capped.mesh)
    }
    @Test fun cancellationIsNotSwallowed() {
        assertFailsWith<IllegalStateException>{refineBends(mesh,rig(List(4){.5f})){error("cancel")}}
    }
}
