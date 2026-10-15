package com.axiel7.moelist.diagnostics

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.axiel7.moelist.screens.more.settings.diagnostics.DiagnosticsContent
import com.axiel7.moelist.ui.theme.MoeListTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DiagnosticsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun uploadRequiresAPreviewAndNeverRunsWhenOpeningTheScreen() {
        var uploads = 0
        var shares = 0
        val preview = mutableStateOf<String?>(null)
        compose.setContent { MoeListTheme {
            DiagnosticsContent(true, false, preview.value, "https://logs.example.com", "", "https://logs.example.com", null,
                {}, { preview.value = "{\"schema\":1}" }, { shares++ }, { uploads++ }, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Send preview to server").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, uploads); assertEquals(0, shares) }
        compose.onNodeWithText("Preview report").performScrollTo().performClick()
        compose.onNodeWithText("Share report file").performScrollTo().performClick()
        compose.onNodeWithText("Send preview to server").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, shares); assertEquals(1, uploads) }
    }

    @Test fun missingServerCannotUploadAndSavedServerCanBeRemoved() {
        val configured = mutableStateOf<String?>("https://logs.example.com")
        compose.setContent { MoeListTheme {
            DiagnosticsContent(false, false, "{\"schema\":1}", "", "", configured.value, null,
                {}, {}, {}, {}, {}, {}, {}, {}, { configured.value = null }, {})
        } }
        compose.onNodeWithText("Remove server").performScrollTo().performClick()
        compose.onNodeWithText("Send preview to server").assertDoesNotExist()
        compose.onNodeWithText("Save server").performScrollTo().assertIsNotEnabled()
    }
}
