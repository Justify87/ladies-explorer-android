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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    encounters: EncounterEditor? = null,
    prices: PriceEditor? = null,
) {
    val searchLoading = search.loading
    val active = search.response?.takeIf { it.searchRunId == state.activeRunId }
    val card = state.cards.firstOrNull()
    val executedFiltersJson = executedProfileMatchContext(active, card?.personId).encode()
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
            var attemptedCurrentSource = false
            fun readPolicy(currentRole: Boolean): SwipeReadPolicy {
                val live = latestReadState
                return swipeReadPolicy(
                    currentRole, live.activeRunId, target.listingId,
                    localOnly = localRecovery || decks.requiresLocal(target) ||
                        (currentRole && latestRetryLocally) || target.listingId in live.localProfileStrictIds,
                    preferLocal = decks.invalidation(target) != 0L ||
                        target.listingId in live.localProfileReloadIds ||
                        live.swipeRestore?.let { it.listingId == target.listingId &&
                            it.ownerId == target.personId && it.runId == live.activeRunId } == true,
                )
            }
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
                    val currentRole = sameSwipeCard(target, latestReadCard)
                    val policy = readPolicy(currentRole)
                    val cached = decks[target]
                    if (cached != null && explicitRetry == 0 &&
                        !(currentRole && shouldRefreshPromotedCurrent(policy, cached, attemptedCurrentSource)))
                        return@withLock true
                    if (currentRole) markCurrentRead(target)
                    if (currentRole && policy.refresh) attemptedCurrentSource = true
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
                if (completed) {
                    // Keep completed speculation alive until promotion, without canceling an in-flight read.
                    snapshotFlow { sameSwipeCard(target, latestReadCard) }.first { it }
                    if (!shouldRefreshPromotedCurrent(readPolicy(true), decks[target], attemptedCurrentSource))
                        break
                    continue
                }
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
        val controls = SwipeControlsContext(deckToken, cardToken, token.takeIf { !transitioning }, enabled && !transitioning,
            availabilityEnabled && matchingRun && started, availability, state.replayLikes, state.canUndoSwipe)
        val sourceContext = if (!matchingRun) "Suchwechsel" else if (active == null) "Lokaler Bestand" else "Aktive Suche"
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
                ?: card.takeIf { selectedMedia?.kind != "VIDEO" }?.let { row ->
                    row.thumbnailUrl?.let { ProfileMediaItem("IMAGE", it, row.localPath, 0u) }
                }
            flight = SwipeFlight(id, deckToken.session, direction, liveDrag, heroSize,
                (hero?.offset ?: 0) - layout.viewportStartOffset,
                selected.takeIf { heroVisible && hero != null })
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
            hasRail = true, notices = notices, noticeOwner = controls, fixedNoticeSlot = true,
            heroLeadingHeight = discoveryLeadingHeight(LocalDensity.current.fontScale).dp,
            header = { presentation, compactNotice, onNotice ->
                SwipeShellHeader(presentation, sourceContext, controls, compactNotice,
                    onNotice, onOpenSearch, { onAvailability(deckToken, it) })
            },
            rail = { SwipeDecisionControls(controls, identity?.title, undo, skip, choose) },
        ) { presentation ->
            Box(Modifier.fillMaxSize().clipToBounds()) {
            if (card == null) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "discover-context") {
                        DiscoverModeSwitcher(DiscoverMode.Swipe, onSelectMode, Modifier)
                    }
                    if (presentation.budget.railInContent) item {
                        SwipeDecisionControls(controls, identity?.title, undo, skip, choose,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                    }
                    item {
                        if (state.loading || searchLoading) CircularProgressIndicator()
                        else {
                            Text("Deck beendet", style = MaterialTheme.typography.titleLarge)
                            Text("Keine weiteren passenden Profile. Reset beginnt die Warteschlange erneut, ohne Entscheidungen zu löschen.")
                            SwipeDeckActions(controls, { onRefresh(deckToken) }, { onReset(deckToken) },
                                { onReplayLikes(deckToken, !state.replayLikes) })
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
                    onSwipeRestoreConsumed = { onSwipeRestoreConsumed(deckToken, it) }, encounters = encounters, prices = prices,
                    fallbackIdentity = identity, afterHeading = {
                    item(key = "profile-actions") {
                        Column {
                            TextButton(onClick = { photoRequest++ }) { Text("Zum Foto") }
                            val image = selectedMedia?.takeIf { it.kind == "IMAGE" }
                            if (image != null) TextButton(enabled = actions, onClick = {
                                if (token?.let(isProfileCurrent) == true && selectedMedia == image) fullImage = image
                            }) { Text("Bild groß anzeigen") }
                            profile?.identity?.profileUrl?.let { url -> TextButton(enabled = actions, onClick = {
                                if (token?.let(isProfileCurrent) == true) openExternal(context, url)
                            }) { Text("Originalprofil öffnen") } }
                            if (token != null && token.preference != Preference.NONE)
                                TextButton(enabled = actions, onClick = { choose(Preference.NONE) }) { Text("Präferenz zurücksetzen") }
                            SwipeDeckActions(controls, { onRefresh(deckToken) }, { onReset(deckToken) },
                                { onReplayLikes(deckToken, !state.replayLikes) })
                        }
                    }
                    if (profile == null) card.description?.let { item(key = "fallback-description") { Text(it) } }
                    }) {
                    item(key = "discover-context") {
                        DiscoverModeSwitcher(DiscoverMode.Swipe, onSelectMode, Modifier)
                    }
                    if (presentation.budget.railInContent) item(key = "compact-decisions") {
                        SwipeDecisionControls(controls, identity?.title, undo, skip, choose,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                    }
                    item(key = "hero") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            ProfileMediaHero(media = profile?.media?.ifEmpty { fallback } ?: fallback,
                            height = presentation.heroHeight,
                            onSwipe = if (actions) ({ decision ->
                                choose(decision)
                            }) else null,
                            modifier = Modifier.onSizeChanged { heroSize = it }, selection = mediaSelection,
                            heldDrag = flight?.takeIf { it.card == cardIdentity }?.offset,
                            onSwipeDrag = { liveDrag = it },
                            playbackVisible = heroVisible,
                            galleryNavigationEnabled = !transitioning,
                            onMediaSettled = { settledMedia = it },
                            actionToken = token,
                            favorite = identity?.favorite, onFavorite = { token?.takeIf(isProfileCurrent)?.let(onFavorite) },
                            imageScale = androidx.compose.ui.layout.ContentScale.Fit,
                            projectionAvailable = profile != null, actionsEnabled = actions,
                            isCurrent = { token?.let(isProfileCurrent) == true },
                            compact = presentation.budget.compactGallery,
                            touchGate = touchGate)
                        }
                    }

                }
            }
            val old = flight?.takeIf { it.card != cardIdentity }
            val oldMedia = old?.media
            if (old != null && oldMedia != null && old.size.width > 0 && old.size.height > 0) {
                val density = LocalDensity.current
                Box(Modifier.offset { IntOffset(12.dp.roundToPx(), old.top) }
                    .size(with(density) { old.size.width.toDp() }, with(density) { old.size.height.toDp() })
                    .graphicsLayer {
                        translationX = motion.value
                        rotationZ = (motion.value / size.width.coerceAtLeast(1f) * 12f).coerceIn(-12f, 12f)
                        alpha = 1f - (kotlin.math.abs(motion.value) / kotlin.math.abs(old.destination)).coerceIn(0f, 1f) * .2f
                    }.clip(MaterialTheme.shapes.large).clearAndSetSemantics {}) {
                    ProfileImage(oldMedia.url, oldMedia.localPath, Modifier.fillMaxSize(),
                        scale = androidx.compose.ui.layout.ContentScale.Fit, allowRetry = false)
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

@Composable
private fun SwipeShellHeader(
    presentation: ProfileShellPresentation, sourceContext: String, context: SwipeControlsContext,
    compactNotice: ProfileNoticeIndicator?, onNotice: () -> Unit,
    onSearch: () -> Unit, onAvailability: (AvailabilityMode) -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileShellTitle("Entdecken", sourceContext, "", presentation.budget.shortHeader, Modifier.weight(1f))
        if (compactNotice != null) ProfileStatusButton(compactNotice, onNotice)
        if (!presentation.budget.narrowHeader) ShellIconButton(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = "Suche öffnen")
        }
        AvailabilityQuickChoice(context.availability, context.availabilityEnabled, context.deck, onAvailability)
    }
}

@Composable
private fun SwipeDeckActions(context: SwipeControlsContext, onRefresh: () -> Unit, onReset: () -> Unit, onReplay: () -> Unit) {
    FlowRow {
        TextButton(onClick = onRefresh, enabled = context.deckEnabled) { Text("Aktualisieren") }
        TextButton(onClick = onReset, enabled = context.deckEnabled) { Text("Deck neu beginnen") }
        if (context.deck.runId != null) TextButton(onClick = onReplay, enabled = context.deckEnabled) {
            Text(if (context.replay) "Likes nicht erneut zeigen" else "Likes erneut zeigen")
        }
    }
}

@Composable
internal fun SwipeDecisionControls(context: SwipeControlsContext, profileTitle: String?, onUndo: () -> Unit,
    onSkip: () -> Unit, onChoose: (Preference) -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface)) {
        val compact = maxHeight < 72.dp || maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.surface.luminance() < .5f
        val reject = if (dark) Color(0xFFF72F49) else scheme.error
        val like = if (dark) Color(0xFF4CDA36) else scheme.tertiary
        FlowRow(Modifier.fillMaxSize().semantics { contentDescription = profileTitle?.let { "Entscheidungen für $it" } ?: "Deckentscheidungen" },
            horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.Center) {
            DecisionAction("Undo", Icons.Default.Undo, context.deckEnabled && context.canUndo, compact,
                scheme.surfaceContainerHigh, scheme.onSurface, onUndo)
            DecisionAction("Nein", Icons.Default.Close, context.profile != null, compact,
                reject, if (dark) Color(0xFF1E160F) else scheme.onError) { onChoose(Preference.DISLIKE) }
            DecisionAction("Später", Icons.Default.SkipNext, context.deckEnabled && context.card != null, compact,
                scheme.surfaceContainerHigh, scheme.onSurface, onSkip)
            DecisionAction("Gefällt", Icons.Default.Favorite, context.profile != null, compact,
                like, if (dark) Color(0xFF153A0C) else scheme.onTertiary) { onChoose(Preference.LIKE) }
        }
    }
}

@Composable
private fun DecisionAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, compact: Boolean, background: Color, foreground: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.size(48.dp).shellFocus().background(
                if (enabled) background else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)) {
            Icon(icon, contentDescription = label,
                tint = if (enabled) foreground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f))
        }
        if (!compact) Text(label, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clearAndSetSemantics {},
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
