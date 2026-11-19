package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
internal fun DiscoverScreen(
    state: DiscoverUiState,
    tabs: ProfileTabMemory,
    notes: Map<Long, NoteEditorState>,
    noteActions: NoteEditorActions,
    searchLoading: Boolean = false,
    executedFiltersJson: String = "{}",
    onRefresh: (SwipeDeckToken) -> Unit,
    onReset: (SwipeDeckToken) -> Unit,
    onUndo: (SwipeDeckToken) -> Unit,
    onReplayLikes: (SwipeDeckToken, Boolean) -> Unit,
    onSkip: (SwipeCardToken) -> Unit,
    onChoose: (SwipeActionToken, Preference) -> Unit,
    onSwipeVisibility: (Boolean) -> Unit,
    onRenderedSwipe: (SwipeActionToken?) -> Unit,
    onConfirmIdentity: (ProfileIdentityToken, Long) -> Unit,
    onSnapshotChanged: (Boolean) -> Unit,
    onFavorite: (ProfileIdentityToken) -> Unit,
    isProfileCurrent: (ProfileIdentityToken) -> Boolean,
    onProfile: (SwipeCardToken) -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.cards.firstOrNull()
    val deckToken = state.deckToken()
    val cardToken = state.cardToken()
    val context = LocalContext.current
    val core = (context.applicationContext as LadiesApplication).coreStore
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(started) {
        onSwipeVisibility(started)
        onDispose { onSwipeVisibility(false) }
    }
    val visible = started && !searchLoading && !state.loading && !state.busy
    val enabled = visible
    val decks = remember(state.activeRunId, state.swipeProfileRevision, started) { SwipeProfileWindow(mutableStateMapOf()) }
    DisposableEffect(decks) { onDispose { decks.close() } }
    var retry by remember(card?.listingId, card?.personId, state.swipeProfileRevision) { mutableIntStateOf(0) }
    var readError by remember(card?.listingId, card?.personId, state.swipeProfileRevision) { mutableStateOf<String?>(null) }
    var retryLocally by remember(card?.listingId, card?.personId, state.swipeProfileRevision) { mutableStateOf(false) }
    val current = card?.let { decks[it] }
    val token = state.swipeToken(current).takeIf { visible }
    SideEffect { onRenderedSwipe(token) }
    var confirmation by remember(token) { mutableStateOf<Long?>(null) }
    confirmation?.let { target ->
        IdentityConfirmationDialog(target, enabled = token != null && visible,
            onConfirm = { confirmation = null; token?.let { onConfirmIdentity(it, target) } },
            onDismiss = { confirmation = null })
    }
    val owner = current?.identity?.personId ?: card?.personId
    val tab = tabs.selected(owner)
    val boundNotes = noteActions.boundTo(token, isProfileCurrent)
    val panels = rememberProfilePanels(current, executedFiltersJson, tab)
    rememberProfileNote(token, boundNotes, state.busy || token == null, tab == ProfileTab.Notes)
    val selectTab: (ProfileTab) -> Unit = { tabs.select(owner, it) }
    ComparisonDialog(panels)
    val upcoming = prefetchCards(state.cards)
    val window = listOfNotNull(card) + upcoming
    SideEffect { decks.retain(window) }
    val control = remember(decks, card?.listingId, card?.personId, decks.invalidation(card), retry, visible) { RequestControl() }
    DisposableEffect(control) { onDispose { control.cancel() } }
    LaunchedEffect(control) {
        if (!visible || card == null || decks[card] != null && retry == 0) return@LaunchedEffect
        readError = null
        try {
            decks.retain(window)
            decks.readCurrent(card) { invalidateOtherProfiles ->
                val localOnly = retryLocally || decks.requiresLocal(card) || card.listingId in state.localProfileStrictIds
                val preferLocal = localOnly || decks.invalidation(card) != 0L || card.listingId in state.localProfileReloadIds
                core.readSwipeProfile(card.listingId, control, refresh = !preferLocal,
                    allowNetwork = !localOnly, onSourceRead = {
                        invalidateOtherProfiles()
                        onSnapshotChanged(decks.closed)
                    })
            }
            retry = 0
            retryLocally = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            retryLocally = retryLocally || failure is LocalProfileReadFailure
            readError = failure.message ?: "Profil konnte nicht geladen werden"
        }
    }
    val prefetchControl = remember(decks, window.map { it.listingId to it.personId }, visible, state.busy) {
        RequestControl()
    }
    DisposableEffect(prefetchControl) { onDispose { prefetchControl.cancel() } }
    LaunchedEffect(prefetchControl) {
        decks.retain(window)
        if (!visible || state.busy || card == null) return@LaunchedEffect
        // Wait for the first current projection, not for every subsequent refresh.
        // Prefetch may itself invalidate the current entry; do not cancel its own control.
        snapshotFlow { decks[card] }.filterNotNull().first()
        for (next in upcoming) {
            try {
                val profile = decks.prefetch(next) { invalidateOtherProfiles ->
                    core.readSwipeProfile(next.listingId, prefetchControl,
                        refresh = false, allowNetwork = state.activeRunId != null && !decks.requiresLocal(next) &&
                            next.listingId !in state.localProfileStrictIds,
                        onSourceRead = {
                            invalidateOtherProfiles()
                            onSnapshotChanged(decks.closed)
                        })
                }
                if (profile != null) warmSwipeImages(context, next, profile)
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
            TextButton(onClick = { onRefresh(deckToken) }, enabled = enabled) { Text("Aktualisieren") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.activeRunId != null) FilterChip(
                selected = state.replayLikes, enabled = enabled, onClick = { onReplayLikes(deckToken, !state.replayLikes) },
                label = { Text("Likes erneut zeigen") },
            )
            TextButton(onClick = { onUndo(deckToken) }, enabled = enabled && state.canUndoSwipe) { Text("Undo") }
            TextButton(onClick = { onReset(deckToken) }, enabled = enabled) { Text("Reset") }
        }
        state.mergeNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.mutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading && card == null) {
            CircularProgressIndicator()
        } else if (card == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Deck beendet", style = MaterialTheme.typography.titleLarge)
                    Text("Keine weiteren passenden Profile. Reset beginnt die Warteschlange erneut, ohne Entscheidungen zu löschen.")
                    OutlinedButton(onClick = { onReset(deckToken) }, enabled = enabled) { Text("Deck neu beginnen") }
                }
            }
        } else key(card.listingId, card.personId) {
            val profile = current
            val matchesOwner = profile != null && profile.identity.personId == card.personId
            val actions = enabled && token != null
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
                                .pointerInput(token, actions, density) {
                                    if (actions) detectHorizontalDragGestures(
                                        onHorizontalDrag = { change, delta ->
                                            change.consume()
                                            drag = (drag + delta).coerceIn(-300 * density, 300 * density)
                                        },
                                        onDragEnd = {
                                            val decision = swipePreference(drag / density)
                                            drag = 0f
                                            if (token != null && decision != null) onChoose(token, decision)
                                        },
                                        onDragCancel = { drag = 0f },
                                    )
                                },
                            originalUrl = profile?.identity?.profileUrl,
                            actionsEnabled = actions,
                            isCurrent = { token?.let(isProfileCurrent) == true },
                        )
                    }
                    item(key = "header") {
                        if (profile == null) ProfileIdentityHeader(card.name, card.city, hasVideo = card.hasVideo)
                        else ProfileHeader(profile.profile, profile.identity, person, profile.media, profile.fromArchive)
                        
                        profile?.warning?.let { Text(it) }
                        readError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { decks.remove(card.listingId); retry++ }, enabled = enabled) { Text("Profil erneut laden") }
                        }
                        if (profile == null && readError == null && visible) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    item(key = "actions") {
                        ProfileActionRail(person, profile?.profile?.phoneDisplay, !actions,
                            onFavorite = { token?.let(onFavorite) },
                            onPreference = { _, preference -> token?.let { onChoose(it, preference) } },
                            isCurrent = { token?.let(isProfileCurrent) == true })
                        TextButton(onClick = { cardToken?.let(onProfile) }, enabled = enabled && cardToken != null) { Text("Profil öffnen") }
                    }
                    if (profile != null) profileContent(profile.profile, profile.identity, person, profile.media,
                        panels, tab, selectTab, profile.candidates, profile.history, !actions,
                        owner?.let(notes::get) ?: NoteEditorState(), boundNotes, onConfirm = { target -> if (token != null) confirmation = target })
                    else card.description?.let { item { Text(it) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { token?.let { onChoose(it, Preference.DISLIKE) } },
                    enabled = actions, modifier = Modifier.weight(1f)) { Text("Nein") }
                OutlinedButton(onClick = { cardToken?.let(onSkip) },
                    enabled = enabled && cardToken != null, modifier = Modifier.weight(1f)) { Text("Später") }
                Button(onClick = { token?.let { onChoose(it, Preference.LIKE) } },
                    enabled = actions, modifier = Modifier.weight(1f)) { Text("Gefällt") }
            }
        }
    }
}

@Composable
fun ProfileThumbnail(url: String?, modifier: Modifier = Modifier, localPath: String? = null) {
    ProfileImage(url = url, localPath = localPath, modifier = modifier)
}
