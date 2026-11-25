package com.hereliesaz.extrusionextraction.shared

import kotlin.test.*

class LearnedRigTest {
    private fun fixture()=floatArrayOf(1f,2f,-1f,0f,0f,0f,0f,0f,1f,0f,
        0f,1f,0f,0f,.25f,.75f,0f,0f)
    @Test fun preservesJointOrderAndWeights() {
        val r=decodeLearnedRig(fixture(),1)
        assertEquals(listOf(Joint("joint_0",-1,Vec3(0f,0f,0f)),Joint("joint_1",0,Vec3(0f,1f,0f))),r.joints)
        assertContentEquals(intArrayOf(0,1,0,0),r.indices)
        assertContentEquals(floatArrayOf(.25f,.75f,0f,0f),r.weights)
    }
    @Test fun rejectsInvalidNativePackets() {
        for((i,v) in listOf(0 to 2f,2 to 0f,6 to 1f,8 to Float.NaN,10 to 2f,11 to .5f,14 to -.1f,15 to .1f,11 to 0f)) {
            assertFailsWith<IllegalArgumentException>{decodeLearnedRig(fixture().apply{this[i]=v},1)}
        }
        assertFailsWith<IllegalArgumentException>{decodeLearnedRig(fixture().dropLast(1).toFloatArray(),1)}
    }
}
