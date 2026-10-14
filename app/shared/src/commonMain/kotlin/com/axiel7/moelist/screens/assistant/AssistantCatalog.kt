package com.axiel7.moelist.screens.assistant

import com.axiel7.moelist.data.model.Response
import com.axiel7.moelist.data.model.anime.AnimeDetails
import com.axiel7.moelist.data.model.anime.AnimeNode
import com.axiel7.moelist.data.model.anime.UserAnimeList
import com.axiel7.moelist.data.model.media.ListStatusDto
import com.axiel7.moelist.data.model.media.MediaSort
import com.axiel7.moelist.data.repository.AnimeRepository
import com.axiel7.moelist.data.repository.DefaultPreferencesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal suspend fun importHistory(
    fetch: suspend (ListStatusDto, String?) -> Response<List<UserAnimeList>>,
): List<UserAnimeList> {
    val entries = mutableMapOf<Int, UserAnimeList>()
    for (status in listOf(ListStatusDto.COMPLETED, ListStatusDto.WATCHING, ListStatusDto.PLAN_TO_WATCH,
        ListStatusDto.ON_HOLD, ListStatusDto.DROPPED)) {
        var page: String? = null
        val visited = mutableSetOf<String>()
        do {
            val response = fetch(status, page)
            check(response.isSuccess) { "Could not load your complete MAL list. Check your connection and sign-in, then retry." }
            response.data.orEmpty().forEach { entries[it.node.id] = it }
            page = response.paging?.next
            check(page == null || (visited.add(page) && visited.size <= 400)) { "MAL returned inconsistent list pages. Please retry." }
        } while (page != null)
    }
    return entries.values.toList()
}

interface AssistantCatalog {
    suspend fun history(): List<UserAnimeList>
    suspend fun search(title: String): List<AnimeNode>
    suspend fun details(id: Int): AnimeDetails?
    suspend fun suggestions(): List<AnimeNode>
    fun clear()
}

internal class MalAssistantCatalog(private val repository: AnimeRepository,
    private val preferences: DefaultPreferencesRepository) : AssistantCatalog {
    private var historyCache: List<UserAnimeList>? = null
    private val detailsCache = mutableMapOf<Int, AnimeDetails>()
    override suspend fun history(): List<UserAnimeList> {
        historyCache?.let { return it }
        return importHistory { status, page -> repository.getUserAnimeList(status, MediaSort.UPDATED, page) }
            .also { historyCache = it }
    }
    override suspend fun search(title: String) = repository.searchAnime(title, 8).let { result ->
        check(result.isSuccess) { "Could not search MAL. Check your connection and retry." }
        result.data.orEmpty().map { it.node }
    }
    override suspend fun details(id: Int): AnimeDetails? {
        detailsCache[id]?.let { return it }
        val result = repository.getAnimeDetails(id) ?: return null
        if (preferences.nsfwInt() == 0 && result.nsfw != "white") return null
        return result.also { detailsCache[id] = it }
    }
    override suspend fun suggestions() = repository.getRecommendedAnimes(25).let { result ->
        check(result.isSuccess) { "Could not load MAL suggestions. Check your connection and retry." }
        result.data.orEmpty().map { it.node }
    }
    override fun clear() { historyCache = null; detailsCache.clear() }
}

data class ConversationResult(
    val request: AnimeRequest, val matches: List<AnimeMatch> = emptyList(),
    val choices: List<AnimeNode> = emptyList(), val imported: Int = 0, val rated: Int = 0,
    val response: String,
)

class AssistantConversation(private val nano: NanoClient, private val catalog: AssistantCatalog) {
    suspend fun respond(text: String, previous: AnimeRequest, shown: List<AnimeMatch>,
        onProgress: (String) -> Unit): ConversationResult {
        onProgress("Understanding your request on this phone…")
        val request = parseAnimeRequest(nano.generate(interpretationPrompt(text, previous, shown)),
            previous.excludedIds.toSet() + shown.map { it.anime.id })
        return recommend(request, onProgress = onProgress)
    }

    suspend fun recommend(request: AnimeRequest, selectedSeedId: Int? = null,
        onProgress: (String) -> Unit): ConversationResult {
        onProgress("Reading your MAL watch history and ratings…")
        val history = catalog.history()
        val rated = history.count { (it.listStatus?.score ?: 0) > 0 }
        var seed: AnimeDetails? = null
        if (selectedSeedId != null) {
            seed = catalog.details(selectedSeedId) ?: error("Could not load that anime's details. Please retry.")
        } else request.seedTitle?.let { title ->
            val nodes = catalog.search(title)
            val exact = nodes.filter { node ->
                listOfNotNull(node.title, node.alternativeTitles?.en, node.alternativeTitles?.ja)
                    .any { it.equals(title, ignoreCase = true) }
            }
            val selected = exact.singleOrNull() ?: nodes.singleOrNull()
            if (selected == null) return ConversationResult(request, choices = nodes.take(5), imported = history.size,
                rated = rated, response = if (nodes.isEmpty()) "I couldn't find that title on MAL. Try its English or Japanese title."
                else "Which title did you mean? Choose one so I compare the right show.")
            seed = catalog.details(selected.id) ?: error("Could not load that anime's details. Please retry.")
        }
        onProgress("Finding titles that fit your history…")
        val favorites = history.filter { (it.listStatus?.score ?: 0) > 0 }
            .sortedByDescending { (it.listStatus?.score ?: 0) * 10 + (it.listStatus?.repeatCount ?: 0).coerceIn(0, 3) }
            .take(6).mapNotNull { catalog.details(it.node.id) }
        val ids = (seed?.recommendations.orEmpty().map { it.node.id } +
            catalog.suggestions().map { it.id } + favorites.flatMap { it.recommendations.orEmpty().map { r -> r.node.id } })
            .distinct().filter { id -> id != seed?.id && history.none { it.node.id == id && it.listStatus?.status != ListStatusDto.PLAN_TO_WATCH } }
            .take(30)
        val candidates = coroutineScope {
            val semaphore = Semaphore(3)
            ids.map { id -> async { semaphore.withPermit { catalog.details(id) } } }.awaitAll().filterNotNull()
        }
        val committed = request.copy(seedTitle = seed?.title ?: request.seedTitle)
        val ranked = rankAnime(candidates, history, favorites, seed, committed).take(10)
        if (ranked.isEmpty()) return ConversationResult(committed, imported = history.size, rated = rated,
            response = "No matches in the current suggestion pool fit those limits. Try a different title or relax a limit.")
        onProgress("Comparing the matches with Gemini Nano…")
        val selection = validateSelection(nano.generate(comparisonPrompt(ranked, seed, committed)), ranked)
        val intro = if (seed == null) "Here are ${selection.size} suggestions using your MAL ratings and watch history."
            else "Here are ${selection.size} comparisons for ${seed?.title}, adjusted to your MAL history."
        val caveat = if (committed.qualities.isEmpty()) "" else
            " Mood preferences (${committed.qualities.joinToString()}) are tentative comparisons from descriptions, not verified content ratings."
        return ConversationResult(committed, matches = selection, imported = history.size, rated = rated, response = intro + caveat)
    }
}
