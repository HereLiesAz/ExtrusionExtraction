package com.hereliesaz.extrusionextraction.shared

private const val JOB_MAGIC=0x45454a31 // ASCII EEJ1
private const val HEADER_BYTES=16
/** Maximum serialized size, enforced before allocating untrusted reference arrays. */
const val MAX_COLAB_JOB_BYTES=HEADER_BYTES+MAX_REFERENCES*IMAGE_SIZE*IMAGE_SIZE*4

/** Immutable job settings and ordered inference-grid references. No account data or original filenames. */
class ColabJob(val images:List<ViewImage>,val cells:Int,val mask:Boolean,val template:Boolean,val learned:Boolean) {
    init {
        require(images.size in VIEW_COUNT..MAX_REFERENCES) { "Invalid reference count." }
        require(cells in 32..96) { "Invalid mesh detail." }
    }
}

/** EEJ1 wire format: big-endian magic, cells, flags, count, then exact ARGB32 pixels in reference order.
 * Flags: 1=automatic foreground, 2=template rig, 4=learned rig. No executable content.
 */
fun encodeColabJob(job:ColabJob):ByteArray {
    val result=ByteArray(HEADER_BYTES+job.images.size*IMAGE_SIZE*IMAGE_SIZE*4)
    var offset=0
    fun write(value:Int) {
        for(shift in 24 downTo 0 step 8) result[offset++]=(value ushr shift).toByte()
    }
    write(JOB_MAGIC);write(job.cells)
    write((if(job.mask)1 else 0) or (if(job.template)2 else 0) or (if(job.learned)4 else 0))
    write(job.images.size)
    for(image in job.images) for(pixel in image.argb) write(pixel)
    return result
}

/** Reject unknown versions, flag bits, out-of-range settings and truncated/trailing data before decoding. */
fun decodeColabJob(bytes:ByteArray):ColabJob {
    require(bytes.size in HEADER_BYTES..MAX_COLAB_JOB_BYTES) { "Invalid job size." }
    var offset=0
    fun read():Int {
        var result=0
        repeat(4){ result=(result shl 8) or (bytes[offset++].toInt() and 255) }
        return result
    }
    require(read()==JOB_MAGIC) { "Unsupported job format." }
    val cells=read();val flags=read();val count=read()
    require(cells in 32..96 && flags in 0..7 && count in VIEW_COUNT..MAX_REFERENCES) { "Invalid job header." }
    require(bytes.size==HEADER_BYTES+count*IMAGE_SIZE*IMAGE_SIZE*4) { "Job length mismatch." }
    val images=List(count){i->ViewImage("Reference ${i+1}",IntArray(IMAGE_SIZE*IMAGE_SIZE){read()})}
    return ColabJob(images,cells,flags and 1!=0,flags and 2!=0,flags and 4!=0)
}
