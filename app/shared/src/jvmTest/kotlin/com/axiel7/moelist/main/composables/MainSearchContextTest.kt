package com.axiel7.moelist.main.composables

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.axiel7.moelist.data.model.media.MediaType
import com.axiel7.moelist.ui.base.navigation.INavigator
import com.axiel7.moelist.ui.base.navigation.NavActionManager
import com.axiel7.moelist.ui.base.navigation.Route
import com.axiel7.moelist.ui.theme.MoeListTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MainSearchContextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchBoxFollowsTabChangesInsteadOfKeepingPreviousMediaType() {
        val tab = mutableStateOf<Route>(Route.Tab.Manga(MediaType.MANGA))
        var openedRoute: Route? = null
        val navigation = NavActionManager(object : INavigator {
            override fun navigate(route: Route) { openedRoute = route }
            override fun goBack() { }
        })
        compose.setContent {
            MoeListTheme {
                MainTopAppBar(null, true, navigation, tab.value.searchMediaType())
            }
        }
        val cases = listOf(
            Route.Tab.Manga(MediaType.MANGA) to MediaType.MANGA,
            Route.Tab.Anime(MediaType.ANIME) to MediaType.ANIME,
            Route.Tab.Manga(MediaType.MANGA) to MediaType.MANGA,
            Route.Tab.Home to MediaType.ANIME,
            Route.Tab.More to MediaType.ANIME,
        )
        for ((selected, expected) in cases) {
            compose.runOnIdle { tab.value = selected; openedRoute = null }
            compose.onNodeWithText("Search").performClick()
            compose.runOnIdle { assertEquals(Route.Search(expected), openedRoute) }
        }
    }
}
