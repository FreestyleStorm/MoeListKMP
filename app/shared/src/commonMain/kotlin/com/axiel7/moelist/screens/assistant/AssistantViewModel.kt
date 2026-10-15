package com.axiel7.moelist.screens.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.moelist.data.model.anime.AnimeNode
import com.axiel7.moelist.data.repository.AnimeRepository
import com.axiel7.moelist.data.repository.DefaultPreferencesRepository
import com.axiel7.moelist.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class AssistantUiState(
    val nano: NanoAvailability = NanoAvailability.CHECKING,
    val status: String = "Checking Gemini Nano…",
    val busy: Boolean = false,
    val messages: List<AssistantMessage> = emptyList(),
    val matches: List<AnimeMatch> = emptyList(),
    val choices: List<AnimeNode> = emptyList(),
    val request: AnimeRequest = AnimeRequest(),
    val imported: Int? = null,
    val rated: Int = 0,
    val error: String? = null,
)

class AssistantViewModel(repository: AnimeRepository, preferences: DefaultPreferencesRepository) : ViewModel() {
    private val nano = createNanoClient()
    private val catalog = MalAssistantCatalog(repository, preferences)
    private val conversation = AssistantConversation(nano, catalog)
    private val mutable = MutableStateFlow(AssistantUiState())
    val uiState = mutable.asStateFlow()
    private var work: Job? = null

    init { checkNano() }

    fun checkNano() {
        if (mutable.value.busy) return
        work = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, status = "Checking Gemini Nano…") }
            try {
                val availability = diagnosticOperation(DiagnosticEvent.NANO_CHECK) { nano.availability() }
                AppDiagnostics.record(DiagnosticEvent.NANO_CHECK, when (availability) {
                    NanoAvailability.READY -> DiagnosticOutcome.OK
                    NanoAvailability.DOWNLOADABLE -> DiagnosticOutcome.DOWNLOADABLE
                    NanoAvailability.DOWNLOADING -> DiagnosticOutcome.DOWNLOADING
                    else -> DiagnosticOutcome.UNAVAILABLE
                })
                mutable.update { it.copy(nano = availability, status = when (availability) {
                    NanoAvailability.READY -> "Gemini Nano is ready. Conversations run on this phone."
                    NanoAvailability.DOWNLOADABLE -> "Download Gemini Nano to start. Wi-Fi is recommended."
                    NanoAvailability.DOWNLOADING -> "Gemini Nano is downloading. Check again when it finishes."
                    else -> "Gemini Nano is unavailable on this device right now. Update Android/AICore and check again."
                }) }
            } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e
                mutable.update { it.copy(nano = NanoAvailability.UNAVAILABLE, status = "Gemini Nano check timed out. Please retry.") }
            } catch (_: Exception) {
                mutable.update { it.copy(nano = NanoAvailability.UNAVAILABLE,
                    status = "Gemini Nano is unavailable on this device right now. Update Android/AICore and check again.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun download() = runWork(DiagnosticEvent.NANO_DOWNLOAD) {
        nano.download { progress -> mutable.update { it.copy(status = progress) } }
        val availability = nano.availability()
        mutable.update { it.copy(nano = availability, status = "Model setup finished. Check its status before sending.") }
    }

    fun send(text: String) {
        if (text.isBlank() || text.length > 600 || mutable.value.nano != NanoAvailability.READY) return
        runWork(DiagnosticEvent.ASSISTANT_REQUEST) {
            val before = mutable.value
            mutable.update { it.copy(messages = (it.messages + AssistantMessage(true, text.trim())).takeLast(20)) }
            val result = withTimeout(180000) {
                conversation.respond(text, before.request, before.matches) { progress -> mutable.update { it.copy(status = progress) } }
            }
            accept(result)
        }
    }

    fun choose(node: AnimeNode) = runWork(DiagnosticEvent.ASSISTANT_REQUEST) {
        val result = withTimeout(180000) {
            conversation.recommend(mutable.value.request.copy(seedTitle = node.title), node.id) { progress -> mutable.update { it.copy(status = progress) } }
        }
        accept(result)
    }

    private fun accept(result: ConversationResult) {
        mutable.update { it.copy(request = result.request, matches = result.matches, choices = result.choices,
            imported = result.imported, rated = result.rated,
            messages = (it.messages + AssistantMessage(false, result.response)).takeLast(20), status = "Ready for your next request.") }
    }

    fun newConversation() {
        if (mutable.value.busy) return
        mutable.update { AssistantUiState(nano = it.nano, status = it.status) }
        catalog.clear()
    }

    fun cancel() { work?.cancel(); mutable.update { it.copy(busy = false, status = "Stopped. Your previous results are still available.") } }

    private fun runWork(event: DiagnosticEvent, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        work = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try { diagnosticOperation(event, block) }
            catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                mutable.update { it.copy(error = "That took too long. Please retry; previous results are unchanged.") }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Couldn't complete this request. Check MAL sign-in, your connection, and Nano availability, then retry. Previous results are unchanged.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() { nano.close(); super.onCleared() }
}
