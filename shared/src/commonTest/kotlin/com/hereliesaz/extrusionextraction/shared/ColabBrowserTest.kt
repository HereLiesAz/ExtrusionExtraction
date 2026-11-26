package com.hereliesaz.extrusionextraction.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ColabBrowserTest {
    @Test fun panelLaunchDoesNotOpenAnotherBrowser() {
        val calls=mutableListOf<String>()
        val surface=launchColabBrowser(ColabBrowserRequest.PANEL,
            {calls.add("panel");true},{calls.add("browser")})
        assertEquals(listOf("panel"),calls)
        assertEquals(ColabBrowserSurface.CUSTOM_TAB,surface)
    }

    @Test fun unavailablePanelFallsBackToBrowser() {
        val calls=mutableListOf<String>()
        val surface=launchColabBrowser(ColabBrowserRequest.PANEL,
            {calls.add("panel");false},{calls.add("browser")})
        assertEquals(listOf("panel","browser"),calls)
        assertEquals(ColabBrowserSurface.BROWSER,surface)
    }

    @Test fun explicitBrowserBypassesPanel() {
        val calls=mutableListOf<String>()
        val surface=launchColabBrowser(ColabBrowserRequest.BROWSER,
            {error("Panel must not open")},{calls.add("browser")})
        assertEquals(listOf("browser"),calls)
        assertEquals(ColabBrowserSurface.BROWSER,surface)
    }

    @Test fun failedLaunchDoesNotReportSuccess() {
        assertFailsWith<IllegalStateException>{
            launchColabBrowser(ColabBrowserRequest.PANEL,{false},{error("No browser")})
        }
    }

    @Test fun notebookIsThePublicCanonicalColabPage() {
        assertEquals("https://colab.research.google.com/github/HereLiesAz/ExtrusionExtraction/blob/main/notebooks/ExtrusionExtraction.ipynb",COLAB_NOTEBOOK_URL)
    }
}
