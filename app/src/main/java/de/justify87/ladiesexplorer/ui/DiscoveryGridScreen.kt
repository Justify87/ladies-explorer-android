package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference

@Composable
internal fun DiscoveryGridScreen(
    discovery: DiscoverUiState,
    search: SearchUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLike: (GridLikeAction) -> Unit,
    onProfile: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cards = gridCards(discovery, search)
    val active = search.response != null
    val enabled = !discovery.busy && !discovery.loading && !search.loading && !search.actionBusy
    val listState = rememberLazyGridState()
    val nextCursor = if (active) null else discovery.nextCursor
    val canPage = nextCursor != null && enabled && !discovery.loadingMore
    LaunchedEffect(listState, nextCursor, canPage, discovery.error) {
        if (canPage && discovery.error == null) {
            snapshotFlow {
                val layout = listState.layoutInfo
                nearDiscoveryEnd(layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount)
            }.first { it }
            onLoadMore()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (active) "Aktive Suche · ${cards.size} sichtbar" else "Lokaler Bestand · neutrale Deckprofile",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh, enabled = enabled) { Text("Aktualisieren") }
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
            ProfileGridCard(
                name = card.name,
                age = card.age,
                thumbnailUrl = card.thumbnailUrl,
                localPath = card.localPath,
                liked = card.preference == Preference.LIKE,
                enabled = enabled,
                onOpen = { onProfile(card.listingId, card.personId) },
                onLike = { onLike(GridLikeAction(card, search.response?.searchRunId, discovery.actionGeneration)) },
            )
        }
        if (nextCursor != null) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                if (discovery.loadingMore) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else {
                    TextButton(onClick = onLoadMore, enabled = canPage, modifier = Modifier.fillMaxWidth()) {
                        Text("Weitere Profile")
                    }
                }
            }
        }
    }
}

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
) {
    val shape = RoundedCornerShape(23.dp)
    Box(
        modifier.fillMaxWidth().aspectRatio(.77f).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClickLabel = "$name öffnen", onClick = onOpen),
    ) {
        ProfileImage(thumbnailUrl, localPath, Modifier.matchParentSize(), description = "$name – Profilbild")
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .44f to Color.Black.copy(alpha = .02f),
            1f to Color.Black.copy(alpha = .82f),
        )))
        Text(
            listOfNotNull(name.ifBlank { "Inserat" }, age).joinToString(" · "),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (onLike != null) {
            IconButton(
                onClick = onLike,
                enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp)
                    .background(if (liked) MaterialTheme.colorScheme.primaryContainer
                    else Color.White.copy(alpha = .94f), CircleShape),
            ) {
                Icon(
                    if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (liked) "Like für $name entfernen" else "$name gefällt mir",
                    tint = if (liked) MaterialTheme.colorScheme.primary else Color(0xFF6B7280),
                )
            }
        }
    }
}
