package com.hereliesaz.extrusionextraction.shared

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Export indexed geometry, vertex colors or a baked texture, normals and optional glTF 2.0 skin.
 * UV seams duplicate export vertices only; normals and skin weights are copied from the original mesh.
 * Inverse bind matrices are column-major translations of the global joint positions.
 */
fun writeGlb(mesh:Mesh,rig:Rig?,atlas:TextureAtlas?=null):ByteArray {
    require(atlas==null || atlas.uv.size==mesh.indices.size*2)
    val source=if(atlas==null) IntArray(mesh.vertices.size){it} else mesh.indices
    val exportIndices=if(atlas==null) mesh.indices else IntArray(source.size){it}
    val binary=ByteArrayOutputStream();val views=mutableListOf<String>();val access=mutableListOf<String>()
    fun buffer(size:Int)=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun accessor(bytes:ByteArray,component:Int,count:Int,type:String,target:Int?=null,bounds:String=""):Int {
        while(binary.size()%4!=0) binary.write(0)
        val start=binary.size();binary.write(bytes)
        views.add("""{"buffer":0,"byteOffset":$start,"byteLength":${bytes.size}${target?.let { ",\"target\":$it" } ?: ""}}""")
        access.add("""{"bufferView":${views.lastIndex},"componentType":$component,"count":$count,"type":"$type"$bounds}""")
        return access.lastIndex
    }
    fun vectors(v:List<Vec3>,bounds:String=""):Int {
        val b=buffer(v.size*12);v.forEach { b.putFloat(it.x).putFloat(it.y).putFloat(it.z) }
        return accessor(b.array(),5126,v.size,"VEC3",34962,bounds)
    }
    val original=mesh.vertices
    val v=source.map { original[it] }
    val bounds=""","min":[${v.minOf { it.x }},${v.minOf { it.y }},${v.minOf { it.z }}],"max":[${v.maxOf { it.x }},${v.maxOf { it.y }},${v.maxOf { it.z }}]"""
    val pos=vectors(v,bounds)
    val normals=MutableList(original.size){Vec3(0f,0f,0f)}
    for(i in mesh.indices.indices step 3) {
        val a=mesh.indices[i];val b=mesh.indices[i+1];val c=mesh.indices[i+2]
        val n=(original[b]-original[a]).cross(original[c]-original[a])
        for(j in intArrayOf(a,b,c)) normals[j]=normals[j]+n
    }
    val normal=vectors(source.map { normals[it].unit() })
    val indexBuffer=buffer(exportIndices.size*4);exportIndices.forEach { indexBuffer.putInt(it) }
    val indices=accessor(indexBuffer.array(),5125,exportIndices.size,"SCALAR",34963)
    var attributes="""{"POSITION":$pos,"NORMAL":$normal"""
    var material=""
    var primitiveMaterial=""
    if(atlas==null) {
        val color=vectors(mesh.colors)
        attributes+=""" ,"COLOR_0":$color"""
    } else {
        val uv=buffer(atlas.uv.size*4);atlas.uv.forEach { uv.putFloat(it) }
        val texcoord=accessor(uv.array(),5126,v.size,"VEC2",34962)
        attributes+=""" ,"TEXCOORD_0":$texcoord"""
        while(binary.size()%4!=0) binary.write(0)
        val start=binary.size();binary.write(atlas.png)
        views.add("""{"buffer":0,"byteOffset":$start,"byteLength":${atlas.png.size}}""")
        primitiveMaterial=""","material":0"""
        material=""","images":[{"bufferView":${views.lastIndex},"mimeType":"image/png"}],
            "samplers":[{"magFilter":9729,"minFilter":9729,"wrapS":33071,"wrapT":33071}],
            "textures":[{"sampler":0,"source":0}],
            "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1}}]"""
    }
    val nodes=mutableListOf("""{"mesh":0${if(rig!=null) ",\"skin\":0" else ""}}""")
    var skin=""
    if(rig!=null) {
        val ids=buffer(v.size*8);for(i in source) for(k in 0..3) ids.putShort(rig.indices[i*4+k].toShort())
        val weights=buffer(v.size*16);for(i in source) for(k in 0..3) weights.putFloat(rig.weights[i*4+k])
        val ia=accessor(ids.array(),5123,v.size,"VEC4",34962)
        val wa=accessor(weights.array(),5126,v.size,"VEC4",34962)
        attributes+=""" ,"JOINTS_0":$ia,"WEIGHTS_0":$wa"""
        val inverse=buffer(rig.joints.size*64)
        rig.joints.forEachIndexed { i,j ->
            val p=j.position
            val local=if(j.parent<0) p else p-rig.joints[j.parent].position
            val kids=rig.joints.indices.filter { rig.joints[it].parent==i }.map { it+1 }
            nodes.add("""{"name":"${j.name}","translation":[${local.x},${local.y},${local.z}]${if(kids.isEmpty()) "" else ",\"children\":$kids"}}""")
            floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,-p.x,-p.y,-p.z,1f).forEach { inverse.putFloat(it) }
        }
        val ib=accessor(inverse.array(),5126,rig.joints.size,"MAT4")
        skin=""","skins":[{"inverseBindMatrices":$ib,"joints":${(1..rig.joints.size).toList()},"skeleton":1}]"""
    }
    attributes+="}"
    while(binary.size()%4!=0) binary.write(0)
    val bin=binary.toByteArray()
    val json="""{"asset":{"version":"2.0","generator":"ExtrusionExtraction"},"scene":0,
        "scenes":[{"nodes":[0${if(rig!=null) ",1" else ""}]}],"nodes":[${nodes.joinToString(",")}],
        "meshes":[{"primitives":[{"attributes":$attributes,"indices":$indices$primitiveMaterial}]}],
        "buffers":[{"byteLength":${bin.size}}],"bufferViews":[${views.joinToString(",")}],
        "accessors":[${access.joinToString(",")}]$skin$material}""".encodeToByteArray()
    val padding=(4-json.size%4)%4
    return buffer(12+8+json.size+padding+8+bin.size)
        .putInt(0x46546c67).putInt(2).putInt(12+8+json.size+padding+8+bin.size)
        .putInt(json.size+padding).putInt(0x4e4f534a).put(json).put(ByteArray(padding){32})
        .putInt(bin.size).putInt(0x004e4942).put(bin).array()
}
