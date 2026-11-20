package de.justify87.ladiesexplorer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.unit.IntOffset

@Composable
internal fun DiscoverScreen(
    state: DiscoverUiState,
    notes: Map<Long, NoteEditorState>,
    noteActions: NoteEditorActions,
    search: SearchUiState,
    rootBarHeight: Dp,
    onOpenSearch: () -> Unit,
    onSelectMode: (DiscoverMode) -> Unit,
    availabilityEnabled: Boolean,
    onAvailability: (SwipeDeckToken, AvailabilityMode) -> Unit,
    onCancelSearch: () -> Unit,
    onRefresh: (SwipeDeckToken) -> Unit,
    onReset: (SwipeDeckToken) -> Unit,
    onUndo: (SwipeDeckToken) -> Unit,
    onSwipeRestoreConsumed: (SwipeDeckToken, SwipePresentation) -> Unit,
    onReplayLikes: (SwipeDeckToken, Boolean) -> Unit,
    onSkip: (SwipeCardToken, SwipePresentation?) -> Unit,
    onChoose: (SwipeActionToken, Preference, SwipePresentation?) -> Unit,
    onSwipeVisibility: (Boolean) -> Unit,
    onRenderedSwipe: (SwipeActionToken?) -> Unit,
    onProjectionMismatch: (SwipeActionToken) -> Unit,
    onConfirmIdentity: (ProfileIdentityToken, Long) -> Unit,
    onSnapshotChanged: (Boolean) -> Unit,
    onFavorite: (ProfileIdentityToken) -> Unit,
    isProfileCurrent: (ProfileIdentityToken) -> Boolean,
    touchGate: ProfileTouchGate? = null,
    modifier: Modifier = Modifier,
) {
    val searchLoading = search.loading
    val active = search.response?.takeIf { it.searchRunId == state.activeRunId }
    val executedFiltersJson = active?.query?.filtersJson ?: "{}"
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
    var heroSize by remember { mutableStateOf(IntSize.Zero) }
    val decks = remember(state.activeRunId, state.swipeProfileRevision, started) {
        SwipeProfileWindow(mutableStateMapOf(), state.swipeExternalRevision)
    }
    DisposableEffect(decks) { onDispose { decks.close() } }
    var retry by remember(card?.listingId, card?.personId, state.swipeProfileRevision) { mutableIntStateOf(0) }
    var readError by remember(card?.listingId, card?.personId, state.swipeProfileRevision) { mutableStateOf<String?>(null) }
    var retryLocally by remember(card?.listingId, card?.personId, state.swipeProfileRevision) { mutableStateOf(false) }
    val current = card?.takeIf { decks.isCurrent(state.swipeExternalRevision) }?.let { decks[it] }
    val projectedToken = state.swipeToken(current)
    val token = projectedToken?.takeIf { visible && currentSwipeActionCard(state, searchLoading, it) != null }
    // Reconcile stale membership before admitting callbacks; do not loop on persistent mismatches.
    var lastMismatch by remember(state.activeRunId, card?.listingId, card?.personId) {
        mutableStateOf<Pair<Pair<Preference, Boolean>, Pair<Preference, Boolean>>?>(null)
    }
    LaunchedEffect(projectedToken, card?.preference, card?.favorite, visible) {
        if (!visible || projectedToken == null || card == null) return@LaunchedEffect
        val queueState = card.preference to card.favorite
        val profileState = projectedToken.preference to projectedToken.favorite
        if (queueState == profileState) lastMismatch = null
        else if (lastMismatch != (queueState to profileState)) {
            lastMismatch = queueState to profileState
            onProjectionMismatch(projectedToken)
        }
    }
    SideEffect { onRenderedSwipe(token) }
    val upcoming = prefetchCards(state.cards)
    val window = listOfNotNull(card) + upcoming
    SideEffect {
        decks.retain(window)
        decks.applyExternalRevision(state.swipeExternalRevision)
    }
    // Promotion preserves in-flight prefetch; pruning, revision changes and navigation cancel it.
    val readGate = remember(decks) { Mutex() }
    val latestReadState by rememberUpdatedState(state)
    val latestReadCard by rememberUpdatedState(card)
    val latestReadVisible by rememberUpdatedState(visible)
    val latestRetryLocally by rememberUpdatedState(retryLocally)
    val markCurrentRead by rememberUpdatedState<(DiscoveryCard) -> Unit>({ target ->
        if (sameSwipeCard(target, card)) readError = null
    })
    val reportCurrentRead by rememberUpdatedState<(DiscoveryCard, Exception?) -> Unit>({ target, error ->
        if (sameSwipeCard(target, card)) {
            readError = error?.message ?: if (error == null) null else "Profil konnte nicht geladen werden"
            retryLocally = if (error == null) false else retryLocally || error is LocalProfileReadFailure
            if (error == null) retry = 0
        }
    })
    for (target in window) key(target.listingId, target.personId) {
        val explicitRetry = if (sameSwipeCard(target, card)) retry else 0
        val slotControl = remember(decks, decks.invalidation(target), explicitRetry) { RequestControl() }
        DisposableEffect(slotControl) { onDispose { slotControl.cancel() } }
        LaunchedEffect(slotControl) {
            var promotionOnly = false
            var localRecovery = false
            fun eligible(): Boolean {
                val activeCard = latestReadCard
                if (!latestReadVisible || activeCard == null) return false
                if (sameSwipeCard(target, activeCard)) return true
                return !promotionOnly && decks[activeCard] != null
            }
            while (true) {
                snapshotFlow { eligible() }.first { it }
                var waitForDemotion = false
                val completed = readGate.withLock {
                    if (!eligible()) return@withLock false
                    if (decks[target] != null && explicitRetry == 0) return@withLock true
                    val currentRole = sameSwipeCard(target, latestReadCard)
                    if (currentRole) markCurrentRead(target)
                    val live = latestReadState
                    val policy = swipeReadPolicy(
                        currentRole, live.activeRunId, target.listingId,
                        localOnly = localRecovery || decks.requiresLocal(target) ||
                            (currentRole && latestRetryLocally) || target.listingId in live.localProfileStrictIds,
                        preferLocal = decks.invalidation(target) != 0L ||
                            target.listingId in live.localProfileReloadIds ||
                            live.swipeRestore?.let { it.listingId == target.listingId &&
                                it.ownerId == target.personId && it.runId == live.activeRunId } == true,
                    )
                    try {
                        decks.readCurrent(target) { invalidateOtherProfiles ->
                            core.readSwipeProfile(target.listingId, slotControl,
                                refresh = policy.refresh, allowNetwork = policy.allowNetwork,
                                onSourceRead = {
                                    invalidateOtherProfiles()
                                    onSnapshotChanged(decks.closed)
                                })
                        }
                        reportCurrentRead(target, null)
                        true
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        promotionOnly = true
                        localRecovery = localRecovery || failure is LocalProfileReadFailure
                        waitForDemotion = swipeFailureNeedsDemotion(currentRole,
                            sameSwipeCard(target, latestReadCard))
                        if (waitForDemotion) reportCurrentRead(target, failure)
                        false
                    }
                }
                if (completed) break
                // A failed current read retries only explicitly or after demotion and promotion.
                if (waitForDemotion) {
                    snapshotFlow { !sameSwipeCard(target, latestReadCard) }.first { it }
                }
            }
        }
        val model = decks[target]
        LaunchedEffect(decks, model?.revision, heroSize, started) {
            if (!started || sameSwipeCard(target, latestReadCard) || heroSize.width <= 0 || heroSize.height <= 0)
                return@LaunchedEffect
            snapshotFlow { latestReadVisible }.first { it }
            if (sameSwipeCard(target, latestReadCard)) return@LaunchedEffect
            try {
                warmSwipeImages(context, target, model, heroSize)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Prefetch failure must not affect deck membership.
            }
        }
    }

    val matchingRun = search.response?.searchRunId == state.activeRunId
    // Animation must not promote cards before the authoritative mutation.
    val cardIdentity = card?.let { it.listingId to it.personId }
    var flight by remember(state.activeRunId, state.swipePresentationGeneration, started) { mutableStateOf<SwipeFlight?>(null) }
    var liveDrag by remember(cardIdentity) { mutableFloatStateOf(0f) }
    var settledMedia by remember(cardIdentity, current?.revision) { mutableStateOf(false) }
    val motion = remember(flight) { Animatable(flight?.offset ?: 0f) }
    val latestState by rememberUpdatedState(state)
    val latestIdentity by rememberUpdatedState(cardIdentity)
    val latestReady by rememberUpdatedState(current != null && settledMedia || readError != null || card == null)
    LaunchedEffect(flight) {
        val outgoing = flight ?: return@LaunchedEffect
        // Bound admission; once admitted, wait for authoritative completion.
        val admitted = withTimeoutOrNull(350) {
            snapshotFlow {
                latestState.busy || latestState.swipeSessionId != outgoing.session ||
                    latestIdentity != outgoing.card
            }.first { it }
        } == true
        if (admitted) {
            snapshotFlow { latestState.busy }.first { !it }
            if (latestIdentity != outgoing.card && latestState.mutationError == null) {
                // Bound the visual wait for a cold image decode.
                withTimeoutOrNull(220) { snapshotFlow { latestReady }.first { it } }
                motion.animateTo(outgoing.destination, tween(200))
            }
        }
        flight = null
    }
    val transitioning = flight != null
    key(state.activeRunId, state.swipePresentationGeneration, card?.listingId, card?.personId) {
        val listState = rememberLazyListState()
        val capture = remember { SwipePresentationCapture() }
        var photoRequest by remember { mutableIntStateOf(0) }
        val profile = current
        var selectedMedia by remember { mutableStateOf<ProfileMediaItem?>(null) }
        var fullImage by remember(token) { mutableStateOf<ProfileMediaItem?>(null) }
        fullImage?.takeIf { token != null }?.let { ProfileImageDialog(it) { fullImage = null } }
        val person = profile?.person
        val identity = profile?.let { heroIdentity(it.profile, it.identity, it.fromArchive,
            it.media.any { medium -> medium.kind == "VIDEO" } || it.identity.hasVideo,
            it.person.preference, it.person.favorite) }
            ?: card?.let { HeroIdentity(it.name, place = it.city, hasVideo = it.hasVideo,
                preference = it.preference, favorite = it.favorite) }
        val actions = enabled && token != null && !transitioning
        val heroVisible by remember(listState) {
            derivedStateOf { profileHeroVisible(listState, "hero") }
        }
        val availability = search.headerAvailability()
        val menuContext = SwipeMenuContext(deckToken, cardToken, token.takeIf { !transitioning }, enabled && !transitioning,
            availabilityEnabled && matchingRun && started, matchingRun, availability, state.replayLikes, state.canUndoSwipe,
            selectedMedia?.let { "${it.kind}:${it.url}:${it.localPath}" })
        val summary = if (state.activeRunId == null) "Lokaler Bestand · neutral"
            else active?.let { "${it.city} · ${it.requestedRadiusKm} km" } ?: "Aktive Suche"
        val counts = if (state.activeRunId == null) "${state.cards.size} im Deck"
            else "Quelle: ${active?.serverCount?.takeIf { active.radiusVerified } ?: "unbekannt"} · ${state.activeVisibleCount} sichtbar · ${state.cards.size} im Deck"
        val notices = buildList {
            if (search.loading) add(ProfileNotice("Suche läuft", if (search.searchCancelling) "Suche wird beendet …" else "Suche läuft …",
                "Abbrechen", !search.searchCancelling, onCancelSearch))
            if (active != null && (active.partial || !active.radiusVerified)) add(ProfileNotice("Teilergebnis / Radius",
                "Teilergebnis / Radius nicht bestätigt", warning = true))
            search.error?.let { add(ProfileNotice("Suchfehler", it, warning = true)) }
            state.mergeNotice?.let { add(ProfileNotice("Identität", it)) }
            state.mutationError?.let { add(ProfileNotice("Entscheidungsfehler", it, warning = true)) }
            state.error?.let { add(ProfileNotice("Deckfehler", it, warning = true)) }
            profile?.warning?.let { add(ProfileNotice("Profilhinweis", it, warning = true)) }
            if (visible && projectedToken != null && token == null) add(ProfileNotice("Profilzustand",
                "Profil- und Deckzustand unterscheiden sich. Bitte Ansicht aktualisieren.", warning = true))
            readError?.let { error ->
                add(ProfileNotice("Profilfehler", error, "Erneut laden", enabled, {
                    card?.let { decks.remove(it.listingId); retry++ }
                }, warning = true))
            }
            if (card != null && profile == null && readError == null && visible)
                add(ProfileNotice("Profil wird geladen", "Profil wird geladen …"))
            if (state.busy) add(ProfileNotice("Wird gespeichert", "Entscheidung oder Aktualisierung wird verarbeitet."))
        }
        fun beginFlight(direction: Float) {
            val id = cardIdentity ?: return
            val layout = listState.layoutInfo
            val hero = layout.visibleItemsInfo.firstOrNull { it.key == "hero" }
            val selected = selectedMedia?.takeIf { it.kind == "IMAGE" }
                ?: card?.takeIf { selectedMedia?.kind != "VIDEO" }?.let { row ->
                    row.thumbnailUrl?.let { ProfileMediaItem("IMAGE", it, row.localPath, 0u) }
                }
            flight = SwipeFlight(id, deckToken.session, direction, liveDrag, heroSize,
                (hero?.offset ?: 0) - layout.viewportStartOffset,
                selected.takeIf { heroVisible && hero != null }, identity.takeIf { heroVisible })
        }
        val undo = { if (flight == null) onUndo(deckToken) }
        val skip = {
            if (flight == null) cardToken?.let { beginFlight(-1f); onSkip(it, capture.read()) }
            Unit
        }
        val choose: (Preference) -> Unit = { preference ->
            if (flight == null) token?.takeIf(isProfileCurrent)?.let {
                if (preference != Preference.NONE) beginFlight(if (preference == Preference.LIKE) 1f else -1f)
                onChoose(it, preference, capture.read())
            }
        }
        ProfileShell(modifier = modifier, listState = listState, heroKey = "hero", rootHeight = rootBarHeight,
            hasRail = true, notices = notices, noticeOwner = menuContext, fixedNoticeSlot = true,
            header = { presentation, compactNotice, onNotice ->
                SwipeShellHeader(
                    presentation = presentation, identity = identity, context = menuContext,
                    summary = summary, counts = counts, executed = active != null, compactNotice = compactNotice,
                    onNotice = onNotice, onPhoto = { photoRequest++ }, onSearch = onOpenSearch,
                    onMode = onSelectMode, onAvailability = { onAvailability(deckToken, it) }, onRefresh = { onRefresh(deckToken) },
                    onReset = { onReset(deckToken) }, onReplay = { onReplayLikes(deckToken, !state.replayLikes) },
                    onFavorite = { token?.let(onFavorite) }, onChoose = choose, onUndo = undo, onSkip = skip,
                    onOpenImage = selectedMedia?.takeIf { it.kind == "IMAGE" }?.let { item ->
                        { if (token?.let(isProfileCurrent) == true) fullImage = item }
                    },
                    onOriginal = profile?.identity?.profileUrl?.let { url ->
                        { if (token?.let(isProfileCurrent) == true) openExternal(context, url) }
                    },
                )
            },
            rail = { SwipeDecisionControls(menuContext, identity?.title, undo, skip, choose) },
        ) { presentation, onIdentityMeasured ->
            Box(Modifier.fillMaxSize().clipToBounds()) {
            if (card == null) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        if (state.loading) CircularProgressIndicator()
                        else {
                            Text("Deck beendet", style = MaterialTheme.typography.titleLarge)
                            Text("Keine weiteren passenden Profile. Reset beginnt die Warteschlange erneut, ohne Entscheidungen zu löschen.")
                            OutlinedButton(onClick = { if (flight == null) onReset(deckToken) }, enabled = enabled && !transitioning) { Text("Deck neu beginnen") }
                        }
                    }
                }
            } else {
                val fallback = listOfNotNull(card.thumbnailUrl?.let { ProfileMediaItem("IMAGE", it, card.localPath, 0u) }
                    ?: card.localPath?.let { ProfileMediaItem("IMAGE", "", it, 0u) })
                ProfileFlow(state = listState, model = profile, filters = executedFiltersJson,
                    token = token, actionsBusy = !actions, notes = notes, noteActions = noteActions,
                    isProfileCurrent = isProfileCurrent, onConfirmIdentity = onConfirmIdentity,
                    modifier = Modifier.fillMaxSize(),
                    paddedDetails = true, photoRequest = photoRequest, onGallerySelection = { selectedMedia = it },
                    userScrollEnabled = !transitioning,
                    swipeRestore = state.swipeRestore, swipeRunId = state.activeRunId, swipeCapture = capture,
                    onSwipeRestoreConsumed = { onSwipeRestoreConsumed(deckToken, it) }) {
                    item(key = "hero") {
                        ProfileMediaHero(media = profile?.media?.ifEmpty { fallback } ?: fallback,
                            height = presentation.heroHeight,
                            onSwipe = if (actions && token != null) ({ decision ->
                                choose(decision)
                            }) else null,
                            modifier = Modifier.onSizeChanged { heroSize = it }, selection = mediaSelection,
                            heldDrag = flight?.takeIf { it.card == cardIdentity }?.offset,
                            onSwipeDrag = { liveDrag = it },
                            playbackVisible = heroVisible,
                            galleryNavigationEnabled = !transitioning,
                            onMediaSettled = { settledMedia = it },
                            identity = identity, actionToken = token,
                            projectionAvailable = profile != null, actionsEnabled = actions,
                            isCurrent = { token?.let(isProfileCurrent) == true },
                            compact = presentation.budget.compactGallery,
                            headerOwnsIdentity = presentation.identityInHeader,
                            onIdentityMeasured = onIdentityMeasured, touchGate = touchGate)
                    }
                    item(key = "header") {
                        Text("Profilinfos", style = MaterialTheme.typography.labelMedium)
                        if (profile != null) ProfileMetadata(profile.profile, profile.identity, person, profile.media, profile.fromArchive)
                    }
                    if (profile == null) card.description?.let { item(key = "fallback-description") { Text(it) } }
                }
            }
            val old = flight?.takeIf { it.card != cardIdentity }
            val oldMedia = old?.media
            if (old != null && oldMedia != null && old.size.width > 0 && old.size.height > 0) {
                val density = LocalDensity.current
                Box(Modifier.offset { IntOffset(0, old.top) }
                    .size(with(density) { old.size.width.toDp() }, with(density) { old.size.height.toDp() })
                    .graphicsLayer {
                        translationX = motion.value
                        rotationZ = (motion.value / size.width.coerceAtLeast(1f) * 12f).coerceIn(-12f, 12f)
                        alpha = 1f - (kotlin.math.abs(motion.value) / kotlin.math.abs(old.destination)).coerceIn(0f, 1f) * .2f
                    }.clearAndSetSemantics {}) {
                    ProfileImage(oldMedia.url, oldMedia.localPath, Modifier.fillMaxSize(), allowRetry = false)
                    old.identity?.let { HeroIdentityOverlay(it, Modifier.align(Alignment.BottomCenter)) }
                }
            }
            }
        }
    }
}

@Composable
fun ProfileThumbnail(url: String?, modifier: Modifier = Modifier, localPath: String? = null) {
    ProfileImage(url = url, localPath = localPath, modifier = modifier)
}

/** Menu actions retain their opening context. */
@Composable
private fun SwipeShellHeader(
    presentation: ProfileShellPresentation, identity: HeroIdentity?, context: SwipeMenuContext,
    summary: String, counts: String, executed: Boolean, compactNotice: ProfileNoticeIndicator?,
    onNotice: () -> Unit, onPhoto: () -> Unit, onSearch: () -> Unit,
    onMode: (DiscoverMode) -> Unit, onAvailability: (AvailabilityMode) -> Unit,
    onRefresh: () -> Unit, onReset: () -> Unit, onReplay: () -> Unit, onFavorite: () -> Unit,
    onChoose: (Preference) -> Unit, onUndo: () -> Unit, onSkip: () -> Unit,
    onOpenImage: (() -> Unit)?, onOriginal: (() -> Unit)?,
) {
    var opened by remember { mutableStateOf<SwipeMenuContext?>(null) }
    val latest by rememberUpdatedState(context)
    DisposableEffect(Unit) { onDispose { opened = null } }
    val openContext = opened
    val expanded = swipeMenuCurrent(openContext, context)
    LaunchedEffect(context) { if (!swipeMenuCurrent(opened, context)) opened = null }
    fun dispatch(action: () -> Unit) {
        if (opened != openContext || !swipeMenuCurrent(openContext, latest)) return
        opened = null
        action()
    }
    val shown = identity.takeIf { presentation.identityInHeader }
    var info by remember(context, identity) { mutableStateOf(false) }
    if (info && identity != null) IdentityInfoDialog(identity) { info = false }
    Row(Modifier.fillMaxSize().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileShellTitle(shown?.title ?: "Ladies Explorer",
            swipeContextShort(context.deck.runId != null, context.matchingRun, context.availability),
            shown?.let { listOfNotNull(it.status, it.shortDecision, it.place).joinToString(" · ") } ?: summary,
            presentation.budget.shortHeader, Modifier.weight(1f),
            onClick = if (shown == null) null else ({ info = true }))
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            if (compactNotice != null) ProfileStatusButton(compactNotice, onNotice)
        }
        if (!presentation.budget.narrowHeader) {
            if (presentation.photoShortcut) IconButton(onClick = onPhoto) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Zum Foto")
            } else IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Suche öffnen") }
        }
        Box {
            IconButton(onClick = { opened = context }) { Icon(Icons.Default.MoreVert, "Filter, Modus und Deckaktionen") }
            DropdownMenu(expanded = expanded, onDismissRequest = { opened = null }) {
                if (identity != null) {
                    Text("${identity.title}\n${identity.subtitle}\n${identity.decisions}",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    DropdownMenuItem(text = { Text("Vollständige Profilinformationen") }, onClick = { dispatch { info = true } })
                }
                Text(swipeContextLabel(summary, executed, context.matchingRun, context.availability),
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                Text(counts, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                if (compactNotice != null) DropdownMenuItem(text = { Text("Status und Hinweise") }, onClick = { dispatch(onNotice) })
                if (presentation.photoShortcut && context.card != null) DropdownMenuItem(text = { Text("Zum Foto") }, onClick = { dispatch(onPhoto) })
                if (presentation.budget.railInMenu && (context.card != null || context.canUndo)) {
                    DropdownMenuItem(text = { Text("Undo") }, enabled = context.deckEnabled && context.canUndo, onClick = { dispatch(onUndo) })
                    DropdownMenuItem(text = { Text("Nein") }, enabled = context.profile != null, onClick = { dispatch { onChoose(Preference.DISLIKE) } })
                    DropdownMenuItem(text = { Text("Später") }, enabled = context.deckEnabled && context.card != null, onClick = { dispatch(onSkip) })
                    DropdownMenuItem(text = { Text("Gefällt") }, enabled = context.profile != null, onClick = { dispatch { onChoose(Preference.LIKE) } })
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text("Suche und Filter öffnen") }, onClick = { dispatch(onSearch) })
                Text(when {
                    !context.matchingRun -> "Suchlauf wird übernommen"
                    executed -> "Ausgeführte Verfügbarkeit"
                    else -> "Verfügbarkeit (Suchentwurf)"
                },
                    Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                AvailabilityMode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(if (context.availability == mode) "✓ ${mode.label}" else mode.label) },
                        enabled = context.availabilityEnabled, onClick = { dispatch { onAvailability(mode) } })
                }
                HorizontalDivider()
                DiscoverMode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(if (mode == DiscoverMode.Swipe) "✓ ${mode.title}" else mode.title) },
                        onClick = { dispatch { onMode(mode) } })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Aktualisieren") }, enabled = context.deckEnabled, onClick = { dispatch(onRefresh) })
                DropdownMenuItem(text = { Text("Deck zurücksetzen") }, enabled = context.deckEnabled, onClick = { dispatch(onReset) })
                if (context.deck.runId != null) DropdownMenuItem(text = { Text(if (context.replay) "Likes nicht erneut zeigen" else "Likes erneut zeigen") },
                    enabled = context.deckEnabled, onClick = { dispatch(onReplay) })
                context.profile?.let { profile ->
                    HorizontalDivider()
                    onOpenImage?.let { action -> DropdownMenuItem(text = { Text("Bild groß anzeigen") }, onClick = { dispatch(action) }) }
                    onOriginal?.let { action -> DropdownMenuItem(text = { Text("Originalprofil öffnen") }, onClick = { dispatch(action) }) }
                    DropdownMenuItem(text = { Text(if (profile.favorite) "Favorit entfernen" else "Als Favorit speichern") },
                        onClick = { dispatch(onFavorite) })
                    if (profile.preference != Preference.NONE) DropdownMenuItem(text = { Text("Präferenz zurücksetzen") },
                        onClick = { dispatch { onChoose(Preference.NONE) } })
                }
            }
        }
    }
}

@Composable
internal fun SwipeDecisionControls(context: SwipeMenuContext, profileTitle: String?, onUndo: () -> Unit,
    onSkip: () -> Unit, onChoose: (Preference) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f
        Row(Modifier.fillMaxSize().semantics { contentDescription = profileTitle?.let { "Entscheidungen für $it" } ?: "Deckentscheidungen" },
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (compact) {
                IconButton(onClick = onUndo, enabled = context.deckEnabled && context.canUndo) {
                    Icon(Icons.Default.Undo, "Undo")
                }
                IconButton(onClick = { onChoose(Preference.DISLIKE) }, enabled = context.profile != null) { Icon(Icons.Default.Close, "Nein") }
                IconButton(onClick = onSkip, enabled = context.deckEnabled && context.card != null) { Icon(Icons.Default.SkipNext, "Später") }
                IconButton(onClick = { onChoose(Preference.LIKE) }, enabled = context.profile != null) { Icon(Icons.Default.Favorite, "Gefällt") }
            } else {
                TextButton(onClick = onUndo, enabled = context.deckEnabled && context.canUndo, modifier = Modifier.fillMaxHeight()) { Text("Undo") }
                OutlinedButton(onClick = { onChoose(Preference.DISLIKE) }, enabled = context.profile != null,
                    modifier = Modifier.fillMaxHeight(), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Nein") }
                TextButton(onClick = onSkip, enabled = context.deckEnabled && context.card != null, modifier = Modifier.fillMaxHeight()) { Text("Später") }
                Button(onClick = { onChoose(Preference.LIKE) }, enabled = context.profile != null,
                    modifier = Modifier.fillMaxHeight(), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Gefällt") }
            }
        }
    }
}
