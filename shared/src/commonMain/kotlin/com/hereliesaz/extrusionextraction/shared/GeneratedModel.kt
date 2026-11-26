package com.hereliesaz.extrusionextraction.shared

/** Per-corner UVs and a self-contained sRGB PNG. The original mesh is unchanged. */
class TextureAtlas(val uv:FloatArray,val png:ByteArray,val size:Int)

/** Geometry, optional rig and texture from the same successful GLB export. */
class GeneratedModel(val mesh:Mesh,val rig:Rig?,val atlas:TextureAtlas?,val glb:ByteArray)

/** A validated reconstruction can produce alternate rig variants without another depth pass.
 * [close] releases the platform session when the inputs change or the workspace closes.
 */
class ReconstructionSession(val base:GeneratedModel,
                            val createVariant:suspend (RigMode,(String)->Unit)->GeneratedModel,
                            val close:()->Unit={})
