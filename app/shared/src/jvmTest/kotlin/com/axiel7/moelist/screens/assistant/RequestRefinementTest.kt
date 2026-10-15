package com.axiel7.moelist.screens.assistant

import com.axiel7.moelist.data.model.anime.*
import com.axiel7.moelist.data.model.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RequestRefinementTest {
    private fun anime(id: Int, episodes: Int? = 12) = AnimeDetails(id = id, title = "Anime $id", numEpisodes = episodes,
        genres = listOf(Genre(1, "Adventure")), mediaFormat = MediaFormat.TV, nsfw = "white")
    private fun match(id: Int) = AnimeMatch(anime(id), "MAL metadata", 1.0)

    @Test fun episodeComparatorsAreInclusiveOrExclusiveAsRequested() {
        val cases = listOf(
            "more than 26 episodes" to (27 to null), "over 26" to (27 to null),
            "at least 26 episodes" to (26 to null), "26+ episodes" to (26 to null),
            "under 24 episodes" to (null to 23), "fewer than 24 episodes" to (null to 23),
            "at most 24 episodes" to (null to 24), "no more than 24 episodes" to (null to 24),
            "no less than 24 episodes" to (24 to null), "24 or fewer episodes" to (null to 24),
            "exactly 26 episodes" to (26 to 26), "between 27 and 50 episodes" to (27 to 50),
            "27–50 episodes" to (27 to 50), "at least 12 but under 24 episodes" to (12 to 23))
        for ((text, expected) in cases) {
            val result = episodeEdit(text)
            assertTrue(text, result.mentioned)
            assertEquals(text, expected.first, result.minimum)
            assertEquals(text, expected.second, result.maximum)
        }
    }

    @Test fun badRangesAndUnsupportedLimitsAskForClarificationInsteadOfIgnoringThem() {
        for (text in listOf("more than 2000 episodes", "under 1 episode", "between 50 and 27 episodes",
            "at least 50 episodes and under 24 episodes", "less than thirty episodes", "over 999999999999999999999 episodes")) {
            assertTrue(text, runCatching { episodeEdit(text) }.exceptionOrNull() is AssistantRequestError)
        }
        assertFalse(episodeEdit("I rated it over 8 out of 10").mentioned)
        val mixed = episodeEdit("I want protagonists over 18 years old and under 24 episodes")
        assertNull(mixed.minimum)
        assertEquals(23, mixed.maximum)
    }

    @Test fun modelCannotEraseTheSeedOrInventPreferencesOnAFollowUp() {
        val previous = AnimeRequest(seedTitle = "Made in Abyss", seedId = 34599, maxEpisodes = 23, format = "series",
            avoidGenres = listOf("Comedy"), excludedIds = listOf(7), userPreferences = listOf("Keep the exploration."))
        val result = refineRequest("less disturbing", previous, listOf(match(8)),
            """{"seedTitle":null,"clearSeed":true,"format":"any","maxEpisodes":null,"genres":["Horror"],"qualities":["More kids"]}""")
        assertEquals("Made in Abyss", result.seedTitle)
        assertEquals(34599, result.seedId)
        assertEquals(23, result.maxEpisodes)
        assertEquals("series", result.format)
        assertEquals(listOf("Comedy"), result.avoidGenres)
        assertEquals(listOf(7), result.excludedIds)
        assertTrue(result.genres.isEmpty())
        assertTrue(result.qualities.isEmpty())
        assertEquals(listOf("Keep the exploration.", "less disturbing"), result.userPreferences)
    }

    @Test fun newLengthReplacesOldLengthWhileOtherConstraintsAndTheSeedStay() {
        val previous = AnimeRequest(seedTitle = "Anime 1", seedId = 1, maxEpisodes = 23, format = "series")
        val next = refineRequest("more than 26 episodes", previous, emptyList(), "{}")
        assertEquals(27, next.minEpisodes)
        assertNull(next.maxEpisodes)
        assertEquals(1, next.seedId)
        assertEquals("series", next.format)
        val candidates = listOf(anime(2, 26), anime(3, 23), anime(4, 13), anime(5, 27), anime(6, 52), anime(7, null), anime(8, 0))
        assertEquals(setOf(5, 6), rankAnime(candidates, emptyList(), emptyList(), anime(1), next).map { it.anime.id }.toSet())
        assertTrue(requestSummary(next).contains("27+ episodes"))
        assertTrue(next.userPreferences.isEmpty())
    }

    @Test fun removingLimitsOrChangingTheComparisonRequiresTheUserToAsk() {
        val previous = AnimeRequest(seedTitle = "Anime 1", seedId = 1, minEpisodes = 27, maxEpisodes = 60)
        val noLimits = refineRequest("Remove episode limits.", previous, emptyList(), "{}")
        assertNull(noLimits.minEpisodes); assertNull(noLimits.maxEpisodes); assertEquals(1, noLimits.seedId)
        val newSeed = refineRequest("Try something like Anime 2", previous, emptyList(), "{\"seedTitle\":\"Anime 2\"}")
        assertEquals("Anime 2", newSeed.seedTitle); assertNull(newSeed.seedId)
        val invented = refineRequest("Keep the exploration", previous, emptyList(), "{\"seedTitle\":\"Anime 999\"}")
        assertEquals(1, invented.seedId)
        val truncated = refineRequest("like Anime 10", previous.copy(seedTitle = "Other anime", seedId = 99), emptyList(), "{\"seedTitle\":\"Anime 1\"}")
        assertEquals(99, truncated.seedId)
        val personalized = refineRequest("like Anime 1, based on my ratings", previous, emptyList(), "{\"seedTitle\":\"Anime 1\"}")
        assertEquals(1, personalized.seedId)
        val merelyMentionedMovie = refineRequest("Keep the feeling of that movie", previous.copy(format = "series"), emptyList(), "{\"format\":\"movie\"}")
        assertEquals("series", merelyMentionedMovie.format)
        val noMovies = refineRequest("No movies, please", previous.copy(format = "any"), emptyList(), "{}")
        assertEquals("series", noMovies.format)
        val general = refineRequest("Remove comparison title.", previous, emptyList(), "{}")
        assertNull(general.seedTitle); assertNull(general.seedId); assertEquals(27, general.minEpisodes)
    }

    @Test fun onlyTheActuallyRejectedShownTitleIsExcluded() {
        val previous = AnimeRequest(excludedIds = listOf(9))
        val result = refineRequest("I already saw the first one", previous, listOf(match(2), match(3)), "{\"excludedIds\":[3]}")
        assertEquals(listOf(9, 2), result.excludedIds)
        assertTrue(runCatching { refineRequest("I saw the first one", previous, listOf(match(2)), "{\"excludedIds\":[999]}") }.isFailure)
    }

    @Test fun ambiguousKidsPreferenceDoesNotInvokeNanoOrChangeFilters() = runBlocking {
        val nano = fakeNano { error("Clarification must not call the model") }
        val catalog = object : AssistantCatalog {
            override suspend fun history(): List<UserAnimeList> = error("No import needed")
            override suspend fun search(title: String): List<AnimeNode> = error("No search needed")
            override suspend fun details(id: Int): AnimeDetails? = error("No details needed")
            override suspend fun suggestions(): List<AnimeNode> = error("No suggestions needed")
            override fun clear() = Unit
        }
        val previous = AnimeRequest(seedTitle = "Anime 1", seedId = 1, minEpisodes = 27)
        val result = AssistantConversation(nano, catalog).respond("anything with less kids", previous, listOf(match(2))) {}
        assertEquals(previous, result.request)
        assertEquals(2, result.clarifications.size)
        assertTrue(result.response.contains("child main characters"))
        assertFalse(ambiguousChildren("fewer child protagonists"))
    }

    @Test fun candidatePoolIncludesPersonalSuggestionsEvenWhenSeedHasManyLinks() {
        val ids = balancedCandidateIds(listOf((1..100).toList(), listOf(201, 202, 203), listOf(301, 302)), setOf(1, 2))
        assertEquals(30, ids.size)
        assertTrue(ids.containsAll(listOf(201, 202, 203, 301, 302)))
        assertFalse(ids.contains(1)); assertFalse(ids.contains(2))
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun screenshotSequenceKeepsResolvedSeedAndNeverReturnsShortOrUnknownLengths() = runBlocking {
        val outputs = ArrayDeque(listOf("{\"seedTitle\":\"Anime 1\",\"qualities\":[\"Epic\"]}", "[2,3,4]", "[2,3]", "[3]"))
        val prompts = mutableListOf<String>()
        val nano = fakeNano { prompt -> prompts += prompt; outputs.removeFirst() }
        var searches = 0
        val catalog = object : AssistantCatalog {
            override suspend fun history() = listOf(UserAnimeList(AnimeNode(1, "Anime 1"), MyAnimeListStatus(ListStatusDto.COMPLETED, 10)))
            override suspend fun search(title: String): List<AnimeNode> { searches++; return listOf(AnimeNode(1, "Anime 1")) }
            override suspend fun details(id: Int) = anime(id, when(id) { 2 -> 26; 3 -> 52; else -> 13 })
            override suspend fun suggestions() = listOf(2, 3, 4).map { AnimeNode(it, "Anime $it") }
            override fun clear() = Unit
        }
        val conversation = AssistantConversation(nano, catalog)
        val first = conversation.respond("I loved Anime 1. Find something similar.", AnimeRequest(), emptyList()) {}
        val question = conversation.respond("anything with less kids", first.request, first.matches) {}
        val cast = conversation.respond(question.clarifications.first(), question.request, first.matches) {}
        val longer = conversation.respond("more than 26 episodes", cast.request, cast.matches) {}
        assertEquals(1, searches)
        assertEquals(1, longer.request.seedId)
        assertEquals(27, longer.request.minEpisodes)
        assertEquals(listOf(3), longer.matches.map { it.anime.id })
        assertTrue(prompts[2].contains("Prefer fewer child protagonists"))
        assertTrue(longer.request.qualities.isEmpty())
        assertEquals(4, prompts.size) // Interpretation once, then three comparisons.
    }

    @Test fun noConfidentMatchCanReturnEmptyWithoutForcingFiveSuggestions() {
        assertTrue(validateSelection("[]", listOf(match(2))).isEmpty())
        assertTrue(runCatching { validateSelection("[999]", listOf(match(2))) }.isFailure)
        assertTrue(runCatching { refineRequest("like a show", AnimeRequest(), emptyList(), "not JSON") }.exceptionOrNull() is AssistantRequestError)
    }

    private fun fakeNano(generate: (String) -> String) = object : NanoClient {
        override suspend fun availability() = NanoAvailability.READY
        override suspend fun download(onProgress: (String) -> Unit) = Unit
        override suspend fun generate(prompt: String) = generate.invoke(prompt)
        override fun close() = Unit
    }
}
