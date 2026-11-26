package com.hereliesaz.extrusionextraction.shared

internal const val RESULT_MAGIC=0x45455231 // EER1
/** External import budgets: each variant and the complete envelope are bounded separately. */
const val MAX_COLAB_MODEL_BYTES=256L*1024*1024
const val MAX_COLAB_RESULT_BYTES=512L*1024*1024

/** Stable protocol IDs, independent of enum declaration order. */
internal fun modeId(mode:RigMode)=when(mode) { RigMode.NONE->0;RigMode.TEMPLATE->1;RigMode.LEARNED->2 }
internal fun modeFor(id:Int)=when(id) { 0->RigMode.NONE;1->RigMode.TEMPLATE;2->RigMode.LEARNED;else->error("Unknown rig variant.") }

/** Common envelope framing. The platform supplies bounded model decoding and exact end detection. */
internal fun readColabEnvelope(readInt:()->Int,readLong:()->Long,readPacket:(Long)->GeneratedModel,
                               atEnd:()->Boolean):Map<RigMode,GeneratedModel> {
    require(readInt()==RESULT_MAGIC) { "Unsupported Colab result format." }
    val count=readInt()
    require(count in 1..3) { "Invalid result variant count." }
    var consumed=8L
    val result=linkedMapOf<RigMode,GeneratedModel>()
    repeat(count) {
        val mode=modeFor(readInt())
        require(mode !in result) { "Duplicate result variant." }
        val length=readLong()
        require(length in 1..MAX_COLAB_MODEL_BYTES) { "Invalid result packet size." }
        consumed+=12L+length
        require(consumed<=MAX_COLAB_RESULT_BYTES) { "Result exceeds import budget." }
        val model=readPacket(length)
        require((mode==RigMode.NONE)==(model.rig==null)) { "Rig variant does not match payload." }
        result[mode]=model
    }
    require(RigMode.NONE in result && atEnd()) { "Missing unrigged result or trailing data." }
    return result.toMap()
}

/** Browser adapter: validate the same bounded packets, including the original GLB. */
fun readColabResultBytes(bytes:ByteArray,verifyGlb:(GeneratedModel)->Unit):Map<RigMode,GeneratedModel> {
    require(bytes.size.toLong() in 1..MAX_COLAB_RESULT_BYTES) { "Result exceeds import budget." }
    val input=ArrayPacketReader(bytes)
    return readColabEnvelope(input::readInt,input::readLong,{ length->
        readModelPacketPayload(input.slice(length),true,verifyGlb)
    },{input.remaining==0L})
}
