package com.hereliesaz.extrusionextraction.shared

import kotlin.math.floor
import kotlin.math.roundToInt

/** U²-Net portable input and fused saliency output have this square resolution. */
const val SEGMENT_SIZE = 320

/** Existing alpha is authoritative; do not replace hand-prepared silhouettes. */
fun hasForegroundMask(image:ViewImage):Boolean = image.sourceMask

/** Pixel-center bilinear resampling, shared by both platform runtimes. */
private fun sample(width:Int,height:Int,x:Float,y:Float,value:(Int)->Float):Float {
    val sx=x.coerceIn(0f,(width-1).toFloat())
    val sy=y.coerceIn(0f,(height-1).toFloat())
    val x0=floor(sx).toInt();val y0=floor(sy).toInt()
    val x1=minOf(x0+1,width-1);val y1=minOf(y0+1,height-1)
    val tx=sx-x0;val ty=sy-y0
    val a=value(y0*width+x0)*(1-tx)+value(y0*width+x1)*tx
    val b=value(y1*width+x0)*(1-tx)+value(y1*width+x1)*tx
    return a*(1-ty)+b*ty
}

/** RGB NCHW input: resize, normalize by image maximum, then ImageNet mean/std.
 * Bilinear interpolation is a portable approximation to rembg's Lanczos resize.
 */
fun segmentationInput(image:ViewImage):FloatArray {
    val size=SEGMENT_SIZE;val pixels=size*size
    val input=FloatArray(3*pixels)
    val mean=floatArrayOf(.485f,.456f,.406f);val std=floatArrayOf(.229f,.224f,.225f)
    for(c in 0..2) for(y in 0 until size) for(x in 0 until size) {
        val sx=(x+.5f)*IMAGE_SIZE/size-.5f
        val sy=(y+.5f)*IMAGE_SIZE/size-.5f
        input[c*pixels+y*size+x]=sample(IMAGE_SIZE,IMAGE_SIZE,sx,sy) { i ->
            if(!image.observed(i)) 255f else ((image.argb[i] ushr (16-c*8)) and 255).toFloat()
        }
    }
    val maximum=(input.maxOrNull() ?: 0f).coerceAtLeast(1e-6f)
    for(c in 0..2) for(i in 0 until pixels) {
        val at=c*pixels+i
        input[at]=(input[at]/maximum-mean[c])/std[c]
    }
    return input
}

/** Convert the fused saliency prediction to alpha on the original DA3 pixel grid.
 * Preserve RGB and explicitly reject flat/invalid or virtually empty predictions.
 * Existing transparency is authoritative and bypasses learned masking.
 */
fun applyForeground(image:ViewImage, saliency:FloatArray):ViewImage {
    if(hasForegroundMask(image)) return image
    require(saliency.size==SEGMENT_SIZE*SEGMENT_SIZE && saliency.all { it.isFinite() }) {
        "Invalid foreground prediction for ${image.name}."
    }
    val low=saliency.minOrNull()!!;val high=saliency.maxOrNull()!!
    require(high-low>=.05f && high>=.1f) { "No reliable foreground in ${image.name}." }
    var foreground=0
    val pixels=IntArray(image.argb.size) { i ->
        val x=i%IMAGE_SIZE;val y=i/IMAGE_SIZE
        val sx=(x+.5f)*SEGMENT_SIZE/IMAGE_SIZE-.5f
        val sy=(y+.5f)*SEGMENT_SIZE/IMAGE_SIZE-.5f
        val predicted=((sample(SEGMENT_SIZE,SEGMENT_SIZE,sx,sy){saliency[it]}-low)/(high-low)).coerceIn(0f,1f)
        val a=if(image.observed(i)) (predicted*image.argb[i].ushr(24)).roundToInt() else 0
        if(a>=128) foreground++
        (image.argb[i] and 0x00ffffff) or (a shl 24)
    }
    require(foreground>=image.argb.size/100) { "Foreground too small in ${image.name}." }
    return ViewImage(image.name,pixels,image.sourceBounds,true,image.croppedForeground)
}
