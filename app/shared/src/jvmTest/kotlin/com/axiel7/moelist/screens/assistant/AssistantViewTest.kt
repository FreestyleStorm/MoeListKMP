package com.axiel7.moelist.screens.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.axiel7.moelist.data.model.anime.AnimeDetails
import com.axiel7.moelist.data.model.media.MediaType
import com.axiel7.moelist.ui.base.navigation.INavigator
import com.axiel7.moelist.ui.base.navigation.NavActionManager
import com.axiel7.moelist.ui.base.navigation.Route
import com.axiel7.moelist.ui.theme.MoeListTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class AssistantViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unsupportedDeviceCannotSubmitAndCanRetryAvailability() {
        var checked = 0
        compose.setContent { MoeListTheme {
            AssistantViewContent(AssistantUiState(nano = NanoAvailability.UNAVAILABLE,
                status = "Gemini Nano is unavailable on this device right now."), true,
                NavActionManager.rememberNavActionManager(), { error("Should not submit") }, { checked++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Gemini Nano is unavailable on this device right now.").assertIsDisplayed()
        compose.onNodeWithText("Ask for recommendations").performTextInput("like Made in Abyss")
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Check Nano").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checked) }
    }

    @Test fun clarificationKeepsTheComparisonVisibleAndDoesNotPresentOldMatchesAsNew() {
        var sent: String? = null
        val state = mutableStateOf(AssistantUiState(nano = NanoAvailability.READY,
            request = AnimeRequest(seedTitle = "Sample favorite", seedId = 1, minEpisodes = 27),
            clarifications = listOf("Prefer fewer child protagonists. Keep the other preferences.", "Less childish tone. Keep the other preferences."),
            messages = listOf(AssistantMessage(false, "Do you mean fewer child main characters, or a less childish tone?")),
            matches = listOf(AnimeMatch(AnimeDetails(id = 2, title = "Previous match", numEpisodes = 52), "Old result", 1.0))))
        compose.setContent { MoeListTheme { AssistantViewContent(state.value, true,
            NavActionManager.rememberNavActionManager(), { sent = it }, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Previous match").assertDoesNotExist()
        compose.onNodeWithText("Current request: Similar to Sample favorite · 27+ episodes").assertIsDisplayed()
        compose.onNodeWithText("Fewer child protagonists").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Prefer fewer child protagonists. Keep the other preferences.", sent) }
        compose.runOnIdle { state.value = state.value.copy(clarifications = emptyList()) }
        compose.onNodeWithText("Previous match").performScrollTo().assertIsDisplayed()
    }

    @Test fun chatCanOpenRealDetailsAndClearItsHistoryInBothThemes() {
        val dark = mutableStateOf(true)
        val initial = AssistantUiState(nano = NanoAvailability.READY, status = "Ready for your next request.",
            imported = 246, rated = 180,
            messages = listOf(AssistantMessage(true, "Find something like my favorite anime."),
                AssistantMessage(false, "Here is a comparison using your MAL history.")),
            matches = listOf(AnimeMatch(AnimeDetails(id = 100, title = "Sample anime", numEpisodes = 12),
                "Shares Adventure with a show you rated highly. Different genre mix: Mystery is not listed here.", 3.0)))
        val state = mutableStateOf(initial)
        var opened: Route? = null
        val navigation = NavActionManager(object : INavigator {
            override fun navigate(route: Route) { opened = route }
            override fun goBack() = Unit
        })
        compose.setContent { MoeListTheme(darkTheme = dark.value) {
            Box(Modifier.width(390.dp).fillMaxHeight().testTag("assistant-preview")) {
                AssistantViewContent(state.value, true, navigation, {}, {}, {},
                    { state.value = AssistantUiState(nano = NanoAvailability.READY) }, {}, {})
            }
        } }
        for (theme in listOf("dark", "light")) {
            compose.runOnIdle { dark.value = theme == "dark"; state.value = initial }
            compose.onNodeWithText("View / add to list").performScrollTo()
            compose.waitForIdle()
            capture("assistant-$theme-conversation")
            compose.onNodeWithText("View / add to list").performClick()
            compose.runOnIdle { assertEquals(Route.MediaDetails(MediaType.ANIME, 100), opened) }
            compose.onNodeWithText("New chat").performClick()
            compose.onNodeWithText("Sample anime").assertDoesNotExist()
            compose.waitForIdle()
            compose.onNodeWithText("Find your next favorite").assertIsDisplayed()
            capture("assistant-$theme-start")
        }
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("assistant-preview").captureToImage()
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/profile-preview").apply { mkdirs() }
        ImageIO.write(output, "png", File(directory, "$name.png"))
    }
}
