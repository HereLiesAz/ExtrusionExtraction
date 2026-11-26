package com.hereliesaz.extrusionextraction.shared

/** Measured bytes, or an indeterminate operation when the source has no known length. */
data class TransferProgress(val label:String, val completed:Long, val total:Long?) {
    val fraction:Float? get() = total?.takeIf { it>0 }?.let { (completed.toDouble()/it).coerceIn(0.0,1.0).toFloat() }
}
