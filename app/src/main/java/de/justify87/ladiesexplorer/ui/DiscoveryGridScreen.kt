package de.justify87.ladiesexplorer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference

@Composable
internal fun DiscoveryGridScreen(
    discovery: DiscoverUiState,
    search: SearchUiState,
    onLoadMore: (GridPageAction) -> Unit,
    onDecision: (GridLikeAction, Preference) -> Unit,
    onUndo: (GridUndoAction) -> Unit,
    onProfile: (GridLikeAction) -> Unit,
    chrome: @Composable () -> Unit,
    onCancelSearch: () -> Unit,
    modifier: Modifier = Modifier,
    touchGate: ProfileTouchGate? = null,
) {
    val contentInset = shellContentInset()
    val cards = gridCards(discovery, search)
    val active = search.response != null
    val emptyState = discoveryEmptyState(discovery, search)
    val loadingResults = emptyState == DiscoveryEmptyState.Loading
    val enabled = !discovery.busy && !discovery.loading && !search.loading && !search.actionBusy
    val undoAction = discovery.gridUndo?.let { GridUndoAction(it, discovery.actionGeneration) }
    val canUndo = undoAction != null && gridUndoMatches(discovery, search, undoAction)
    val pageAction = if (active) null else discovery.nextCursor?.let { GridPageAction(it, discovery.actionGeneration) }
    val canPage = pageAction != null && gridPageMatches(discovery, search, pageAction)
    val showPage = pageAction != null && (cards.isNotEmpty() || !loadingResults)
    var anchorKey by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorIndex by rememberSaveable { mutableIntStateOf(0) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val inlineChrome = maxHeight < 360.dp || (maxHeight < 460.dp && LocalDensity.current.fontScale > 1.3f)
        val showStatus = hasDiscoverySearchStatus(search)
        val keys = buildList {
            if (inlineChrome) add("discovery-chrome")
            if (inlineChrome && undoAction != null) add("grid-undo")
            if (showStatus) add("search-status")
            if (discovery.mutationError != null) add("mutation-error")
            if (discovery.error != null) add("error")
            if (cards.isEmpty()) add(if (loadingResults) "loading" else "empty")
            cards.forEach { add(gridAnchorKey(it.listingId)) }
            if (showPage) add("more")
        }
        val presentationContext = Triple(search.response?.searchRunId, discovery.globalVisibility, search.activeProjectionPending)
        val listState = remember(presentationContext) {
            val (index, offset) = gridRestorePosition(keys, anchorKey, anchorIndex, anchorOffset)
            LazyGridState(index, offset)
        }
        fun currentAnchor(): Triple<String, Int, Int>? {
            val index = listState.firstVisibleItemIndex
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
            return Triple(gridAnchorKey(item.key), index, listState.firstVisibleItemScrollOffset)
        }
        fun saveAnchor(anchor: Triple<String, Int, Int>) {
            anchorKey = anchor.first
            anchorIndex = anchor.second
            anchorOffset = anchor.third
        }
        LaunchedEffect(listState) {
            snapshotFlow { currentAnchor() }.filterNotNull().collect { saveAnchor(it) }
        }
        LaunchedEffect(listState, pageAction, canPage, discovery.error) {
            if (pageAction != null && canPage && discovery.error == null) {
                snapshotFlow {
                    val layout = listState.layoutInfo
                    nearDiscoveryEnd(layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount)
                }.first { it }
                onLoadMore(pageAction)
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (!inlineChrome) Box(Modifier.fillMaxWidth().padding(horizontal = contentInset)) { chrome() }
            if (!inlineChrome && undoAction != null) GridUndoButton(undoAction, canUndo, onUndo)
            // Context replacement must not retain outgoing images from an invalid membership.
            key(presentationContext) {
                LazyVerticalGrid(
                    columns = profileGridCells(),
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = contentInset, vertical = ShellMetrics.small),
                    horizontalArrangement = Arrangement.spacedBy(ShellMetrics.small),
                    verticalArrangement = Arrangement.spacedBy(ShellMetrics.small),
                ) {
                    if (inlineChrome) item(key = "discovery-chrome", span = { GridItemSpan(maxLineSpan) }) { chrome() }
                    if (inlineChrome && undoAction != null) item(key = "grid-undo", span = { GridItemSpan(maxLineSpan) }) {
                        GridUndoButton(undoAction, canUndo, onUndo)
                    }
                    if (showStatus) {
                        item(key = "search-status", span = { GridItemSpan(maxLineSpan) }) {
                            DiscoverySearchRunStatus(search, onCancelSearch)
                        }
                    }
                    discovery.mutationError?.let { message ->
                        item(key = "mutation-error", span = { GridItemSpan(maxLineSpan) }) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    discovery.error?.let { message ->
                        item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (loadingResults && cards.isEmpty()) {
                        item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (cards.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text(if (emptyState == DiscoveryEmptyState.Unknown) "Lokale Treffer unbekannt." else "Keine Treffer.",
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    items(cards, key = { it.listingId }) { card ->
                        val action = GridLikeAction(card, search.response?.searchRunId, discovery.actionGeneration)
                        ProfileGridCard(
                            name = card.name,
                            age = card.age,
                            thumbnailUrl = card.thumbnailUrl,
                            localPath = card.localPath,
                            preference = card.preference,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(160), placementSpec = spring(stiffness = 500f), fadeOutSpec = tween(140)),
                            decisionOwner = action,
                            touchGate = touchGate,
                            enabled = enabled,
                            onOpen = {
                                currentAnchor()?.let(::saveAnchor)
                                onProfile(action)
                            },
                            onDecision = { target -> onDecision(action, target) },
                        )
                    }
                    if (showPage && pageAction != null) {
                        item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                            if (discovery.loadingMore) {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                }
                            } else {
                                TextButton(onClick = { onLoadMore(pageAction) }, enabled = canPage, modifier = Modifier.fillMaxWidth()) {
                                    Text("Weitere Profile")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun profileGridCells(): GridCells =
    GridCells.Adaptive(if (LocalDensity.current.fontScale >= 1.5f) 240.dp else 148.dp)

@Composable
private fun GridUndoButton(action: GridUndoAction, enabled: Boolean, onUndo: (GridUndoAction) -> Unit) {
    TextButton(onClick = { onUndo(action) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Letzte Entscheidung rückgängig")
    }
}

internal fun gridPreferenceLabel(preference: Preference): String = when (preference) {
    Preference.LIKE -> "Gefällt"
    Preference.NONE -> "Unbewertet"
    Preference.DISLIKE -> "Gefällt nicht"
}

@Composable
internal fun ProfileGridCard(
    name: String,
    age: String?,
    thumbnailUrl: String?,
    localPath: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    place: String? = null,
    preference: Preference? = null,
    decisionOwner: Any? = null,
    onDecision: ((Preference) -> Unit)? = null,
    touchGate: ProfileTouchGate? = null,
) {
    if (preference == null || onDecision == null) {
        GridCardContent(name, age, place, thumbnailUrl, localPath, enabled, onOpen, modifier)
        return
    }
    var drag by remember(decisionOwner) { mutableFloatStateOf(0f) }
    val limit = with(LocalDensity.current) { 16.dp.toPx() }
    val shift by animateFloatAsState((drag * .18f).coerceIn(-limit, limit), label = "grid decision")
    PhotoGestureSurface(owner = decisionOwner, modifier = modifier.fillMaxWidth(),
        galleryEnabled = enabled, onSwipe = onDecision.takeIf { enabled }, onDrag = { drag = it },
        touchGate = touchGate) { hit ->
        val actions = Modifier.semantics {
            contentDescription = name.ifBlank { "Inserat" }
            stateDescription = gridPreferenceLabel(preference)
            if (enabled) customActions = listOf(
                CustomAccessibilityAction("Gefällt") { onDecision(Preference.LIKE); true },
                CustomAccessibilityAction("Gefällt nicht") { onDecision(Preference.DISLIKE); true },
                CustomAccessibilityAction("Bewertung entfernen") { onDecision(Preference.NONE); true },
            )
        }
        GridCardContent(name, age, null, thumbnailUrl, localPath, enabled, onOpen,
            Modifier.then(hit.photo), preference, shift, drag,
            decisionOwner = decisionOwner, interaction = Modifier.then(hit.previous).then(actions))
    }
}

@Composable
private fun GridCardContent(
    name: String, age: String?, place: String?, thumbnailUrl: String?, localPath: String?,
    enabled: Boolean, onOpen: () -> Unit, modifier: Modifier, preference: Preference? = null,
    shift: Float = 0f, drag: Float = 0f,
    decisionOwner: Any? = null, interaction: Modifier = Modifier,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Box(
        modifier.fillMaxWidth().heightIn(min = if (largeText) 220.dp else 0.dp)
            .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (preference == null) Modifier.shellFocus()
                .clickable(enabled = enabled, onClickLabel = "$name öffnen", onClick = onOpen) else Modifier),
    ) {
        Spacer(Modifier.fillMaxWidth().aspectRatio(1.03f))
        ProfileImage(thumbnailUrl, localPath, Modifier.matchParentSize().graphicsLayer { translationX = shift },
            description = "$name – Profilbild")
        if (preference != null) {
            if (preference == Preference.DISLIKE)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .28f)))
            age?.takeIf { it.isNotBlank() }?.let {
                Text(it, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = .78f), MaterialTheme.shapes.small).padding(horizontal = 7.dp, vertical = 3.dp),
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            GridPreferenceIcon(preference, Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (drag != 0f) {
                Text(if (drag > 0f) "Gefällt" else "Gefällt nicht",
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha = .82f), MaterialTheme.shapes.small).padding(6.dp),
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            // Revoke presses with their action snapshot without restarting the image request.
            key(decisionOwner) {
                Box(Modifier.matchParentSize().then(interaction).shellFocus()
                    .clickable(enabled = enabled, onClickLabel = "$name öffnen", onClick = onOpen))
            }
        } else {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Transparent, .44f to Color.Black.copy(alpha = .02f),
                1f to Color.Black.copy(alpha = .82f),
            )))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Black.copy(alpha = .85f))))
                .padding(12.dp)) {
                Text(listOfNotNull(name.ifBlank { "Inserat" }, age?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                place?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun GridPreferenceIcon(preference: Preference, modifier: Modifier) {
    val liked = preference == Preference.LIKE
    val foreground = if (liked) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFFD1D5DB)
    Box(modifier.size(30.dp).background(
        if (liked) MaterialTheme.colorScheme.primaryContainer else Color.Black.copy(alpha = .82f), CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
        if (preference == Preference.DISLIKE) Canvas(Modifier.size(18.dp)) {
            drawLine(Color.White, Offset(size.width * .12f, size.height * .12f),
                Offset(size.width * .88f, size.height * .88f), strokeWidth = 2.dp.toPx())
        }
    }
}
