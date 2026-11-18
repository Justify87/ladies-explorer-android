package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.Preference

@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    searchLoading: Boolean = false,
    onRefresh: () -> Unit,
    onSkip: (Long) -> Unit,
    onChoose: (Long, Preference) -> Unit,
    onFavorite: (Long) -> Unit,
    onProfile: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.cards.firstOrNull()
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.activeRunId == null) "Lokaler Bestand · ${state.cards.size} unbewertete Profile"
                    else "Aktive Suche · ${state.activeVisibleCount} sichtbar · ${state.cards.size} unbewertet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh, enabled = !state.busy && !searchLoading) { Text("Aktualisieren") }
        }
        if (state.loading) {
            CircularProgressIndicator()
        } else if (card == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Keine neuen Profile", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.activeRunId == null) "Starte eine Suche oder aktualisiere den lokalen Bestand."
                        else "Keine unbewerteten Treffer in diesem Suchlauf. Ändere die Filter oder starte eine neue Suche.")
                    OutlinedButton(onClick = onRefresh) { Text("Erneut laden") }
                }
            }
        } else {
            var drag by remember(card.personId) { mutableFloatStateOf(0f) }
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer { translationX = drag; rotationZ = drag / 45f }
                    .pointerInput(card.personId, state.busy, searchLoading) {
                        if (!state.busy && !searchLoading) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, delta ->
                                    change.consume()
                                    drag = (drag + delta).coerceIn(-300f, 300f)
                                },
                                onDragEnd = {
                                    val distance = drag
                                    drag = 0f
                                    if (distance > 110f) onChoose(card.personId, Preference.LIKE)
                                    if (distance < -110f) onChoose(card.personId, Preference.DISLIKE)
                                },
                                onDragCancel = { drag = 0f },
                            )
                        }
                    },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileThumbnail(
                        url = card.thumbnailUrl,
                        localPath = card.localPath,
                        modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp),
                    )
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(card.name, style = MaterialTheme.typography.headlineSmall)
                        Text(card.city, style = MaterialTheme.typography.bodyLarge)
                        card.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it.take(190), maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                modifier = Modifier.weight(1f),
                                onClick = { onProfile(card.listingId, card.personId) },
                            ) { Text("Profil ansehen") }
                            IconButton(onClick = { onFavorite(card.personId) }, enabled = !state.busy && !searchLoading) {
                                Icon(
                                    if (card.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (card.favorite) "Favorit entfernen" else "Zu Favoriten",
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy && !searchLoading,
                    onClick = { onChoose(card.personId, Preference.DISLIKE) },
                ) { Text("Nein") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy && !searchLoading,
                    onClick = { onSkip(card.personId) },
                ) { Text("Später") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy && !searchLoading,
                    onClick = { onChoose(card.personId, Preference.LIKE) },
                ) { Text("Gefällt") }
            }
            Text("Wischen: links Nein, rechts Gefällt", style = MaterialTheme.typography.labelSmall)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun FavoritesScreen(
    state: DiscoverUiState,
    onRefresh: () -> Unit,
    onProfile: (Long, Long) -> Unit,
    onFavorite: (Long) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.favorites.size, state.favoritesNextCursor) {
        if (state.favoritesNextCursor != null) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (last >= state.favorites.size - 3) onLoadMore()
                }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favoriten", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Aktualisieren") }
            }
        }
        if (state.loading) item { CircularProgressIndicator() }
        if (!state.loading && state.favorites.isEmpty()) item {
            Text("Noch keine Favoriten gespeichert. Markiere ein Profil im Entdecken-Deck oder in der Detailansicht.")
        }
        items(state.favorites, key = { it.personId }) { card ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileThumbnail(
                        url = card.thumbnailUrl,
                        localPath = card.localPath,
                        modifier = Modifier.size(96.dp).clip(MaterialTheme.shapes.small),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(card.name, style = MaterialTheme.typography.titleMedium)
                        Text(card.city, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onProfile(card.listingId,card.personId) }) {
                            Text("Profil ansehen")
                        }
                    }
                    IconButton(onClick = { onFavorite(card.personId) },enabled = !state.busy) {
                        Icon(Icons.Default.Favorite, contentDescription = "Favorit entfernen")
                    }
                }
            }
        }
        if (state.favoritesLoadingMore) item { CircularProgressIndicator() }
        state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
    }
}

@Composable
fun ProfileThumbnail(url: String?, modifier: Modifier = Modifier, localPath: String? = null) {
    if (url.isNullOrBlank() && localPath == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("Kein Bild",style = MaterialTheme.typography.bodySmall) }
    } else {
        AsyncImage(
            model = localPath?.let(::File) ?: url,
            contentDescription = "Profilbild",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}
