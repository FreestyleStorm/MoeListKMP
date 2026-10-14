package com.axiel7.moelist.screens.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import org.jetbrains.compose.resources.DrawableResource
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
internal fun ProfileViewContent(
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
    val initialLoading = uiState.isLoading && uiState.user == null
    val available = stats != null
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
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f

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
            Modifier.padding(padding).fillMaxSize().profileAurora(dark)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(shape = RoundedCornerShape(28.dp), color = ProfileNight,
                    contentColor = Color.White, border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 228.dp)) {
                        ProfileArtwork(UiRes.drawable.profile_night_lake, ProfileNight,
                            Modifier.matchParentSize(), imageAlpha = 1f)
                        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            PressSurface(
                                enabled = !uiState.profilePictureUrl.isNullOrBlank(),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(88.dp),
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
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.profilePlaceholder(initialLoading))
                                uiState.user?.location?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                                ) { Text(stringResource(UiRes.string.view_profile_mal), color = ProfileMint) }
                            }
                        }
                    }
                }

                PressSurface(enabled = available, color = ProfileNight,
                    contentColor = Color.White,
                    border = BorderStroke(1.dp, ProfileMint.copy(alpha = .18f)),
                    onClick = { details = StatDetails(watchTimeTitle, days, watchTimeDescription) }) {
                    Box {
                        ProfileArtwork(UiRes.drawable.profile_night_lake, ProfileNight,
                            Modifier.matchParentSize(), imageAlpha = .85f)
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(painterResource(UiRes.drawable.ic_round_access_time_24),
                                    contentDescription = null, tint = ProfileMint, modifier = Modifier.size(20.dp))
                                Text(watchTimeTitle, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(days, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
                                color = ProfileMint,
                                modifier = Modifier.profilePlaceholder(initialLoading))
                            Text(stringResource(UiRes.string.profile_days_watched), style = MaterialTheme.typography.bodyLarge)
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LocalContentColor.current.copy(alpha = .15f))
                            TextButton(enabled = available,
                                onClick = { details = StatDetails(episodesTitle, episodes, episodesDescription) },
                                contentPadding = PaddingValues(0.dp)) {
                                Text("$episodes ${stringResource(UiRes.string.episodes)}", color = ProfileMint)
                            }
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
                            Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(categoryAccent(status)))
                        }
                    }
                }
                categories.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (status, count) ->
                            CategoryCard(status, count, initialLoading, available,
                                Modifier.weight(1f), onClick = { navActionManager.toAnimeCategory(status) })
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileMetric(meanTitle, mean, available, initialLoading, Modifier.weight(1f)) {
                        details = StatDetails(meanTitle, mean, meanDescription)
                    }
                    ProfileMetric(repeatTitle, rewatched, available, initialLoading, Modifier.weight(1f)) {
                        details = StatDetails(repeatTitle, rewatched, repeatDescription)
                    }
                }
            }
        }
    }
}

// Keep the glow fixed behind the scrolling cards. Brushes are cached until the size or theme changes.
private fun Modifier.profileAurora(dark: Boolean): Modifier = drawWithCache {
    val base = if (dark) Color(0xFF0B1221) else Color(0xFFF4F7FC)
    val teal = if (dark) Color(0xFF219C91).copy(alpha = .42f) else Color(0xFF9DE6D9).copy(alpha = .55f)
    val violet = if (dark) Color(0xFF8053CF).copy(alpha = .38f) else Color(0xFFCAB7EE).copy(alpha = .48f)
    val tealGlow = Brush.radialGradient(
        colors = listOf(teal, teal.copy(alpha = 0f)),
        center = Offset(size.width * -.1f, size.height * .32f),
        radius = size.maxDimension * .8f,
    )
    val violetGlow = Brush.radialGradient(
        colors = listOf(violet, violet.copy(alpha = 0f)),
        center = Offset(size.width * 1.05f, size.height * .88f),
        radius = size.maxDimension * .75f,
    )
    onDrawBehind {
        drawRect(base)
        drawRect(tealGlow)
        drawRect(violetGlow)
    }
}

@Composable
private fun CategoryCard(status: ListStatus, count: Int?, loading: Boolean, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val accent = categoryAccent(status)
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val base = lerp(MaterialTheme.colorScheme.surfaceContainer, accent, if (dark) .09f else .06f)
    val art = when (status) {
        ListStatus.COMPLETED, ListStatus.PLAN_TO_WATCH -> UiRes.drawable.profile_night_lake
        ListStatus.WATCHING -> UiRes.drawable.profile_evening_city
        else -> null
    }
    PressSurface(onClick, enabled, base, MaterialTheme.colorScheme.onSurface, modifier,
        border = BorderStroke(1.dp, accent.copy(alpha = if (dark) .2f else .16f))) {
        Box {
            if (art != null) ProfileArtwork(art, base, Modifier.matchParentSize(),
                imageAlpha = if (dark) .7f else .3f, wash = if (dark) .25f else .65f)
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(status.icon), contentDescription = null, tint = accent,
                    modifier = Modifier.size(24.dp))
                Text(count?.format() ?: "—", style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, color = if (dark) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.profilePlaceholder(loading))
                Text(status.localized(), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun ProfileMetric(title: String, value: String, enabled: Boolean, loading: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    PressSurface(onClick, enabled, MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.profilePlaceholder(loading))
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
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, tween(180), label = "profile press")
    Surface(onClick = onClick, enabled = enabled, interactionSource = interaction,
        shape = shape, color = color, contentColor = contentColor, border = border,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }, content = content)
}

// Loaded text has no placeholder drawing layer, including while MAL refreshes.
private fun Modifier.profilePlaceholder(initialLoading: Boolean): Modifier =
    if (initialLoading) defaultPlaceholder(true) else this

private val ProfileNight = Color(0xFF101C23)
private val ProfileMint = Color(0xFF9BE8C5)

@Composable
private fun categoryAccent(status: ListStatus): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (status) {
        ListStatus.COMPLETED -> if (dark) Color(0xFF75DBAC) else Color(0xFF246849)
        ListStatus.WATCHING, ListStatus.READING -> if (dark) Color(0xFF8FB7FF) else Color(0xFF24497E)
        ListStatus.PLAN_TO_WATCH, ListStatus.PLAN_TO_READ -> if (dark) Color(0xFFC8B0FF) else Color(0xFF604484)
        ListStatus.ON_HOLD -> if (dark) Color(0xFFF5D077) else Color(0xFF705A26)
        ListStatus.DROPPED -> if (dark) Color(0xFFFF9AA6) else Color(0xFF943948)
    }
}

// Decorative offline artwork; the entire card stays a single animated tap target.
@Composable
private fun ProfileArtwork(art: DrawableResource, base: Color, modifier: Modifier,
    imageAlpha: Float, wash: Float = .15f) {
    Box(modifier) {
        Image(painterResource(art), contentDescription = null,
            contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
            alpha = imageAlpha, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            listOf(base.copy(alpha = .78f), base.copy(alpha = wash)))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.Transparent, base.copy(alpha = .42f)))))
    }
}

@Preview
@Composable
fun ProfilePreview() {
    MoeListTheme {
        ProfileViewContent(ProfileUiState(), null, true, NavActionManager.rememberNavActionManager())
    }
}
