package com.hereliesaz.extrusionextraction.shared

import kotlin.math.*

/** Reference limit bounds the retained image/depth arrays on mobile, not the number of generations. */
const val MAX_REFERENCES = 24

/** Plan fixed-size inference windows, preserving at least two shared reference images. */
fun viewWindows(count:Int):List<IntRange> {
    require(count in VIEW_COUNT..MAX_REFERENCES)
    val result=mutableListOf(0 until VIEW_COUNT)
    while(result.last().last<count-1) {
        val start=min(result.last().first+2,count-VIEW_COUNT)
        result.add(start until start+VIEW_COUNT)
    }
    return result
}

private fun median(values:List<Float>):Float {
    require(values.isNotEmpty()) { "No valid overlapping depths." }
    val s=values.sorted()
    return if(s.size%2==0) (s[s.size/2-1]+s[s.size/2])/2 else s[s.size/2]
}
private fun rotation(v:CameraView)=FloatArray(9){i->v.extrinsic[(i/3)*4+i%3]}
private fun transpose(m:FloatArray)=FloatArray(9){i->m[(i%3)*3+i/3]}
private fun multiply(a:FloatArray,b:FloatArray)=FloatArray(9){i->
    (0..2).sumOf { k->(a[i/3*3+k]*b[k*3+i%3]).toDouble() }.toFloat()
}
private fun transform(r:FloatArray,v:Vec3)=Vec3(
    r[0]*v.x+r[1]*v.y+r[2]*v.z,r[3]*v.x+r[4]*v.y+r[5]*v.z,r[6]*v.x+r[7]*v.y+r[8]*v.z)
private fun translation(v:CameraView)=Vec3(v.extrinsic[3],v.extrinsic[7],v.extrinsic[11])
private fun center(v:CameraView)=transform(transpose(rotation(v)),translation(v))*-1f
private fun norm(v:Vec3)=sqrt(v.dot(v))

/** Align one DA3 window into the established world frame using shared images.
 *
 * A similarity transform is necessary because independently inferred windows have
 * arbitrary global scales. Estimate scale from paired depth ratios in both
 * overlapping images, rotation from the first shared camera, and translation
 * from its center. Reject inconsistent depth/pose overlaps before fusing.
 * The first window remains the output reference; repeated views are not fused twice.
 */
fun appendWindow(established:List<CameraView>, start:Int, local:List<CameraView>):List<CameraView> {
    require(local.size==VIEW_COUNT && start>=0 && start+VIEW_COUNT>established.size)
    val overlap=established.size-start
    require(overlap in 2 until VIEW_COUNT)
    val logScales=mutableListOf<Float>()
    val typicalDepths=mutableListOf<Float>()
    for(i in 0 until overlap) {
        val a=established[start+i];val b=local[i]
        require(a.image===b.image) { "Window references do not match." }
        val ratios=mutableListOf<Float>()
        for(y in 0 until IMAGE_SIZE step 14) for(x in 0 until IMAGE_SIZE step 14) {
            val pixel=y*IMAGE_SIZE+x
            if(a.reliable(pixel) && b.reliable(pixel)) {
                val ratio=ln(a.depth[pixel])-ln(b.depth[pixel])
                if(ratio.isFinite()) { ratios.add(ratio);typicalDepths.add(a.depth[pixel]) }
            }
        }
        require(ratios.size>=32) { "Insufficient foreground overlap between windows." }
        val m=median(ratios)
        require(median(ratios.map { abs(it-m) })<=.20f) { "Overlapping depths disagree." }
        logScales.add(m)
    }
    val logScale=median(logScales)
    require(logScales.all { abs(it-logScale)<=.20f }) { "Overlapping views disagree on scale." }
    val scale=exp(logScale)
    require(scale.isFinite() && scale>0f)
    val globalAnchor=established[start];val localAnchor=local.first()
    val a=multiply(transpose(rotation(globalAnchor)),rotation(localAnchor))
    val offset=center(globalAnchor)-transform(a,center(localAnchor))*scale
    val depthTolerance=median(typicalDepths)*.10f
    for(i in 0 until overlap) {
        val global=established[start+i];val candidate=local[i]
        val predicted=multiply(rotation(candidate),transpose(a))
        val delta=multiply(predicted,transpose(rotation(global)))
        val angle=acos(((delta[0]+delta[4]+delta[8]-1f)/2f).coerceIn(-1f,1f))
        val distance=norm(transform(a,center(candidate))*scale+offset-center(global))
        require(angle.isFinite() && angle<=PI/12 && distance.isFinite() && distance<=depthTolerance) {
            "Camera poses disagree across windows. Reorder or replace overlapping views."
        }
    }
    return established+local.drop(overlap).map { view ->
        val r=multiply(rotation(view),transpose(a))
        val t=translation(view)*scale-transform(r,offset)
        val extrinsic=floatArrayOf(r[0],r[1],r[2],t.x,r[3],r[4],r[5],t.y,r[6],r[7],r[8],t.z)
        CameraView(view.image,FloatArray(view.depth.size){view.depth[it]*scale},view.confidence,view.k,extrinsic)
    }
}
