package com.hereliesaz.extrusionextraction

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.hereliesaz.extrusionextraction.shared.COLAB_NOTEBOOK_URL
import com.hereliesaz.extrusionextraction.shared.ColabBrowserRequest
import com.hereliesaz.extrusionextraction.shared.ColabBrowserSurface
import com.hereliesaz.extrusionextraction.shared.launchColabBrowser

/** Use a real browser-controlled Custom Tab, retaining its cookies, origin and native controls.
 * Partial dimensions are requests: browsers may choose full-screen. Never use a WebView for
 * Google authentication or inject scripts into the notebook.
 */
internal fun openColabNotebook(activity:Activity,request:ColabBrowserRequest):ColabBrowserSurface {
    val uri=Uri.parse(COLAB_NOTEBOOK_URL)
    return launchColabBrowser(request,openPanel={
        val provider=CustomTabsClient.getPackageName(activity,null)
        if(provider==null) false else {
            val metrics=activity.resources.displayMetrics
            val configuration=activity.resources.configuration
            val builder=CustomTabsIntent.Builder().setShowTitle(true)
            if(configuration.screenHeightDp>0) {
                val height=(configuration.screenHeightDp*metrics.density*.82f).toInt().coerceAtLeast(1)
                builder.setInitialActivityHeightPx(height,CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
            }
            if(configuration.screenWidthDp>=600) {
                val width=(configuration.screenWidthDp*metrics.density*.72f).toInt().coerceAtLeast(1)
                builder.setInitialActivityWidthPx(width)
            }
            val tab=builder.build()
            tab.intent.setPackage(provider)
            try {
                tab.launchUrl(activity,uri)
                true
            } catch(_:ActivityNotFoundException) { false }
            catch(_:SecurityException) { false }
        }
    },openBrowser={
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch(e:ActivityNotFoundException) {
            throw IllegalStateException("No browser can open Colab.",e)
        }
    })
}
