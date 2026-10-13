package com.axiel7.moelist.screens.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
}
