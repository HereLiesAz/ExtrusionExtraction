package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class WindowAlignmentTest {
    @Test fun windowCoverage() {
        assertEquals(listOf(0..3),viewWindows(4))
        assertEquals(listOf(0..3,1..4),viewWindows(5))
        assertEquals(listOf(0..3,2..5,3..6),viewWindows(7))
        for(n in 4..MAX_REFERENCES) {
            val windows=viewWindows(n)
            assertEquals((0 until n).toList(),windows.flatten().distinct())
            assertTrue(windows.zipWithNext().all { (a,b)->a.intersect(b).size>=2 })
        }
    }

    private val images=List(6){ViewImage("view $it",IntArray(IMAGE_SIZE*IMAGE_SIZE){-1})}
    private val k=floatArrayOf(100f,0f,252f,0f,100f,252f,0f,0f,1f)
    private fun view(i:Int,depth:Float,extrinsic:FloatArray)=CameraView(images[i],
        FloatArray(IMAGE_SIZE*IMAGE_SIZE){depth},FloatArray(IMAGE_SIZE*IMAGE_SIZE){1f},k,extrinsic)
    // Established cameras face along +Z; camera centers are (i,0,0).
    private fun established()=List(4){i->view(i,6f,floatArrayOf(1f,0f,0f,-i.toFloat(),0f,1f,0f,0f,0f,0f,1f,0f))}
    // Independent coordinates: local X is global +Y, local Y is global -X.
    // Global point = 2 * (-localY, localX, localZ) + (2,4,0).
    private fun local()=List(4){i->val global=i+2
        view(global,3f,floatArrayOf(0f,-1f,0f,(2f-global)/2,1f,0f,0f,2f,0f,0f,1f,0f))
    }
    @Test fun alignsRotationTranslationAndScale() {
        val prior=established()
        val result=appendWindow(prior,2,local())
        assertEquals(6,result.size)
        assertSame(prior[2],result[2])
        val new=result[5]
        assertEquals(6f,new.depth[0],.00001f)
        assertEquals(5f,new.world(252,252,0f).x,.00001f)
        assertEquals(0f,new.world(252,252,0f).y,.00001f)
        assertEquals(6f,new.world(252,252,6f).z,.00001f)
        assertContentEquals(floatArrayOf(1f,0f,0f,-5f,0f,1f,0f,0f,0f,0f,1f,0f),new.extrinsic)
    }
    @Test fun rejectsInconsistentScale() {
        val bad=local().toMutableList()
        bad[1]=view(3,12f,bad[1].extrinsic)
        assertFailsWith<IllegalArgumentException>{appendWindow(established(),2,bad)}
    }
    @Test fun rejectsInconsistentCamera() {
        val bad=local().toMutableList()
        val matrix=bad[1].extrinsic.copyOf().also { it[3]+=5f }
        bad[1]=view(3,3f,matrix)
        assertFailsWith<IllegalArgumentException>{appendWindow(established(),2,bad)}
    }
}
