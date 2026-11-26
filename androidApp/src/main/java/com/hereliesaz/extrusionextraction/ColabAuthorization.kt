package com.hereliesaz.extrusionextraction

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.hereliesaz.extrusionextraction.shared.*

/** Request only the Colab scope through Google Identity Services; no server or offline grant. */
@Composable
internal fun rememberColabAuthorization(activity:Activity):((Result<ColabTransfer>)->Unit)->Unit {
    var pending by remember { mutableStateOf<((Result<ColabTransfer>)->Unit)?>(null) }
    val client=remember(activity){Identity.getAuthorizationClient(activity)}
    fun finish(result:Result<ColabTransfer>) {
        val callback=pending;pending=null;callback?.invoke(result)
    }
    val resolution=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result->
        if(result.resultCode!=Activity.RESULT_OK) finish(Result.failure(IllegalStateException("Google authorization cancelled.")))
        else finish(runCatching {
            val grant=client.getAuthorizationResultFromIntent(result.data)
            require(grant.grantedScopes.contains(Scope(COLAB_OAUTH_SCOPE))) { "Colab scope was not granted." }
            GoogleColabTransfer(grant.accessToken ?: error("Google returned no access token."))
        })
    }
    return { callback->
        check(pending==null) { "Google authorization is already in progress." }
        pending=callback
        try {
            client.authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(COLAB_OAUTH_SCOPE))).build())
                .addOnSuccessListener { grant->
                    try {
                        if(grant.hasResolution()) {
                            val intent=grant.pendingIntent ?: error("Google authorization resolution missing.")
                            resolution.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                        } else {
                            require(grant.grantedScopes.contains(Scope(COLAB_OAUTH_SCOPE))) { "Colab scope was not granted." }
                            finish(Result.success(GoogleColabTransfer(grant.accessToken ?: error("Google returned no access token."))))
                        }
                    } catch(e:Exception) { finish(Result.failure(IllegalStateException("Google authorization could not complete.",e))) }
                }.addOnFailureListener { finish(Result.failure(IllegalStateException("Google authorization failed. Verify the registered Android client and Colab API.",it))) }
        } catch(e:Exception) { finish(Result.failure(IllegalStateException("Google authorization unavailable.",e))) }
    }
}
