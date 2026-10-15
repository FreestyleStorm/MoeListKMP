package com.axiel7.moelist.screens.assistant

import com.axiel7.moelist.data.model.Paging
import com.axiel7.moelist.data.model.Response
import com.axiel7.moelist.data.model.anime.*
import com.axiel7.moelist.data.model.media.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class RecommendationEngineTest {
    private fun anime(id: Int, genre: String = "Adventure", episodes: Int? = 12,
        format: MediaFormat = MediaFormat.TV) = AnimeDetails(id = id, title = "Anime $id",
        genres = listOf(Genre(id = id, name = genre)), numEpisodes = episodes, mediaFormat = format, nsfw = "white")
    private fun entry(id: Int, status: ListStatusDto = ListStatusDto.COMPLETED, score: Int = 0) =
        UserAnimeList(AnimeNode(id, "Anime $id"), MyAnimeListStatus(status, score))

    @Test fun historyIncludesAllPagesAndStatusesAndDeduplicates() = runBlocking {
        val calls = mutableListOf<Pair<ListStatusDto, String?>>()
        val result = importHistory { status, page ->
            calls += status to page
            if (status == ListStatusDto.COMPLETED && page == null) Response(listOf(entry(1)), Paging(next = "next"))
            else if (status == ListStatusDto.COMPLETED) Response(listOf(entry(1), entry(2)))
            else Response(listOf(entry(status.ordinal + 20, status)))
        }
        assertEquals(6, result.size)
        assertTrue(result.any { it.node.id == 2 })
        assertEquals(5, calls.map { it.first }.distinct().size)
    }

    @Test fun failedHistoryPageNeverBecomesACompleteProfile() = runBlocking {
        assertTrue(runCatching { importHistory { _, page ->
            if (page == null) Response(listOf(entry(1)), Paging(next = "next"))
            else Response<List<UserAnimeList>>(error = "offline")
        } }.isFailure)
    }

    @Test fun repeatedHistoryPageIsRejected() = runBlocking {
        assertTrue(runCatching { importHistory { _, _ -> Response(listOf(entry(1)), Paging(next = "same")) } }.isFailure)
    }

    @Test fun watchedDroppedAndUnknownLengthsStayExcludedUnderStrictLimits() {
        val history = listOf(entry(2), entry(3, ListStatusDto.DROPPED), entry(4, ListStatusDto.PLAN_TO_WATCH))
        val candidates = listOf(anime(2), anime(3), anime(4), anime(5, episodes = null), anime(6, episodes = 24),
            anime(7, format = MediaFormat.MOVIE), anime(8, genre = "Horror"))
        val result = rankAnime(candidates, history, emptyList(), null,
            AnimeRequest(maxEpisodes = 23, format = "series", avoidGenres = listOf("Horror")))
        assertEquals(listOf(4), result.map { it.anime.id })
    }

    @Test fun ratingsPersonalizeResultsAndUnratedShowsRemainNeutral() {
        val history = listOf(entry(1, score = 10), entry(2, score = 2), entry(3, score = 0))
        val favorites = listOf(anime(1, "Drama"), anime(2, "Comedy"), anime(3, "Horror"))
        val result = rankAnime(listOf(anime(9, "Horror"), anime(8, "Comedy"), anime(7, "Drama")),
            history, favorites, null, AnimeRequest())
        assertEquals(7, result.first().anime.id)
        assertTrue(result.first().evidence.contains("higher-rated"))
        assertFalse(result.first { it.anime.id == 9 }.evidence.contains("higher-rated"))
    }

    @Test fun inventedOrDuplicateModelTitlesCannotReachTheUi() {
        val matches = rankAnime(listOf(anime(2)), emptyList(), emptyList(), null, AnimeRequest())
        assertTrue(runCatching { validateSelection("[99999]", matches) }.isFailure)
        assertTrue(runCatching { validateSelection("[2,2]", matches) }.isFailure)
        assertEquals(2, validateSelection("```json\n[2]\n```", matches).single().anime.id)
    }

    @Test fun invalidConstraintsOrInventedExclusionsAreRejected() {
        assertTrue(runCatching { parseAnimeRequest("{\"maxEpisodes\":-1}", emptySet()) }.isFailure)
        assertTrue(runCatching { parseAnimeRequest("{\"format\":\"anything\"}", emptySet()) }.isFailure)
        assertTrue(runCatching { parseAnimeRequest("{\"excludedIds\":[999]}", emptySet()) }.isFailure)
    }

    @Test fun followUpsUsePriorConstraintsAndShownTitlesWithRealCatalogResults() = runBlocking {
        val responses = ArrayDeque(listOf("{\"seedTitle\":\"Anime 1\"}", "[2,3,4]", "[2,4]", "[4]"))
        val prompts = mutableListOf<String>()
        val nano = object : NanoClient {
            override suspend fun availability() = NanoAvailability.READY
            override suspend fun download(onProgress: (String) -> Unit) = Unit
            override suspend fun generate(prompt: String): String { prompts += prompt; return responses.removeFirst() }
            override fun close() = Unit
        }
        val catalog = object : AssistantCatalog {
            override suspend fun history() = listOf(entry(1, score = 10), entry(5, ListStatusDto.WATCHING))
            override suspend fun search(title: String) = listOf(AnimeNode(1, "Anime 1"))
            override suspend fun details(id: Int) = anime(id, episodes = if (id == 3) 36 else 12)
            override suspend fun suggestions() = listOf(2, 3, 4, 5).map { AnimeNode(it, "Anime $it") }
            override fun clear() = Unit
        }
        val conversation = AssistantConversation(nano, catalog)
        val first = conversation.respond("like Anime 1", AnimeRequest(), emptyList()) {}
        val second = conversation.respond("under 24 episodes", first.request, first.matches) {}
        val third = conversation.respond("I already saw the first one", second.request, second.matches) {}
        assertEquals(listOf(2, 4), second.matches.map { it.anime.id })
        assertEquals(listOf(4), third.matches.map { it.anime.id })
        assertEquals(2, third.imported)
        assertEquals(1, third.rated)
        assertTrue(prompts[2].contains("\"maxEpisodes\":23"))
        assertTrue(prompts[2].contains("Anime 2"))
    }
}
