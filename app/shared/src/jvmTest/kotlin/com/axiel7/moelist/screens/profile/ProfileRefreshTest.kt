package com.axiel7.moelist.screens.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.axiel7.moelist.data.model.User
import com.axiel7.moelist.data.model.anime.UserAnimeStatistics
import com.axiel7.moelist.ui.base.model.ListStatus
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
class ProfileRefreshTest {
    @get:Rule
    val compose = createComposeRule()

    private val user = User(
        name = "Test viewer",
        animeStatistics = UserAnimeStatistics(
            numItemsWatching = 1, numItemsCompleted = 2,
            numItemsOnHold = 0, numItemsDropped = 0, numItemsPlanToWatch = 0,
            numItems = 3, numDaysWatched = 12f,
            numDaysWatching = null, numDaysCompleted = null,
            numDaysOnHold = null, numDaysDropped = null, numDays = null,
            numEpisodes = 8, numTimesRewatched = 0, meanScore = 7.5f,
        ),
    )

    @Test
    fun cachedRefreshKeepsTextUnshadedAndCategoryClickable() {
        val state = mutableStateOf(ProfileUiState(user = user, isLoading = false))
        var openedRoute: Route? = null
        val navigation = NavActionManager(object : INavigator {
            override fun navigate(route: Route) { openedRoute = route }
            override fun goBack() { }
        })
        compose.setContent {
            MoeListTheme(darkTheme = true) {
                ProfileViewContent(state.value, null, true, navigation)
            }
        }
        val number = compose.onNodeWithText("12", useUnmergedTree = true)
        val clearBackground = number.captureToImage().toPixelMap()[0, 0]

        // A resume-triggered refresh must preserve both the text and its tap targets.
        compose.runOnIdle { state.value = state.value.copy(isLoading = true) }
        compose.onNodeWithText("Completed").assertIsEnabled()
        assertEquals(clearBackground, number.captureToImage().toPixelMap()[0, 0])
        compose.onNodeWithText("Completed").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Route.AnimeCategory(ListStatus.COMPLETED), openedRoute) }

        // A failed refresh must also preserve the last successful profile.
        compose.runOnIdle { state.value = state.value.copy(isLoading = false) }
        number.performScrollTo()
        assertEquals(clearBackground, number.captureToImage().toPixelMap()[0, 0])
        compose.onNodeWithText("Your watch time").assertIsEnabled()
    }

    @Test
    fun firstLoadDoesNotEnableCategoriesBeforeStatsArrive() {
        compose.setContent {
            MoeListTheme {
                ProfileViewContent(ProfileUiState(), null, true,
                    NavActionManager.rememberNavActionManager())
            }
        }
        compose.onNodeWithText("Completed").assertIsNotEnabled()
    }

    @Test
    fun illustratedProfileStaysReadableAndNavigableInBothThemes() {
        val dark = mutableStateOf(true)
        val illustratedUser = user.copy(name = "Anime fan", animeStatistics = user.animeStatistics!!.copy(
            numItemsCompleted = 176, numItemsWatching = 12, numItemsPlanToWatch = 45,
            numItemsOnHold = 8, numItemsDropped = 5, numItems = 246,
            numDaysWatched = 58f, numEpisodes = 3482, numTimesRewatched = 12, meanScore = 7.8f))
        var openedRoute: Route? = null
        val navigation = NavActionManager(object : INavigator {
            override fun navigate(route: Route) { openedRoute = route }
            override fun goBack() { }
        })
        compose.setContent {
            MoeListTheme(darkTheme = dark.value) {
                Box(Modifier.width(390.dp).fillMaxHeight().testTag("profile-preview")) {
                    ProfileViewContent(ProfileUiState(user = illustratedUser, isLoading = false), null, true, navigation)
                }
            }
        }
        for (theme in listOf("dark", "light")) {
            compose.runOnIdle { dark.value = theme == "dark" }
            compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
                it(0f, -10000f)
            }
            compose.waitForIdle()
            capture("profile-$theme-top")
            compose.onNodeWithText("Plan to Watch").performScrollTo().assertIsEnabled()
            capture("profile-$theme-collection")
            compose.onNodeWithText("Watching").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(Route.AnimeCategory(ListStatus.WATCHING), openedRoute) }
            compose.onNodeWithText("Your watch time").performScrollTo().performClick()
            compose.onNodeWithText("OK").performClick()
            compose.waitForIdle()
        }
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("profile-preview").captureToImage()
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/profile-preview").apply { mkdirs() }
        ImageIO.write(output, "png", File(directory, "$name.png"))
    }
}
