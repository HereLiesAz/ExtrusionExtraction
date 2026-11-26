package com.hereliesaz.extrusionextraction.shared

/** Scope used for the user's explicit Google consent. No Drive or identity access is requested. */
const val COLAB_OAUTH_SCOPE="https://www.googleapis.com/auth/colaboratory"

/** Existing runtime; no allocation or deletion is performed by the transfer workflow. */
data class ColabRuntime(val name:String,val description:String)

/** Credentials remain inside the platform transport and are never stored in workspace state. */
interface ColabTransfer {
    suspend fun runtimes():List<ColabRuntime>
    suspend fun pair(runtime:ColabRuntime,code:String)
    suspend fun upload(runtime:ColabRuntime,code:String,job:ColabJob)
    suspend fun download(runtime:ColabRuntime,code:String):Map<RigMode,GeneratedModel>
}

/** Canonical one-time notebook directory. Reject traversal and arbitrary user-selected paths. */
internal fun colabPairPath(code:String):String {
    require(Regex("[0-9a-f]{32}").matches(code)) { "Enter the notebook's 32-character pairing code." }
    return "content/ee-transfer-$code"
}
