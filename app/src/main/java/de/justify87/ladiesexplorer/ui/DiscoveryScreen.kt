package de.justify87.ladiesexplorer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.unit.IntOffset

@Composable
internal fun DiscoverScreen(
    state: DiscoverUiState,
    notes: Map<Long, NoteEditorState>,
    noteActions: NoteEditorActions,
    search: SearchUiState,
    rootBarHeight: Dp,
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
    val visibleCards = state.cards.takeUnless { search.activeProjectionPending }.orEmpty()
    val card = visibleCards.firstOrNull()
    val executedFiltersJson = executedProfileMatchContext(active, card?.personId, state.globalVisibility).encode()
    val deckToken = state.deckToken()
    val cardToken = state.cardToken().takeIf { card != null }
    val context = LocalContext.current
    val contentInset = shellContentInset()
    val core = (context.applicationContext as LadiesApplication).coreStore
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(started) {
        onSwipeVisibility(started)
        onDispose { onSwipeVisibility(false) }
    }
    val visible = started && !searchLoading && !search.activeProjectionPending && !state.loading && !state.busy
    val enabled = visible
    var heroSize by remember { mutableStateOf(IntSize.Zero) }
    var discoveryContextHeight by remember { mutableIntStateOf(0) }
    val decks = remember(state.activeRunId, state.swipeProfileRevision, started) {
        SwipeProfileWindow(mutableStateMapOf(), state.swipeExternalRevision)
    }
    val preparation = remember(decks) { ProfilePreparationWindow() }
    val videoFiles = (context.applicationContext as LadiesApplication).galleryVideoFiles
    val failures = remember(decks) { mutableStateMapOf<Pair<Long, Long>, SwipeReadFailure>() }
    val localRetries = remember(decks) { mutableStateMapOf<Pair<Long, Long>, Boolean>() }
    var activeRead by remember(decks) { mutableStateOf<Pair<Pair<Long, Long>, RequestControl>?>(null) }
    var activeWork by remember(decks) { mutableStateOf<Pair<Pair<Long, Long>, Job>?>(null) }
    DisposableEffect(decks) {
        onDispose {
            activeRead?.second?.cancel()
            activeWork?.second?.cancel()
            decks.close()
            preparation.clear()
        }
    }
    val window = swipeReadWindow(visibleCards)
    val windowKeys = window.map { it.listingId to it.personId }.toSet()
    val cardKey = card?.let { it.listingId to it.personId }
    val prepared = preparation.displayed(cardKey)?.takeIf { decks.isCurrent(state.swipeExternalRevision) }
    val current = card?.takeIf { decks.isCurrent(state.swipeExternalRevision) }
        ?.let { decks[it] }?.takeIf { model -> prepared?.model?.let { shown ->
            shown.local == model.local && shown.fromArchive == model.fromArchive && shown.warning == model.warning
        } == true }
    val displayed = prepared?.model
    val readError = cardKey?.let { failures[it] }?.takeIf { it.current }?.message
    val projectedToken = state.swipeToken(current)
    val token = projectedToken?.takeIf { visible && currentSwipeActionCard(state, searchLoading, it) != null }
    var lastMismatch by remember(state.activeRunId, cardKey) {
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
    SideEffect {
        onRenderedSwipe(token)
        if (!decks.isCurrent(state.swipeExternalRevision)) {
            preparation.clear()
            failures.clear()
            activeRead?.second?.cancel()
            activeWork?.second?.cancel()
        }
        decks.retain(window)
        decks.applyExternalRevision(state.swipeExternalRevision)
        preparation.retain(windowKeys)
        failures.keys.toList().filterNot { it in windowKeys }.forEach { failures.remove(it) }
        failures.toMap().forEach { (key, failure) ->
            if (key != cardKey && failure.current) failures[key] = failure.copy(current = false)
        }
        localRetries.keys.toList().filterNot { it in windowKeys }.forEach { localRetries.remove(it) }
        activeRead?.takeIf { it.first !in windowKeys }?.second?.cancel()
        activeWork?.takeIf { it.first !in windowKeys }?.second?.cancel()
    }
    val latestReadState by rememberUpdatedState(state)
    val latestWindow by rememberUpdatedState(window)
    val latestReadVisible by rememberUpdatedState(visible)
    val latestSearch by rememberUpdatedState(active)
    fun filtersFor(target: DiscoveryCard): String =
        executedProfileMatchContext(latestSearch, target.personId, latestReadState.globalVisibility).encode()

    // Choose again after every profile read, panel read or medium; no speculative mutex queue.
    LaunchedEffect(decks) {
        try {
            while (isActive) {
                val target = snapshotFlow {
                    if (!latestReadVisible || decks.closed) null else nextProfileWork(latestWindow) { candidate ->
                        val key = candidate.listingId to candidate.personId
                        val currentRole = sameSwipeCard(candidate, latestWindow.firstOrNull())
                        val failure = failures[key]
                        val model = decks[candidate]
                        if (model == null) swipeReadMayRetry(failure, currentRole)
                        else heroSize.width > 0 && heroSize.height > 0 &&
                            !preparation.ready(model, filtersFor(candidate), heroSize)
                    }
                }.first { it != null } ?: continue
                val key = target.listingId to target.personId
                videoFiles.retainPins(preparation, preparation.pinnedVideos())
                if (!latestReadVisible || latestWindow.none { sameSwipeCard(it, target) }) continue
                val work = launch(start = CoroutineStart.LAZY) {
                    val model = decks[target]
                    if (model == null) {
                        val live = latestReadState
                        val currentRole = sameSwipeCard(target, latestWindow.firstOrNull())
                        val policy = swipeReadPolicy(currentRole, live.activeRunId, target.listingId,
                            localOnly = localRetries[key] == true || decks.requiresLocal(target) ||
                                target.listingId in live.localProfileStrictIds,
                            preferLocal = target.listingId in live.localProfileReloadIds ||
                                live.swipeRestore?.let { it.listingId == target.listingId &&
                                    it.ownerId == target.personId && it.runId == live.activeRunId } == true)
                        val control = RequestControl()
                        activeRead = key to control
                        try {
                            val loaded = decks.readCurrent(target) { invalidateOtherProfiles ->
                                core.readSwipeProfile(target.listingId, control,
                                    refresh = policy.refresh, allowNetwork = policy.allowNetwork,
                                    onSourceRead = {
                                        invalidateOtherProfiles()
                                        onSnapshotChanged(decks.closed)
                                    })
                            }
                            if (loaded != null) {
                                failures.remove(key)
                                localRetries.remove(key)
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            if (latestWindow.any { sameSwipeCard(it, target) }) {
                                if (failure is LocalProfileReadFailure) localRetries[key] = true
                                failures[key] = SwipeReadFailure(currentRole && sameSwipeCard(target, latestWindow.firstOrNull()),
                                    failure.message ?: "Profil konnte nicht geladen werden")
                            }
                        } finally {
                            activeRead = null
                            control.cancel()
                        }
                    } else {
                        val filters = filtersFor(target)
                        val size = heroSize
                        preparation.step(model, filters, size,
                            { core.prepareProfilePanels(model, filters) },
                            { medium, remaining -> prepareProfileMedium(context, medium, size, remaining, preparation) },
                            accept = { decks[target] == model && latestWindow.any { sameSwipeCard(it, target) } &&
                                size == heroSize && filters == filtersFor(target) })
                    }
                }
                activeWork = key to work
                work.start()
                work.join()
                activeWork = null
                videoFiles.retainPins(preparation, preparation.pinnedVideos())
            }
        } finally {
            withContext(NonCancellable) { videoFiles.retainPins(preparation, emptySet()) }
        }
    }
    LaunchedEffect(preparation, windowKeys) {
        videoFiles.retainPins(preparation, preparation.pinnedVideos())
    }

    val matchingRun = search.response?.searchRunId == state.activeRunId
    // Animation must not promote cards before the authoritative mutation.
    val cardIdentity = card?.let { it.listingId to it.personId }
    var flight by remember(state.activeRunId, state.swipePresentationGeneration, started) { mutableStateOf<SwipeFlight?>(null) }
    var liveDrag by remember(cardIdentity) { mutableFloatStateOf(0f) }
    val motion = remember(flight) { Animatable(flight?.offset ?: 0f) }
    val latestState by rememberUpdatedState(state)
    val latestIdentity by rememberUpdatedState(cardIdentity)
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
        val profile = displayed
        var selectedMedia by remember { mutableStateOf<ProfileMediaItem?>(null) }
        var visual by remember(profile?.media?.map { it.presentationKey() }) { mutableStateOf<HeroVisual?>(null) }
        var heroCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        var bodyCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        var fullImage by remember(cardIdentity) { mutableStateOf<ProfileMediaItem?>(null) }
        fullImage?.takeIf { item -> profile?.media?.any { it.presentationKey() == item.presentationKey() } == true }
            ?.let { ProfileImageDialog(it) { fullImage = null } }
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
                    card?.let { target ->
                        val key = target.listingId to target.personId
                        failures.remove(key)
                        decks.remove(target.listingId)
                        preparation.remove(key)
                    }
                }, warning = true))
            }
            if (prepared?.degraded == true) add(ProfileNotice("Medienhinweis", "Einige Profilinhalte konnten nicht vorbereitet werden.", warning = true))
            if (card != null && profile == null && readError == null && visible)
                add(ProfileNotice("Profil wird geladen", "Profil wird geladen …"))
            if (state.busy) add(ProfileNotice("Wird gespeichert", "Entscheidung oder Aktualisierung wird verarbeitet."))
        }
        fun beginFlight(direction: Float) {
            val id = cardIdentity ?: return
            val body = bodyCoordinates?.takeIf { it.isAttached }
            val hero = heroCoordinates?.takeIf { it.isAttached }
            val origin = if (body != null && hero != null) body.localPositionOf(hero, Offset.Zero) else Offset.Zero
            val size = hero?.size ?: heroSize
            val fullyVisible = body != null && hero != null && heroVisible &&
                origin.x >= -1f && origin.y >= -1f &&
                origin.x + size.width <= body.size.width + 1f &&
                origin.y + size.height <= body.size.height + 1f
            val outgoing = visual?.takeIf { fullyVisible && it.geometry.viewport == size &&
                (profile == null || selectedMedia == it.media) }
            flight = SwipeFlight(id, deckToken.session, direction, liveDrag, size,
                origin.y.roundToInt(), outgoing?.painter, origin.x.roundToInt(), outgoing?.geometry?.cropped == true)
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
        // Keep admission and the hero budget on the same measured-context snapshot.
        val measuredContextHeight = discoveryContextHeight
        ProfileShell(modifier = modifier, listState = listState, heroKey = "hero", rootHeight = rootBarHeight,
            hasRail = true, notices = notices, noticeOwner = controls, fixedNoticeSlot = true,
            heroLeadingHeight = with(LocalDensity.current) { measuredContextHeight.toDp() },
            header = { presentation, compactNotice, onNotice ->
                SwipeShellHeader(presentation, sourceContext, controls, compactNotice,
                    onNotice, { onAvailability(deckToken, it) })
            },
            rail = { SwipeDecisionControls(controls, identity?.title, undo, skip, choose) },
        ) { presentation ->
            Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { bodyCoordinates = it }) {
            if (card == null) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = contentInset, vertical = ShellMetrics.small),
                    verticalArrangement = Arrangement.spacedBy(ShellMetrics.small)) {
                    item(key = "discover-context") {
                        DiscoverModeSwitcher(DiscoverMode.Swipe, onSelectMode, Modifier)
                    }
                    if (presentation.budget.railInContent) item {
                        SwipeDecisionControls(controls, identity?.title, undo, skip, choose,
                            modifier = Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch), horizontalInset = 0.dp)
                    }
                    item {
                        val emptyState = discoveryEmptyState(state, search)
                        if (emptyState == DiscoveryEmptyState.Loading) CircularProgressIndicator()
                        else {
                            if (emptyState == DiscoveryEmptyState.Unknown) {
                                Text("Lokale Treffer unbekannt.", style = MaterialTheme.typography.titleLarge)
                            } else {
                                Text("Deck beendet", style = MaterialTheme.typography.titleLarge)
                                Text("Keine weiteren passenden Profile. Reset beginnt die Warteschlange erneut, ohne Entscheidungen zu löschen.")
                            }
                            SwipeDeckActions(controls, { onRefresh(deckToken) }, { onReset(deckToken) },
                                { onReplayLikes(deckToken, !state.replayLikes) })
                        }
                    }
                }
            } else {
                val fallback = emptyList<ProfileMediaItem>()
                ProfileFlow(state = listState, model = profile, filters = executedFiltersJson,
                    token = token, actionsBusy = !actions, notes = notes, noteActions = noteActions,
                    isProfileCurrent = isProfileCurrent, onConfirmIdentity = onConfirmIdentity,
                    modifier = Modifier.fillMaxSize(),
                    paddedDetails = true, photoRequest = photoRequest, onGallerySelection = { selectedMedia = it },
                    userScrollEnabled = !transitioning,
                    swipeRestore = state.swipeRestore, swipeRunId = state.activeRunId, swipeCapture = capture,
                    onSwipeRestoreConsumed = { onSwipeRestoreConsumed(deckToken, it) }, encounters = encounters, prices = prices,
                    fallbackIdentity = identity, fallbackMedia = fallback, preparedPanels = prepared?.panels,
                    onDiscoveryContextHeight = { discoveryContextHeight = it }, afterHeading = {
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch), horizontalInset = 0.dp)
                    }
                    item(key = "hero") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = contentInset)) {
                            if (awaitDiscoveryContext(measuredContextHeight, listState.firstVisibleItemIndex)) {
                                // Do not decode against the provisional viewport on a cold start.
                                Box(Modifier.fillMaxWidth().height(presentation.heroHeight))
                            } else ProfileMediaHero(media = profile?.media?.ifEmpty { fallback } ?: fallback,
                            height = presentation.heroHeight,
                            onSwipe = if (actions) ({ decision ->
                                choose(decision)
                            }) else null,
                            modifier = Modifier.onSizeChanged { heroSize = it }
                                .onGloballyPositioned { heroCoordinates = it }, selection = mediaSelection,
                            heldDrag = flight?.takeIf { it.card == cardIdentity }?.offset,
                            onSwipeDrag = { liveDrag = it },
                            playbackVisible = heroVisible,
                            galleryNavigationEnabled = !transitioning,
                            preparedMedia = prepared?.media.orEmpty(),
                            preparing = profile == null && readError == null,
                            videoPlaybackEnabled = started && !state.busy && !searchLoading && !transitioning,
                            presentationOwner = cardIdentity,
                            actionToken = token,
                            favorite = identity?.favorite, onFavorite = { token?.takeIf(isProfileCurrent)?.let(onFavorite) },
                            onVisual = { visual = it },
                            projectionAvailable = profile != null, actionsEnabled = actions,
                            isCurrent = { token?.let(isProfileCurrent) == true },
                            compact = presentation.budget.compactGallery,
                            touchGate = touchGate)
                        }
                    }

                }
            }
            val old = flight?.takeIf { it.card != cardIdentity }
            val oldPainter = old?.painter
            if (old != null && oldPainter != null && old.size.width > 0 && old.size.height > 0) {
                val density = LocalDensity.current
                Box(Modifier.absoluteOffset { IntOffset(old.left, old.top) }
                    .size(with(density) { old.size.width.toDp() }, with(density) { old.size.height.toDp() })
                    .graphicsLayer {
                        translationX = motion.value
                        rotationZ = (motion.value / size.width.coerceAtLeast(1f) * 12f).coerceIn(-12f, 12f)
                        alpha = 1f - (kotlin.math.abs(motion.value) / kotlin.math.abs(old.destination)).coerceIn(0f, 1f) * .2f
                    }.clip(MaterialTheme.shapes.medium).clearAndSetSemantics {}) {
                    SwipeFlightImage(oldPainter, old.cropped, Modifier.fillMaxSize())
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
    onAvailability: (AvailabilityMode) -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(horizontal = shellContentInset()), verticalAlignment = Alignment.CenterVertically) {
        ProfileShellTitle("Entdecken", sourceContext, "", presentation.budget.shortHeader, Modifier.weight(1f),
            titleStyle = MaterialTheme.typography.titleLarge)
        if (compactNotice != null) ProfileStatusButton(compactNotice, onNotice)
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
    onSkip: () -> Unit, onChoose: (Preference) -> Unit, modifier: Modifier = Modifier.fillMaxSize(),
    horizontalInset: Dp = shellContentInset()) {
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        val compact = maxHeight < ShellMetrics.decisionRail || maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f
        val scheme = MaterialTheme.colorScheme
        val inset = if (maxWidth >= ShellMetrics.touch * 4 + horizontalInset * 2) horizontalInset else 0.dp
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = inset).semantics {
            contentDescription = profileTitle?.let { "Entscheidungen für $it" } ?: "Deckentscheidungen"
        }, horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.Center,
            maxItemsInEachRow = ((maxWidth - inset * 2) / ShellMetrics.touch).toInt().coerceIn(1, 4)) {
            val actionCell = Modifier.weight(1f).widthIn(min = ShellMetrics.touch)
            DecisionAction("Rückgängig", Icons.Default.Undo, context.deckEnabled && context.canUndo, compact,
                scheme.surfaceContainer, scheme.onSurfaceVariant, false, actionCell, onUndo)
            DecisionAction("Nein", Icons.Default.Close, context.profile != null, compact,
                scheme.errorContainer, scheme.onErrorContainer, true, actionCell) { onChoose(Preference.DISLIKE) }
            DecisionAction("Später", Icons.Default.Schedule, context.deckEnabled && context.card != null, compact,
                scheme.surfaceContainer, scheme.onSurfaceVariant, false, actionCell, onSkip)
            DecisionAction("Gefällt", Icons.Default.Favorite, context.profile != null, compact,
                scheme.tertiaryContainer, scheme.onTertiaryContainer, true, actionCell) { onChoose(Preference.LIKE) }
        }
    }
}

@Composable
private fun DecisionAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, compact: Boolean, background: Color, foreground: Color,
    primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.size(ShellMetrics.touch).shellFocus()) {
            Box(Modifier.size(if (primary) ShellMetrics.touch else ShellMetrics.secondaryAction)
                .testTag("decision-surface-$label")
                .background(if (enabled) background else MaterialTheme.colorScheme.surfaceContainer,
                    MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(if (primary) ShellMetrics.icon else 20.dp),
                    tint = if (enabled) foreground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f))
            }
        }
        if (!compact) Text(label, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clearAndSetSemantics {},
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1)
    }
}
