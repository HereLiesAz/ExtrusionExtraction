package com.hereliesaz.extrusionextraction.shared

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/** Per-corner UVs and a self-contained sRGB PNG. The original mesh is unchanged. */
class TextureAtlas(val uv:FloatArray,val png:ByteArray,val size:Int)

/** Bake linear vertex colors into individually padded triangle charts.
 * This is a bounded, editable base-color atlas, not photographic reprojection.
 * Return null when the triangle budget cannot fit a 4096px atlas at 8px per chart.
 */
fun bakeTextureAtlas(mesh:Mesh,checkpoint:()->Unit={}):TextureAtlas? {
    val count=mesh.indices.size/3
    val tile=listOf(32,16,8).firstOrNull { count<=(4096/it)*(4096/it) } ?: return null
    var size=tile
    while((size/tile)*(size/tile)<count) size*=2
    val columns=size/tile
    val pixels=ByteArray(size*size*3)
    val uv=FloatArray(mesh.indices.size*2)
    fun srgb(v:Float):Byte {
        val x=v.coerceIn(0f,1f)
        val y=if(x<=.0031308f) 12.92f*x else 1.055f*x.pow(1f/2.4f)-.055f
        return (y*255f).roundToInt().toByte()
    }
    for(face in 0 until count) {
        if(face%256==0) checkpoint()
        val ox=(face%columns)*tile;val oy=(face/columns)*tile
        val c=Array(3){mesh.colors[mesh.indices[face*3+it]]}
        val span=(tile-3).toFloat()
        val u0=ox+1.5f;val v0=oy+1.5f
        val corners=floatArrayOf(u0,v0,u0+span,v0,u0,v0+span)
        for(i in corners.indices) uv[face*6+i]=corners[i]/size
        for(y in 0 until tile) for(x in 0 until tile) {
            var b=((x-1)/span).coerceIn(0f,1f)
            var d=((y-1)/span).coerceIn(0f,1f)
            if(b+d>1f) { val excess=(b+d-1f)/2f;b-=excess;d-=excess }
            val rgb=c[0]*(1f-b-d)+c[1]*b+c[2]*d
            val i=((oy+y)*size+ox+x)*3
            pixels[i]=srgb(rgb.x);pixels[i+1]=srgb(rgb.y);pixels[i+2]=srgb(rgb.z)
        }
    }
    checkpoint()
    return TextureAtlas(uv,encodeRgbPng(size,pixels,checkpoint),size)
}

/** Portable PNG encoder using only Java base APIs available on Android and desktop. */
private fun encodeRgbPng(size:Int,pixels:ByteArray,checkpoint:()->Unit):ByteArray {
    val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
    out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
    fun chunk(name:String,data:ByteArray) {
        val type=name.encodeToByteArray();val crc=CRC32();crc.update(type);crc.update(data)
        out.writeInt(data.size);out.write(type);out.write(data);out.writeInt(crc.value.toInt())
    }
    val header=ByteArrayOutputStream()
    DataOutputStream(header).use { it.writeInt(size);it.writeInt(size);it.write(byteArrayOf(8,2,0,0,0)) }
    chunk("IHDR",header.toByteArray())
    chunk("sRGB",byteArrayOf(0))
    val compressed=ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { zip->
        for(y in 0 until size) {
            if(y%32==0) checkpoint()
            zip.write(0);zip.write(pixels,y*size*3,size*3)
        }
    }
    chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf())
    return bytes.toByteArray()
}
