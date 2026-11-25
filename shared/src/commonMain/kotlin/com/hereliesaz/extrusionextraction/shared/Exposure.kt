package com.hereliesaz.extrusionextraction.shared

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Pairwise log exposure difference: gain[j] / gain[i] = exp(logRatio). */
internal data class ExposureOverlap(val i:Int,val j:Int,val logRatio:Float,val weight:Int)

/** Robust scalar exposure ratio from corresponding, unsaturated linear RGB samples.
 * Reject dark or clipped samples and inconsistent channel ratios rather than
 * treating changes in material color or illumination as exposure differences.
 */
internal fun exposureRatio(a:Vec3,b:Vec3):Float? {
    val left=floatArrayOf(a.x,a.y,a.z);val right=floatArrayOf(b.x,b.y,b.z)
    if((left+right).any{!it.isFinite() || it<.02f || it>.9f}) return null
    val ratios=FloatArray(3){ln(left[it])-ln(right[it])}
    if(ratios.maxOrNull()!!-ratios.minOrNull()!!>.15f) return null
    return ratios.sorted()[1]
}

/** Require enough consistent matched samples; discard outliers by median deviation. */
internal fun summarizeExposure(i:Int,j:Int,ratios:List<Float>):ExposureOverlap? {
    val sorted=ratios.filter{it.isFinite()}.sorted()
    if(sorted.size<12) return null
    val median=sorted[sorted.size/2]
    val deviations=sorted.map{abs(it-median)}.sorted()
    if(deviations[deviations.size/2]>.15f) return null
    val inliers=sorted.filter{abs(it-median)<=.15f}
    if(inliers.size<12 || inliers.size*2<sorted.size) return null
    return ExposureOverlap(i,j,inliers[inliers.size/2],inliers.size)
}

/** Solve a small weighted overlap graph in log space, anchoring the earliest
 * image in each connected component. Isolated images retain unit gain.
 * Corrections are limited to one stop in either direction. The anchor fixes
 * relative exposure only; no absolute white balance or relighting is inferred.
 */
internal fun exposureGains(count:Int,overlaps:List<ExposureOverlap>):FloatArray {
    require(count in 1..MAX_REFERENCES)
    val graph=Array(count){mutableListOf<ExposureOverlap>()}
    for(e in overlaps) {
        require(e.i in 0 until count && e.j in 0 until count && e.i!=e.j && e.weight>0 && e.logRatio.isFinite())
        graph[e.i].add(e);graph[e.j].add(e)
    }
    val visited=BooleanArray(count);val logs=DoubleArray(count)
    for(anchor in 0 until count) {
        if(visited[anchor]) continue
        val component=mutableListOf(anchor);visited[anchor]=true
        var cursor=0
        while(cursor<component.size) {
            val v=component[cursor++]
            for(e in graph[v]) {
                val other=if(e.i==v) e.j else e.i
                if(!visited[other]) {visited[other]=true;component.add(other)}
            }
        }
        if(component.size==1) continue
        val free=component.drop(1)
        val local=IntArray(count){-1};free.forEachIndexed{i,v->local[v]=i}
        val n=free.size;val matrix=Array(n){DoubleArray(n+1)}
        for((row,v) in free.withIndex()) for(e in graph[v]) {
            val other=if(e.i==v) e.j else e.i
            val w=e.weight.toDouble()
            matrix[row][row]+=w
            if(local[other]>=0) matrix[row][local[other]]-=w
            matrix[row][n]+=w*(if(v==e.j) e.logRatio else -e.logRatio)
        }
        // Anchored graph Laplacian is positive definite; partial pivoting for stability.
        for(col in 0 until n) {
            val pivot=(col until n).maxBy{abs(matrix[it][col])}
            if(pivot!=col) {val tmp=matrix[col];matrix[col]=matrix[pivot];matrix[pivot]=tmp}
            val d=matrix[col][col];check(abs(d)>1e-12)
            for(j in col..n) matrix[col][j]/=d
            for(row in 0 until n) if(row!=col) {
                val factor=matrix[row][col]
                for(j in col..n) matrix[row][j]-=factor*matrix[col][j]
            }
        }
        for((i,v) in free.withIndex()) logs[v]=matrix[i][n]
    }
    return FloatArray(count){exp(logs[it]).coerceIn(.5,2.0).toFloat()}
}
