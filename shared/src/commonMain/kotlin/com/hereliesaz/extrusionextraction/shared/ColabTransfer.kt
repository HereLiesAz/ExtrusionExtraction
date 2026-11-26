package com.hereliesaz.extrusionextraction.shared

/** Scope used for the user's explicit Google consent. No Drive or identity access is requested. */
const val COLAB_OAUTH_SCOPE="https://www.googleapis.com/auth/colaboratory"

/** Existing runtime; no allocation or deletion is performed by the transfer workflow. */
data class ColabRuntime(val name:String,val description:String)

/** Last observed notebook state, not proof that a worker is still alive. */
enum class ColabJobState(val label:String) {
    READY("Waiting for job"), QUEUED("Job uploaded"), CLAIMED("Waiting for execution"),
    RUNNING("Reconstructing"), SUCCEEDED("Completed"), PARTIAL("Completed with errors"),
    FAILED("Failed"), CANCELLED("Cancelled"), AMBIGUOUS("Multiple submissions")
}

/** Server-observed status. Cancellation requests are distinct from confirmed cancellation. */
data class ColabSnapshot(val state:ColabJobState,val reportedAt:String,val detail:String,
                         val resultAvailable:Boolean,val cancelRequested:Boolean) {
    val canSubmit:Boolean get()=state==ColabJobState.READY && !cancelRequested
    val canCancel:Boolean get()=state in setOf(ColabJobState.QUEUED,ColabJobState.CLAIMED,ColabJobState.RUNNING) && !cancelRequested
}

/** A missing runtime or pairing is not proof of why it disappeared. */
class ColabSessionUnavailable(message:String):IllegalStateException(message)

/** Credentials remain inside the platform transport and are never stored in workspace state. */
interface ColabTransfer {
    suspend fun runtimes():List<ColabRuntime>
    suspend fun pair(runtime:ColabRuntime,code:String)
    suspend fun upload(runtime:ColabRuntime,code:String,job:ColabJob)
    suspend fun download(runtime:ColabRuntime,code:String):Map<RigMode,GeneratedModel>
    suspend fun snapshot(runtime:ColabRuntime,code:String):ColabSnapshot
    suspend fun cancel(runtime:ColabRuntime,code:String)
}

/** Canonical one-time notebook directory. Reject traversal and arbitrary user-selected paths. */
internal fun colabPairPath(code:String):String {
    require(Regex("[0-9a-f]{32}").matches(code)) { "Enter the notebook's 32-character pairing code." }
    return "content/ee-transfer-$code"
}

/** Reconcile the notebook's last report with the files actually observed in its pairing directory.
 * A ready report can precede upload/claim; terminal reports are never reclassified as active.
 */
internal fun observedColabSnapshot(reported:ColabJobState,timestamp:String,detail:String,names:List<String>):ColabSnapshot {
    val pending=names.count{it.startsWith("job-") && it.endsWith(".eejob")}
    val state=if(reported==ColabJobState.READY) when {
        "claimed.eejob" in names -> ColabJobState.CLAIMED
        pending>1 -> ColabJobState.AMBIGUOUS
        pending==1 -> ColabJobState.QUEUED
        else -> reported
    } else reported
    return ColabSnapshot(state,timestamp,if(state==reported)detail else state.label,
        "reconstruction.eeresult" in names,"cancel.txt" in names)
}
