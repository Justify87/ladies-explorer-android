package de.justify87.ladiesexplorer.ui

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
    onLike: (GridLikeAction) -> Unit,
    onProfile: (GridLikeAction) -> Unit,
    chrome: @Composable () -> Unit,
    onCancelSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cards = gridCards(discovery, search)
    val active = search.response != null
    val enabled = !discovery.busy && !discovery.loading && !search.loading && !search.actionBusy
    val pageAction = if (active) null else discovery.nextCursor?.let { GridPageAction(it, discovery.actionGeneration) }
    val canPage = pageAction != null && gridPageMatches(discovery, search, pageAction)
    var anchorKey by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorIndex by rememberSaveable { mutableIntStateOf(0) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val inlineChrome = maxHeight < 360.dp || (maxHeight < 460.dp && LocalDensity.current.fontScale > 1.3f)
        val showStatus = hasDiscoverySearchStatus(search)
        val keys = buildList {
            if (inlineChrome) add("discovery-chrome")
            if (showStatus) add("search-status")
            if (discovery.mutationError != null) add("mutation-error")
            if (discovery.error != null) add("error")
            if (cards.isEmpty()) add(if (discovery.loading || search.loading) "loading" else "empty")
            cards.forEach { add(gridAnchorKey(it.listingId)) }
            if (pageAction != null) add("more")
        }
        val listState = remember {
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
            if (!inlineChrome) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { chrome() }
            LazyVerticalGrid(
                columns = profileGridCells(),
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (inlineChrome) item(key = "discovery-chrome", span = { GridItemSpan(maxLineSpan) }) { chrome() }
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
                if ((discovery.loading || search.loading) && cards.isEmpty()) {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (cards.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text("Keine Treffer.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                items(cards, key = { it.listingId }) { card ->
                    val action = GridLikeAction(card, search.response?.searchRunId, discovery.actionGeneration)
                    ProfileGridCard(
                        name = card.name,
                        place = card.city,
                        age = card.age,
                        thumbnailUrl = card.thumbnailUrl,
                        localPath = card.localPath,
                        liked = card.preference == Preference.LIKE,
                        enabled = enabled,
                        onOpen = {
                            currentAnchor()?.let(::saveAnchor)
                            onProfile(action)
                        },
                        onLike = { onLike(action) },
                    )
                }
                if (pageAction != null) {
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

@Composable
internal fun profileGridCells(): GridCells =
    GridCells.Adaptive(if (LocalDensity.current.fontScale >= 1.5f) 240.dp else 148.dp)

@Composable
internal fun ProfileGridCard(
    name: String,
    age: String?,
    thumbnailUrl: String?,
    localPath: String?,
    liked: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onLike: (() -> Unit)?,
    modifier: Modifier = Modifier,
    place: String? = null,
) {
    val shape = MaterialTheme.shapes.large
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Box(
        modifier.fillMaxWidth().heightIn(min = if (largeText) 220.dp else 0.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .shellFocus().clickable(enabled = enabled, onClickLabel = "$name öffnen", onClick = onOpen),
    ) {
        Spacer(Modifier.fillMaxWidth().aspectRatio(1.03f))
        ProfileImage(thumbnailUrl, localPath, Modifier.matchParentSize(), description = "$name – Profilbild")
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .44f to Color.Black.copy(alpha = .02f),
            1f to Color.Black.copy(alpha = .82f),
        )))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .padding(top = if (onLike != null) 64.dp else 0.dp)
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
        if (onLike != null) {
            IconButton(
                onClick = onLike,
                enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp).shellFocus()
                    .background(if (liked) MaterialTheme.colorScheme.primaryContainer
                    else Color.White.copy(alpha = .94f), CircleShape),
            ) {
                Icon(
                    if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (liked) "Like für $name entfernen" else "$name gefällt mir",
                    tint = if (liked) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF6B7280),
                )
            }
        }
    }
}
