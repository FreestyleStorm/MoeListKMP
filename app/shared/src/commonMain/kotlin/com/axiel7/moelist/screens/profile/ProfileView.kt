package com.axiel7.moelist.screens.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.axiel7.moelist.data.utils.DateUtils.parseDateAndLocalize
import com.axiel7.moelist.data.utils.MAL_PROFILE_URL
import com.axiel7.moelist.data.utils.NumExtensions.format
import com.axiel7.moelist.ui.base.model.ListStatus
import com.axiel7.moelist.ui.base.navigation.NavActionManager
import com.axiel7.moelist.ui.composables.DefaultScaffoldWithTopAppBar
import com.axiel7.moelist.ui.composables.PlatformImage
import com.axiel7.moelist.ui.composables.defaultPlaceholder
import com.axiel7.moelist.ui.generated.resources.*
import com.axiel7.moelist.ui.theme.MoeListTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileView(isCompactScreen: Boolean, navActionManager: NavActionManager) {
    val viewModel: ProfileViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshProfile()
        onPauseOrDispose { }
    }
    ProfileViewContent(uiState, viewModel, isCompactScreen, navActionManager)
}

private data class StatDetails(val title: String, val value: String, val description: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileViewContent(
    uiState: ProfileUiState,
    event: ProfileEvent?,
    isCompactScreen: Boolean,
    navActionManager: NavActionManager,
) {
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var details by remember { mutableStateOf<StatDetails?>(null) }
    val stats = uiState.user?.animeStatistics
    val available = !uiState.isLoading && stats != null
    val days = stats?.numDaysWatched?.format() ?: "—"
    val episodes = stats?.numEpisodes?.format() ?: "—"
    val mean = stats?.meanScore?.format() ?: "—"
    val rewatched = stats?.numTimesRewatched?.format() ?: "—"
    val watchTimeTitle = stringResource(UiRes.string.profile_watch_time)
    val watchTimeDescription = stringResource(UiRes.string.profile_watch_time_info)
    val episodesTitle = stringResource(UiRes.string.episodes)
    val episodesDescription = stringResource(UiRes.string.profile_episodes_info)
    val meanTitle = stringResource(UiRes.string.mean_score)
    val meanDescription = stringResource(UiRes.string.profile_mean_info)
    val repeatTitle = stringResource(UiRes.string.rewatched)
    val repeatDescription = stringResource(UiRes.string.profile_rewatch_info)

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbar.showSnackbar(it)
            event?.onMessageDisplayed()
        }
    }

    details?.let { selected ->
        ModalBottomSheet(onDismissRequest = { details = null }, sheetState = sheetState) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(selected.title, style = MaterialTheme.typography.titleLarge)
                Text(selected.value, style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(selected.description, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { details = null }
                }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(UiRes.string.ok))
                }
            }
        }
    }

    DefaultScaffoldWithTopAppBar(
        title = stringResource(UiRes.string.title_profile),
        navigateBack = navActionManager::goBack,
        snackbarHostState = snackbar,
        isTopBarVisible = isCompactScreen,
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Box {
                        ProfileLandscape(Modifier.matchParentSize())
                        Row(Modifier.fillMaxWidth().padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            PressSurface(
                                enabled = !uiState.profilePictureUrl.isNullOrBlank(),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(80.dp),
                                shape = CircleShape,
                                onClick = {
                                    uiState.profilePictureUrl?.let {
                                        navActionManager.toFullPoster(persistentListOf(it))
                                    }
                                },
                            ) {
                                PlatformImage(model = uiState.profilePictureUrl,
                                    contentDescription = stringResource(UiRes.string.title_profile),
                                    placeholder = painterResource(UiRes.drawable.ic_round_account_circle_24),
                                    error = painterResource(UiRes.drawable.ic_round_account_circle_24),
                                    fallback = painterResource(UiRes.drawable.ic_round_account_circle_24),
                                    modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(uiState.user?.name ?: if (uiState.isLoading) "…" else "—",
                                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.defaultPlaceholder(uiState.isLoading))
                                uiState.user?.location?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                }
                                uiState.user?.birthday?.parseDateAndLocalize()?.let {
                                    Text(it, style = MaterialTheme.typography.labelMedium)
                                }
                                uiState.user?.joinedAt?.parseDateAndLocalize()?.let {
                                    Text(stringResource(UiRes.string.profile_joined, it),
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(
                                    enabled = !uiState.user?.name.isNullOrBlank(),
                                    onClick = { uriHandler.openUri(MAL_PROFILE_URL + uiState.user?.name) },
                                    contentPadding = PaddingValues(0.dp),
                                ) { Text(stringResource(UiRes.string.view_profile_mal)) }
                            }
                        }
                    }
                }

                PressSurface(enabled = available, color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = { details = StatDetails(watchTimeTitle, days, watchTimeDescription) }) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(watchTimeTitle, style = MaterialTheme.typography.titleMedium)
                        Text(days, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.defaultPlaceholder(uiState.isLoading))
                        Text(stringResource(UiRes.string.profile_days_watched), style = MaterialTheme.typography.bodyLarge)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LocalContentColor.current.copy(alpha = .15f))
                        TextButton(enabled = available,
                            onClick = { details = StatDetails(episodesTitle, episodes, episodesDescription) },
                            contentPadding = PaddingValues(0.dp)) {
                            Text("$episodes ${stringResource(UiRes.string.episodes)}")
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(UiRes.string.profile_collection), style = MaterialTheme.typography.titleLarge)
                    Text(stats?.numItems?.let { stringResource(UiRes.string.total_entries, it) } ?: "—",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!uiState.isLoading && stats == null) {
                    Text(stringResource(UiRes.string.profile_stats_unavailable), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { event?.refreshProfile() }) {
                        Text(stringResource(UiRes.string.profile_retry))
                    }
                }
                val categories = listOf(
                    ListStatus.COMPLETED to stats?.numItemsCompleted,
                    ListStatus.WATCHING to stats?.numItemsWatching,
                    ListStatus.PLAN_TO_WATCH to stats?.numItemsPlanToWatch,
                    ListStatus.ON_HOLD to stats?.numItemsOnHold,
                    ListStatus.DROPPED to stats?.numItemsDropped,
                )
                val total = categories.sumOf { (it.second ?: 0).coerceAtLeast(0) }
                Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    categories.forEach { (status, count) ->
                        if (total > 0 && count != null && count > 0) {
                            Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(status.primaryColor()))
                        }
                    }
                }
                categories.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (status, count) ->
                            CategoryCard(status, count, uiState.isLoading, available,
                                Modifier.weight(1f), onClick = { navActionManager.toAnimeCategory(status) })
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileMetric(meanTitle, mean, available, uiState.isLoading, Modifier.weight(1f)) {
                        details = StatDetails(meanTitle, mean, meanDescription)
                    }
                    ProfileMetric(repeatTitle, rewatched, available, uiState.isLoading, Modifier.weight(1f)) {
                        details = StatDetails(repeatTitle, rewatched, repeatDescription)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(status: ListStatus, count: Int?, loading: Boolean, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    PressSurface(onClick, enabled, status.primaryColor(), status.onPrimaryColor(), modifier) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(status.icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(count?.format() ?: "—", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.defaultPlaceholder(loading))
            Text(status.localized(), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ProfileMetric(title: String, value: String, enabled: Boolean, loading: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    PressSurface(onClick, enabled, MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.defaultPlaceholder(loading))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PressSurface(
    onClick: () -> Unit,
    enabled: Boolean,
    color: Color,
    contentColor: Color = contentColorFor(color),
    modifier: Modifier = Modifier.fillMaxWidth(),
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, tween(180), label = "profile press")
    Surface(onClick = onClick, enabled = enabled, interactionSource = interaction,
        shape = shape, color = color, contentColor = contentColor,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }, content = content)
}

@Composable
private fun ProfileLandscape(modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = .12f), Color.Transparent)))
        val mountains = Path().apply {
            moveTo(0f, size.height * .8f)
            lineTo(size.width * .25f, size.height * .3f)
            lineTo(size.width * .48f, size.height * .85f)
            lineTo(size.width * .78f, size.height * .25f)
            lineTo(size.width, size.height * .7f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(mountains, tint.copy(alpha = .07f))
    }
}

@Preview
@Composable
fun ProfilePreview() {
    MoeListTheme {
        ProfileViewContent(ProfileUiState(), null, true, NavActionManager.rememberNavActionManager())
    }
}
