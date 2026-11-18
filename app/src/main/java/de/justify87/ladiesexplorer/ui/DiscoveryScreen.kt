package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException

@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    searchLoading: Boolean = false,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onReplayLikes: (Boolean) -> Unit,
    onSkip: (Long) -> Unit,
    onChoose: (Long, Preference) -> Unit,
    onFavorite: (Long) -> Unit,
    onProfile: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.cards.firstOrNull()
    val context = LocalContext.current
    val core = (context.applicationContext as LadiesApplication).coreStore
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED) && !searchLoading && !state.loading && !state.busy
    val enabled = !state.busy && !state.loading && !searchLoading
    val decks = remember(state.activeRunId) { mutableStateMapOf<Long, SwipeProfile>() }
    var retry by remember(card?.listingId) { mutableIntStateOf(0) }
    var readError by remember(card?.listingId) { mutableStateOf<String?>(null) }
    val current = card?.let { decks[it.listingId] }
    val upcoming = prefetchCards(state.cards)
    val window = listOfNotNull(card) + upcoming
    val control = remember(state.activeRunId, card?.listingId, retry, visible) { RequestControl() }
    DisposableEffect(control) { onDispose { control.cancel() } }
    LaunchedEffect(control) {
        if (!visible || card == null || current != null && retry == 0) return@LaunchedEffect
        readError = null
        try {
            decks[card.listingId] = core.readSwipeProfile(card.listingId, control, refresh = true)
            retry = 0
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            readError = failure.message ?: "Profil konnte nicht geladen werden"
        }
    }
    val prefetchControl = remember(state.activeRunId, window.map { it.listingId }, visible, current != null, state.busy) {
        RequestControl()
    }
    DisposableEffect(prefetchControl) { onDispose { prefetchControl.cancel() } }
    LaunchedEffect(prefetchControl) {
        decks.keys.toList().filterNot { id -> window.any { it.listingId == id } }.forEach(decks::remove)
        if (!visible || state.busy || current == null) return@LaunchedEffect
        for (next in upcoming) {
            try {
                val profile = decks[next.listingId] ?: core.readSwipeProfile(next.listingId, prefetchControl,
                    refresh = false, allowNetwork = state.activeRunId != null)
                decks[next.listingId] = profile
                warmSwipeImages(context, next, profile)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                try { warmSwipeImages(context, next, null) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* optional cache warming must not fail the deck */ }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.activeRunId == null) "Lokaler Bestand · neutrale Profile"
                else "Aktive Suche · ${state.activeVisibleCount} sichtbar · ${state.cards.size} im Deck",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { decks.clear(); onRefresh() }, enabled = enabled) { Text("Aktualisieren") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.activeRunId != null) FilterChip(
                selected = state.replayLikes, enabled = enabled, onClick = { onReplayLikes(!state.replayLikes) },
                label = { Text("Likes erneut zeigen") },
            )
            TextButton(onClick = onUndo, enabled = enabled && state.canUndoSwipe) { Text("Undo") }
            TextButton(onClick = { decks.clear(); onReset() }, enabled = enabled) { Text("Reset") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading && card == null) {
            CircularProgressIndicator()
        } else if (card == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Deck beendet", style = MaterialTheme.typography.titleLarge)
                    Text("Keine weiteren passenden Profile. Reset beginnt die Warteschlange erneut, ohne Entscheidungen zu löschen.")
                    OutlinedButton(onClick = onReset, enabled = enabled) { Text("Deck neu beginnen") }
                }
            }
        } else key(card.listingId) {
            val profile = current
            val matchesOwner = profile == null || profile.identity.personId == card.personId
            val actions = enabled && matchesOwner
            val density = LocalDensity.current.density
            var drag by remember { mutableFloatStateOf(0f) }
            val listState = rememberLazyListState()
            val person = profile?.person?.takeIf { matchesOwner }?.copy(
                preference = card.preference, favorite = card.favorite,
            )
            val fallback = listOfNotNull(card.thumbnailUrl?.let {
                ProfileMediaItem("IMAGE", it, card.localPath, 0u)
            } ?: card.localPath?.let { ProfileMediaItem("IMAGE", "", it, 0u) })
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val heroHeight = (maxHeight * .88f).coerceIn(200.dp, 720.dp)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "hero") {
                        // Only the image surface owns horizontal decisions. Vertical drags
                        // remain with LazyColumn; tap zones keep their click semantics.
                        ProfileMediaHero(
                            media = profile?.media?.ifEmpty { fallback } ?: fallback,
                            modifier = Modifier.fillMaxWidth().height(heroHeight)
                                .graphicsLayer { translationX = drag; rotationZ = drag / (45 * density) }
                                .pointerInput(card.listingId, actions, density) {
                                    if (actions) detectHorizontalDragGestures(
                                        onHorizontalDrag = { change, delta ->
                                            change.consume()
                                            drag = (drag + delta).coerceIn(-300 * density, 300 * density)
                                        },
                                        onDragEnd = {
                                            val decision = swipePreference(drag / density)
                                            drag = 0f
                                            decision?.let { onChoose(card.listingId, it) }
                                        },
                                        onDragCancel = { drag = 0f },
                                    )
                                },
                            originalUrl = profile?.identity?.profileUrl,
                        )
                    }
                    item(key = "header") {
                        if (profile == null) ProfileIdentityHeader(card.name, card.city, hasVideo = card.hasVideo)
                        else ProfileHeader(profile.profile, profile.identity, person, profile.media, profile.fromArchive)
                        if (!matchesOwner) Text("Personenzuordnung geändert. Bitte aktualisieren.")
                        profile?.warning?.let { Text(it) }
                        readError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { decks.remove(card.listingId); retry++ }, enabled = enabled) { Text("Profil erneut laden") }
                        }
                        if (profile == null && readError == null && visible) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    item(key = "actions") {
                        ProfileActionRail(person, profile?.profile?.phoneDisplay, !actions,
                            onFavorite = onFavorite, onPreference = { _, preference -> onChoose(card.listingId, preference) })
                        if (person == null) TextButton(onClick = { onFavorite(card.personId) }, enabled = actions) {
                            Text(if (card.favorite) "Favorit entfernen" else "Favorit")
                        }
                        TextButton(onClick = { onProfile(card.listingId, card.personId) }, enabled = enabled) { Text("Profil öffnen") }
                    }
                    if (profile != null) profileContent(profile.profile, profile.candidates, profile.history, !actions)
                    else card.description?.let { item { Text(it) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onChoose(card.listingId, Preference.DISLIKE) },
                    enabled = actions, modifier = Modifier.weight(1f)) { Text("Nein") }
                OutlinedButton(onClick = { onSkip(card.listingId) },
                    enabled = enabled, modifier = Modifier.weight(1f)) { Text("Später") }
                Button(onClick = { onChoose(card.listingId, Preference.LIKE) },
                    enabled = actions, modifier = Modifier.weight(1f)) { Text("Gefällt") }
            }
        }
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
    ProfileImage(url = url, localPath = localPath, modifier = modifier)
}
