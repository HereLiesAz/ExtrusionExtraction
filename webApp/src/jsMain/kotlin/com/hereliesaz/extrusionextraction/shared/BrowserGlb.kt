package com.hereliesaz.extrusionextraction.shared

import kotlinx.serialization.json.*

/** Validate the original GLB against the packet's actual preview attributes without regenerating
 * floating-point normals on a different numeric backend. No external buffers, images or extensions.
 * The worker and JVM importer continue to enforce byte-exact exporter equality.
 */
internal fun validateBrowserGlb(model:GeneratedModel) {
    val bytes=model.glb
    fun int(offset:Int):Int {
        require(offset>=0 && offset.toLong()+4<=bytes.size) { "Invalid GLB offset." }
        return (bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8) or
            ((bytes[offset+2].toInt() and 255) shl 16) or ((bytes[offset+3].toInt() and 255) shl 24)
    }
    require(bytes.size>=28 && int(0)==0x46546c67 && int(4)==2 && int(8)==bytes.size && int(16)==0x4e4f534a)
    val jsonLength=int(12)
    require(jsonLength in 1..minOf(4*1024*1024,bytes.size-28) && jsonLength%4==0) { "Invalid GLB JSON length." }
    val binHeader=20+jsonLength
    require(int(binHeader+4)==0x004e4942 && int(binHeader)==bytes.size-binHeader-8) { "Invalid GLB BIN chunk." }
    val bin=binHeader+8
    val root=Json.parseToJsonElement(bytes.decodeToString(20,binHeader)).jsonObject
    fun JsonObject.number(key:String)=getValue(key).jsonPrimitive.int
    fun JsonObject.array(key:String)=getValue(key).jsonArray
    fun JsonObject.obj(key:String)=getValue(key).jsonObject
    fun JsonElement.objectAt()=jsonObject
    require(root.keys.all{it in setOf("asset","scene","scenes","nodes","meshes","buffers","bufferViews","accessors","skins","images","samplers","textures","materials")})
    require(root.obj("asset")["generator"]?.jsonPrimitive?.content=="ExtrusionExtraction")
    val buffers=root.array("buffers")
    require(buffers.size==1 && buffers[0].objectAt().keys==setOf("byteLength") && buffers[0].objectAt().number("byteLength")==bytes.size-bin)
    val views=root.array("bufferViews")
    val accessors=root.array("accessors")
    fun view(index:Int,required:Long):Int {
        val v=views[index].objectAt()
        require(v.keys.all{it in setOf("buffer","byteOffset","byteLength","target")} && v.number("buffer")==0)
        val offset=v.number("byteOffset");val length=v.number("byteLength")
        require(offset>=0 && length>=0 && required==length.toLong() && offset.toLong()+length<=bytes.size-bin)
        return bin+offset
    }
    fun accessor(index:Int,component:Int,count:Int,type:String,stride:Int):Int {
        val a=accessors[index].objectAt()
        require(a.keys.all{it in setOf("bufferView","componentType","count","type","min","max")} &&
            a.number("componentType")==component && a.number("count")==count && a.getValue("type").jsonPrimitive.content==type)
        return view(a.number("bufferView"),count.toLong()*stride)
    }
    val primitive=root.array("meshes").single().objectAt().array("primitives").single().objectAt()
    require(primitive.keys.all{it in setOf("attributes","indices","material")})
    val attrs=primitive.obj("attributes")
    val mesh=model.mesh
    val atlas=model.atlas
    val rig=model.rig
    val source=if(atlas==null) IntArray(mesh.vertices.size){it} else mesh.indices
    val keys=mutableSetOf("POSITION","NORMAL",if(atlas==null)"COLOR_0" else "TEXCOORD_0")
    if(rig!=null) keys.addAll(listOf("JOINTS_0","WEIGHTS_0"))
    require(attrs.keys==keys)
    val pos=accessor(attrs.number("POSITION"),5126,source.size,"VEC3",12)
    val normal=accessor(attrs.number("NORMAL"),5126,source.size,"VEC3",12)
    val index=accessor(primitive.number("indices"),5125,mesh.indices.size,"SCALAR",4)
    fun sameFloat(at:Int,value:Float)=require(Float.fromBits(int(at))==value) { "GLB differs from preview data." }
    for(i in source.indices) {
        val v=mesh.vertices[source[i]]
        sameFloat(pos+i*12,v.x);sameFloat(pos+i*12+4,v.y);sameFloat(pos+i*12+8,v.z)
        require((0..2).all{Float.fromBits(int(normal+i*12+it*4)).isFinite()})
    }
    for(i in mesh.indices.indices) require(int(index+i*4)==if(atlas==null)mesh.indices[i] else i) { "GLB index mismatch." }
    if(atlas==null) {
        require(root["images"]==null && root["textures"]==null && root["materials"]==null && root["samplers"]==null && primitive["material"]==null)
        val color=accessor(attrs.number("COLOR_0"),5126,source.size,"VEC3",12)
        for(i in source.indices) {
            val c=mesh.colors[source[i]]
            sameFloat(color+i*12,c.x);sameFloat(color+i*12+4,c.y);sameFloat(color+i*12+8,c.z)
        }
    } else {
        val uv=accessor(attrs.number("TEXCOORD_0"),5126,source.size,"VEC2",8)
        for(i in atlas.uv.indices) sameFloat(uv+i*4,atlas.uv[i])
        val image=root.array("images").single().objectAt()
        require(image.keys==setOf("bufferView","mimeType") && image["mimeType"]?.jsonPrimitive?.content=="image/png")
        val start=view(image.number("bufferView"),atlas.png.size.toLong())
        require(atlas.png.indices.all{bytes[start+it]==atlas.png[it]}) { "GLB texture mismatch." }
        require(primitive.number("material")==0 && root.array("textures").single().objectAt().number("source")==0)
    }
    val nodes=root.array("nodes")
    require(root.number("scene")==0 && root.array("scenes").single().objectAt().array("nodes").map{it.jsonPrimitive.int}==
        if(rig==null)listOf(0) else listOf(0,1))
    require(nodes.size==1+(rig?.joints?.size ?: 0))
    require(nodes[0].objectAt().number("mesh")==0)
    if(rig==null) {
        require(root["skins"]==null && nodes[0].objectAt().keys==setOf("mesh"))
    } else {
        require(nodes[0].objectAt().keys==setOf("mesh","skin") && nodes[0].objectAt().number("skin")==0)
        val skin=root.array("skins").single().objectAt()
        require(skin.keys==setOf("inverseBindMatrices","joints","skeleton") && skin.number("skeleton")==1 &&
            skin.array("joints").map{it.jsonPrimitive.int}==(1..rig.joints.size).toList())
        val ids=accessor(attrs.number("JOINTS_0"),5123,source.size,"VEC4",8)
        val weights=accessor(attrs.number("WEIGHTS_0"),5126,source.size,"VEC4",16)
        val inverse=accessor(skin.number("inverseBindMatrices"),5126,rig.joints.size,"MAT4",64)
        for(i in source.indices) for(k in 0..3) {
            val at=ids+i*8+k*2
            require(((bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8))==rig.indices[source[i]*4+k])
            sameFloat(weights+i*16+k*4,rig.weights[source[i]*4+k])
        }
        for((i,joint) in rig.joints.withIndex()) {
            val node=nodes[i+1].objectAt()
            require(node.keys.all{it in setOf("name","translation","children")} && node["name"]?.jsonPrimitive?.content==joint.name)
            val children=node["children"]?.jsonArray?.map{it.jsonPrimitive.int} ?: emptyList()
            require(children==rig.joints.indices.filter{rig.joints[it].parent==i}.map{it+1})
            val local=if(joint.parent<0)joint.position else joint.position-rig.joints[joint.parent].position
            val translation=node.array("translation")
            require(translation.size==3)
            for((k,value) in listOf(local.x,local.y,local.z).withIndex())
                require(kotlin.math.abs(translation[k].jsonPrimitive.float-value)<=1e-6f)
            val p=joint.position
            val matrix=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,-p.x,-p.y,-p.z,1f)
            for(k in matrix.indices) sameFloat(inverse+i*64+k*4,matrix[k])
        }
    }
}
