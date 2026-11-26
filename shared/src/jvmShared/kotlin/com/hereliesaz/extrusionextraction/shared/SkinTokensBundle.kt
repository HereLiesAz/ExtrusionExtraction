package com.hereliesaz.extrusionextraction.shared

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Immutable identities from the publisher's verified SHA256SUMS commit,
 * cross-checked with MANIFEST.json and each F16 file's SHA256 metadata.
 * Neither the downloader nor the installed model may supply its own trust pins.
 */
internal const val SKIN_TOKENS_REVISION = "2c55d38ffe01871c6956103926cc4a40bd7acc8a"
internal data class SkinTokensComponent(val name:String,val bytes:Long,val sha256:String)
internal val skinTokensComponents = listOf(
    SkinTokensComponent("mesh-encoder.gguf",57813184L,"532710809e3db6c54389dd6489c2aa3c768244868b9c197198d06fa664214527"),
    SkinTokensComponent("tokenrig.gguf",948562656L,"933529dc0e550fd499e2e1ae1c574c6d5852047c04832e7207304e184901ed82"),
    SkinTokensComponent("skin-vae.gguf",244014784L,"dcd5859ac89bae62bcfd6b7823f9d0e2393b22364bcb19f84c1109af8e07455e")
)

/** Verify exact length and digest while optionally copying into a private staging file.
 * The caller owns both streams; no partially verified data may be published.
 */
internal fun verifySkinTokensStream(input:InputStream,component:SkinTokensComponent,
                                    output:OutputStream?=null,transfer:(Long)->Unit={},checkpoint:()->Unit={}) {
    val digest=MessageDigest.getInstance("SHA-256")
    val buffer=ByteArray(1024*1024)
    var total=0L
    while(true) {
        checkpoint()
        val n=input.read(buffer)
        if(n<0) break
        total+=n
        require(total<=component.bytes) { "GGUF length mismatch: ${component.name}" }
        digest.update(buffer,0,n)
        output?.write(buffer,0,n)
        transfer(total)
    }
    require(total==component.bytes) { "GGUF length mismatch: ${component.name}" }
    val actual=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    require(actual==component.sha256) { "GGUF checksum mismatch: ${component.name}" }
}

/** Cheap readiness check; cryptographic verification still precedes every inference. */
internal fun skinTokensBundlePresent(folder:File):Boolean = skinTokensComponents.all {
    val file=File(folder,it.name)
    file.isFile && file.length()==it.bytes
}
