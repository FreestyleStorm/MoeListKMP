package com.axiel7.moelist.screens.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.moelist.data.model.anime.AnimeNode
import com.axiel7.moelist.data.model.media.MediaType
import com.axiel7.moelist.ui.base.navigation.NavActionManager
import com.axiel7.moelist.ui.composables.DefaultScaffoldWithTopAppBar
import com.axiel7.moelist.ui.composables.PlatformImage
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AssistantView(isLoggedIn: Boolean, navActionManager: NavActionManager) {
    val viewModel: AssistantViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AssistantViewContent(state, isLoggedIn, navActionManager, viewModel::send, viewModel::checkNano,
        viewModel::download, viewModel::newConversation, viewModel::cancel, viewModel::choose)
}

@Composable
internal fun AssistantViewContent(
    state: AssistantUiState, isLoggedIn: Boolean, navActionManager: NavActionManager,
    onSend: (String) -> Unit, onCheck: () -> Unit, onDownload: () -> Unit,
    onClear: () -> Unit, onCancel: () -> Unit, onChoose: (AnimeNode) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val canSend = isLoggedIn && state.nano == NanoAvailability.READY && !state.busy
    fun send() {
        if (canSend && draft.isNotBlank()) { onSend(draft); draft = "" }
    }
    LaunchedEffect(state.messages.size, state.matches, state.choices) {
        if (listState.layoutInfo.totalItemsCount > 0) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
    DefaultScaffoldWithTopAppBar(title = "Anime companion", navigateBack = navActionManager::goBack,
        actions = { TextButton(enabled = !state.busy, onClick = { onClear(); draft = "" }) { Text("New chat") } }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Find your next favorite", style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold)
                                Text("Tell me what you loved, then refine the suggestions together.")
                                Text("On-device AI · Prototype", style = MaterialTheme.typography.labelMedium)
                                Text(state.status, style = MaterialTheme.typography.bodySmall)
                                if (state.imported != null) Text("${state.imported} visible MAL titles · ${state.rated} rated",
                                    style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (state.nano == NanoAvailability.DOWNLOADABLE) Button(enabled = !state.busy,
                                        onClick = onDownload) { Text("Download model") }
                                    TextButton(enabled = !state.busy, onClick = onCheck) { Text("Check Nano") }
                                }
                            }
                        }
                    }
                    if (!isLoggedIn) item { Text("Sign in to MAL to use your ratings and watch history.") }
                    if (state.messages.isEmpty()) {
                        item { Text("Try a request", style = MaterialTheme.typography.titleMedium) }
                        items(listOf("I loved Made in Abyss. Find something similar.",
                            "Suggest anime based on my highest ratings.", "Find a short series I haven't watched.")) { example ->
                            OutlinedButton(onClick = { draft = example }, modifier = Modifier.fillMaxWidth()) { Text(example) }
                        }
                        item { Text("Anime facts come from MAL. Your requests and selected taste information are processed on this phone. MAL search and sync need internet. Chats stay in memory and clear when this screen is closed; New chat clears them now.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(state.messages) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(20.dp),
                                color = if (message.fromUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.widthIn(max = 560.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (message.fromUser) "You" else "Companion", style = MaterialTheme.typography.labelMedium)
                                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    items(state.choices, key = { "choice-${it.id}" }) { node ->
                        OutlinedButton(enabled = !state.busy, onClick = { onChoose(node) }, modifier = Modifier.fillMaxWidth()) {
                            Text(node.title)
                        }
                    }
                    if (state.matches.isNotEmpty()) item { Text("Your matches", style = MaterialTheme.typography.titleLarge) }
                    items(state.matches, key = { "match-${it.anime.id}" }) { match ->
                        Card(onClick = { navActionManager.toMediaDetails(MediaType.ANIME, match.anime.id) },
                            shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    PlatformImage(model = match.anime.mainPicture?.large, contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(width = 68.dp, height = 98.dp).clip(RoundedCornerShape(12.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(match.anime.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("${match.anime.numEpisodes?.takeIf { it > 0 }?.let { "$it episodes" } ?: "Length unknown"} · ${match.anime.mediaFormat?.name?.lowercase() ?: "Format unknown"}",
                                            style = MaterialTheme.typography.labelMedium)
                                        Text(match.anime.genres.orEmpty().take(4).joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text(match.evidence, style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { navActionManager.toMediaDetails(MediaType.ANIME, match.anime.id) }) { Text("View / add to list") }
                                    TextButton(enabled = canSend, onClick = { onSend("I've already seen ${match.anime.title}.") }) { Text("Already seen") }
                                }
                            }
                        }
                    }
                    if (state.matches.isNotEmpty()) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = canSend, onClick = { onSend("Keep the same preferences, but only series under 24 episodes.") }) { Text("Under 24 episodes") }
                            OutlinedButton(enabled = canSend, onClick = { onSend("Keep the same preferences, but only movies.") }) { Text("Movies") }
                        }
                    }
                }
                if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                if (state.busy) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.status, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancel) { Text("Stop") }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft, onValueChange = { if (it.length <= 600) draft = it },
                        label = { Text("Ask for recommendations") }, enabled = !state.busy,
                        modifier = Modifier.weight(1f), maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }))
                    Button(enabled = canSend && draft.isNotBlank(), onClick = ::send) { Text("Send") }
                }
            }
        }
    }
}
