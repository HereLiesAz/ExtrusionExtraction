package com.hereliesaz.extrusionextraction.shared

/** Canonical, public notebook. Account selection and authentication belong to the user's browser. */
const val COLAB_NOTEBOOK_URL="https://colab.research.google.com/github/HereLiesAz/ExtrusionExtraction/blob/main/notebooks/ExtrusionExtraction.ipynb"

/** The browser launch requested by the user. A panel may fall back to an ordinary browser. */
enum class ColabBrowserRequest { PANEL, BROWSER }

/** Describes only a successfully dispatched launch, never notebook authentication or runtime state.
 * Browsers choose whether a Custom Tab is partial or full-screen.
 */
enum class ColabBrowserSurface(val description:String) {
    CUSTOM_TAB("Colab opened in a browser tab. Expand it or dismiss it to return to the workspace."),
    BROWSER("Colab opened in your browser. Return to its existing tab to resume.")
}

/** Route an explicit browser request directly, or try the browser-owned panel before falling back.
 * The panel adapter returns false when a suitable tab cannot be launched; the browser adapter
 * may throw if no browser is available. Neither path authenticates or claims a running session.
 */
fun launchColabBrowser(request:ColabBrowserRequest,openPanel:()->Boolean,openBrowser:()->Unit):ColabBrowserSurface {
    if(request==ColabBrowserRequest.PANEL && openPanel()) return ColabBrowserSurface.CUSTOM_TAB
    openBrowser()
    return ColabBrowserSurface.BROWSER
}
