package de.justify87.ladiesexplorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import de.justify87.ladiesexplorer.core.CaptureMode
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle


@Composable
fun LadiesApp(
    viewModel: MainViewModel,
    onSaveDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onSaveHar: () -> Unit,
    onShareHar: () -> Unit,
) {
    val home by viewModel.uiState.collectAsStateWithLifecycle()
    val search by viewModel.searchUiState.collectAsStateWithLifecycle()
    val saved by viewModel.savedSearch.state.collectAsStateWithLifecycle()
    val discovery by viewModel.discoverState.collectAsStateWithLifecycle()
    val map by viewModel.mapState.collectAsStateWithLifecycle()
    val notes by viewModel.profileNotes.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = listSaver<ShellRoute, String>(
        save = { it.encode() }, restore = { ShellRoute.decode(it) },
    )) { mutableStateOf(ShellRoute()) }
    val stateHolder = rememberSaveableStateHolder()
    val searchStateHolder = rememberSaveableStateHolder()
    var lastPresentedRun by rememberSaveable { mutableStateOf(search.response?.searchRunId) }
    LaunchedEffect(search.response?.searchRunId) {
        val run = search.response?.searchRunId
        if (run != null && run != lastPresentedRun) {
            lastPresentedRun = run
            if (!search.profileOpen && (route.root == RootDestination.Search || route.root == RootDestination.Discover)) {
                route = route.select(if (route.discovery == DiscoverMode.Grid) DiscoverMode.Grid else DiscoverMode.Swipe)
            }
        }
    }
    // Refresh visible local projections after swipe persistence, without a new source search.
    val listVisible = !search.profileOpen && (route.root == RootDestination.Saved ||
        route.root == RootDestination.Discover && route.discovery == DiscoverMode.Grid)
    val mapVisible = route.mapVisible && !search.profileOpen
    LaunchedEffect(listVisible, mapVisible) {
        viewModel.setDiscoveryProjectionVisibility(listVisible, mapVisible)
    }
    val savedVisible = !search.profileOpen && route.root == RootDestination.Saved
    LaunchedEffect(savedVisible) {
        if (!savedVisible) viewModel.cancelSavedStatus()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val actionSurface = when {
        search.profileOpen -> ProfileActionSurface.NONE
        route.root == RootDestination.Settings -> ProfileActionSurface.SETTINGS
        route.root == RootDestination.Saved -> ProfileActionSurface.SAVED
        route.root == RootDestination.Discover && route.discovery == DiscoverMode.Grid -> ProfileActionSurface.GRID
        route.root == RootDestination.Discover && route.discovery == DiscoverMode.Map -> ProfileActionSurface.MAP
        route.root == RootDestination.Search && saved.pane == SearchPane.SavedResults -> ProfileActionSurface.SAVED_RESULTS
        route.root == RootDestination.Search && saved.pane == SearchPane.Filters -> ProfileActionSurface.SEARCH
        else -> ProfileActionSurface.NONE
    }
    val currentActionSurface by rememberUpdatedState(actionSurface)
    LaunchedEffect(actionSurface, lifecycle) {
        viewModel.setProfileActionSurface(
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) actionSurface
            else ProfileActionSurface.NONE
        )
    }
    val profileTouchGate = remember { ProfileTouchGate() }
    val windowInfo = LocalWindowInfo.current
    val swipeShell = !search.profileOpen && route.root == RootDestination.Discover && route.discovery == DiscoverMode.Swipe
    val profileTouchEnabled = swipeShell || search.profileOpen
    DisposableEffect(route, search.profileOpen, search.profileSessionId, discovery.swipeSessionId) {
        onDispose { profileTouchGate.invalidate() }
    }
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (!focused) profileTouchGate.invalidate()
        }
    }
    val savedSearchRouteVisible = route.root == RootDestination.Search && !search.profileOpen
    val currentSavedSearchRouteVisible by rememberUpdatedState(savedSearchRouteVisible)
    DisposableEffect(lifecycle, viewModel) {
        fun publishVisibility() {
            val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            viewModel.savedSearch.setVisible(currentSavedSearchRouteVisible && started)
            viewModel.setProfileActionSurface(if (started) currentActionSurface else ProfileActionSurface.NONE)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) publishVisibility()
            if (event == Lifecycle.Event.ON_PAUSE) profileTouchGate.invalidate()
            if (event == Lifecycle.Event.ON_START) viewModel.setProfilePresentationVisible(true)
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.setProfilePresentationVisible(false)
                viewModel.cancelSavedStatus()
                viewModel.savedSearch.cancelCheck()
            }
        }
        lifecycle.addObserver(observer)
        publishVisibility()
        viewModel.setProfilePresentationVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            profileTouchGate.invalidate()
            viewModel.setProfilePresentationVisible(false)
            viewModel.savedSearch.setVisible(false)
            viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
        }
    }

    // Root navigation preserves pagination and scroll state.
    fun selectRoot(destination: RootDestination) {
        if (viewModel.hasProfileEditor || route.root == destination || saved.blockingNavigation) return
        profileTouchGate.invalidate()
        if (swipeShell) viewModel.setSwipeVisible(false)
        viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
        viewModel.clearPreview()
        route = route.select(destination)
        // The explicit entry read also consumes any pending overview invalidation.
        if (destination == RootDestination.Search && saved.result == null) viewModel.refreshSearchDefinitions()
        viewModel.savedSearch.setVisible(destination == RootDestination.Search && !search.profileOpen &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        if (destination == RootDestination.Settings) viewModel.refresh()
    }

    LaunchedEffect(route.root, home.httpCaptureMode, search.profileOpen) {
        if (route.root == RootDestination.Settings && !search.profileOpen && home.httpCaptureMode != CaptureMode.OFF) {
            while (true) {
                delay(2_000)
                viewModel.refreshCaptureStatus()
            }
        }
    }

    val pane = saved.pane
    val renderedPaneAction = SavedSearchPaneAction(saved.actionGeneration)
    val draftAction = SearchDraftEditAction(discovery.actionGeneration, saved.actionGeneration)
    val renderedEditorAction = saved.editor?.let { SavedEditorAction(it, saved.actionGeneration) }
    val searchCancelAction = SearchCancelAction(ProfileActionSurface.SEARCH, discovery.actionGeneration, search.searchAttemptId)
    LaunchedEffect(savedSearchRouteVisible, lifecycle) {
        viewModel.savedSearch.setVisible(savedSearchRouteVisible &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val savedResultsOpen = route.root == RootDestination.Search && pane == SearchPane.SavedResults
    val savedEditorOpen = route.root == RootDestination.Search && pane == SearchPane.SavedEditor
    val savedOverviewOpen = route.root == RootDestination.Search && pane == SearchPane.SavedList
    var previousEditorSession by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(saved.editor?.session) {
        val old = previousEditorSession
        if (old != null && old != saved.editor?.session) searchStateHolder.removeState("editor/$old")
        previousEditorSession = saved.editor?.session
    }
    val renderedProfileOpen = search.profileOpen
    val renderedProfileSession = search.profileSessionId
    val renderedRetryAction = search.detailRetryAction()
    fun back() {
        viewModel.encounters.edit?.let { viewModel.encounters.requestDismiss(it); return }
        viewModel.prices.edit?.let { viewModel.prices.requestDismiss(it); return }
        profileTouchGate.invalidate()
        // A delayed detail Back must never fall through to the root route after closing.
        if (renderedProfileOpen) {
            viewModel.closeProfile(renderedProfileSession)
            return
        }
        if (!search.profileOpen && (savedEditorOpen || savedOverviewOpen || savedResultsOpen)) {
            viewModel.savedSearch.back()
            return
        }
        when (route.backAction(search.profileOpen, savedResultsOpen)) {
            BackAction.CloseProfile -> viewModel.closeProfile(renderedProfileSession)
            BackAction.CloseSavedResults -> viewModel.savedSearch.back()
            BackAction.Navigate -> {
                viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
                viewModel.savedSearch.setVisible(false)
                route = route.back()
            }
            BackAction.System -> Unit
        }
    }
    // On the default swipe route, Back cancels an active touch or delegates to Android.
    BackHandler(enabled = viewModel.hasProfileEditor || savedEditorOpen || savedOverviewOpen ||
        route.backAction(search.profileOpen, savedResultsOpen) != BackAction.System ||
        (profileTouchEnabled && profileTouchGate.active)) { back() }

    EncounterEditorDialog(viewModel.encounters)
    PriceEditorDialog(viewModel.prices)

    Scaffold(
        modifier = if (profileTouchEnabled) Modifier.admitProfileTouches(profileTouchGate) {
            windowInfo.isWindowFocused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } else Modifier,
        topBar = {
            if (!search.profileOpen && !swipeShell) {
                val nested = savedResultsOpen || savedEditorOpen || savedOverviewOpen
                AppHeader(
                    title = when {
                        savedResultsOpen -> saved.searches.firstOrNull { it.searchId == saved.result?.id }?.name
                            ?: "Gespeicherte Ergebnisse"
                        savedEditorOpen -> "Suche bearbeiten"
                        savedOverviewOpen -> "Gespeicherte Suchen"
                        else -> "Ladies"
                    },
                    subtitle = route.root.title.takeIf { !nested && route.root != RootDestination.Discover },
                    onBack = if (nested) ({ back() }) else null,
                    backEnabled = saved.operation != SavedOperation.Write,
                ) {
                    if (route.root != RootDestination.Search) {
                        ShellIconButton(onClick = { selectRoot(RootDestination.Search) },
                            enabled = !saved.blockingNavigation && !viewModel.hasProfileEditor) {
                            Icon(Icons.Default.Search, contentDescription = "Suche öffnen")
                        }
                    } else if (route.root == RootDestination.Search && pane == SearchPane.Filters) {
                        ShellIconButton(onClick = { viewModel.savedSearch.open(renderedPaneAction) },
                            enabled = !search.loading && !saved.blockingNavigation && !home.loading && !discovery.busy) {
                            BadgedBox(badge = { if (saved.searches.any { it.unreadNewResults }) Badge() }) {
                                Icon(Icons.Default.Bookmarks, contentDescription = if (saved.searches.any { it.unreadNewResults })
                                    "Gespeicherte Suchen, neue Ergebnisse" else "Gespeicherte Suchen")
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!search.profileOpen) RootNavigation(
                active = route.root,
                navigationEnabled = !saved.blockingNavigation && !viewModel.hasProfileEditor,
                unreadSearch = saved.searches.any { it.unreadNewResults },
                onSelect = ::selectRoot,
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .imePadding()
        if (search.profileOpen) {
            key(search.profileSessionId) {
                ProfileDetailScreen(
                    state = search,
                    notes = notes,
                    noteActions = viewModel.noteActions,
                    encounters = viewModel.encounters, prices = viewModel.prices,
                    onFavorite = viewModel::toggleProfileFavorite,
                    onPreference = viewModel::chooseProfilePreference,
                    isProfileCurrent = viewModel::isProfileActionCurrent,
                    onConfirmIdentity = viewModel::confirmIdentity,
                    onCancelArchive = viewModel::cancelMediaArchive,
                    onRetryProfile = { renderedRetryAction?.let(viewModel::retryProfileProjection) },
                    onBack = { viewModel.closeProfile(renderedProfileSession) },
                    actionsBusy = discovery.busy,
                    touchGate = profileTouchGate,
                    modifier = modifier,
                )
            }
        } else {
            stateHolder.SaveableStateProvider(route.stateKey) {
                when (route.root) {
                    RootDestination.Discover -> Column(modifier = modifier) {
                        if (route.discovery == DiscoverMode.Swipe) {
                            val cancelAction = SearchCancelAction(ProfileActionSurface.NONE,
                                discovery.actionGeneration, search.searchAttemptId, discovery.swipeSessionId)
                            DiscoverScreen(
                                state = discovery,
                                notes = notes,
                                noteActions = viewModel.noteActions,
                                encounters = viewModel.encounters, prices = viewModel.prices,
                                search = search,
                                rootBarHeight = (padding.calculateBottomPadding() -
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).coerceAtLeast(0.dp),
                                onOpenSearch = {
                                    if (route.root == RootDestination.Discover && route.discovery == DiscoverMode.Swipe)
                                        selectRoot(RootDestination.Search)
                                },
                                onSelectMode = { mode ->
                                    if (!viewModel.hasProfileEditor && route.root == RootDestination.Discover && route.discovery == DiscoverMode.Swipe &&
                                        mode != DiscoverMode.Swipe) {
                                        profileTouchGate.invalidate()
                                        viewModel.setSwipeVisible(false)
                                        viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
                                        route = route.select(mode)
                                    }
                                },
                                availabilityEnabled = !search.loading && !home.loading && !discovery.busy && !saved.busy,
                                onAvailability = viewModel::runSwipeAvailability,
                                onCancelSearch = { viewModel.cancelActiveSearch(cancelAction) },
                                onRefresh = viewModel::refreshSwipe,
                                onReset = viewModel::resetSwipe,
                                onUndo = viewModel::undoSwipe,
                                onSwipeRestoreConsumed = viewModel::consumeSwipePresentation,
                                onReplayLikes = viewModel::setReplayLikes,
                                onSkip = viewModel::skipCard,
                                onChoose = viewModel::chooseSwipePreference,
                                onSwipeVisibility = viewModel::setSwipeVisible,
                                onRenderedSwipe = viewModel::publishRenderedSwipe,
                                onProjectionMismatch = viewModel::reconcileSwipeProjection,
                                onConfirmIdentity = viewModel::confirmIdentity,
                                onSnapshotChanged = viewModel::markSwipeSnapshotChanged,
                                onFavorite = viewModel::toggleProfileFavorite,
                                isProfileCurrent = viewModel::isProfileActionCurrent,
                                touchGate = profileTouchGate,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (route.discovery == DiscoverMode.Grid) {
                            key(search.response?.searchRunId) {
                                val cancelAction = SearchCancelAction(ProfileActionSurface.GRID,
                                    discovery.actionGeneration, search.searchAttemptId)
                                DiscoveryGridScreen(
                                    discovery = discovery,
                                    search = search,
                                    onRefresh = viewModel::refreshRenderedGrid,
                                    onLoadMore = viewModel::loadMoreDiscovery,
                                    onLike = viewModel::toggleGridLike,
                                    onProfile = viewModel::openGridProfile,
                                    chrome = {
                                        val availabilityAction = search.gridAvailabilityAction(discovery.actionGeneration)
                                        val run = search.response
                                        DiscoveryChrome(
                                            summary = run?.let { "${it.city} · ${it.requestedRadiusKm} km" } ?: "Lokaler Bestand · neutral",
                                            availability = search.headerAvailability(),
                                            availabilityEnabled = gridAvailabilityMatches(discovery, search, availabilityAction) && !home.loading && !saved.busy,
                                            owner = availabilityAction,
                                            active = DiscoverMode.Grid,
                                            onMode = { mode ->
                                                viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
                                                route = route.select(mode)
                                            },
                                            onAvailability = { viewModel.runGridAvailability(availabilityAction, it) },
                                            modifier = Modifier,
                                        )
                                    },
                                    onCancelSearch = { viewModel.cancelActiveSearch(cancelAction) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            MapScreen(
                                state = map,
                                onRefresh = viewModel::refreshRenderedMap,
                                onResolve = viewModel::resolveMapLocations,
                                onCancel = viewModel::cancelRenderedMapResolution,
                                onFavoritesOnly = viewModel::setMapFavoritesOnly,
                                generation = discovery.actionGeneration,
                                onProfile = viewModel::openMapProfile,
                                onMode = { mode ->
                                    viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
                                    route = route.select(mode)
                                },
                                profileEnabled = !discovery.busy && !search.loading,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    RootDestination.Search -> searchStateHolder.SaveableStateProvider(
                        when (pane) {
                            SearchPane.SavedResults -> "results/${saved.result?.id}"
                            SearchPane.SavedEditor -> "editor/${saved.editor?.session}"
                            else -> pane.name
                        }
                    ) { when (pane) {
                    SearchPane.SavedList -> SavedSearchOverview(
                        state = saved, enabled = !search.loading && !home.loading && !discovery.busy,
                        controller = viewModel.savedSearch, modifier = modifier)
                    SearchPane.SavedResults -> key(saved.result?.id) {
                        SavedSearchResultsScreen(saved, !discovery.busy && !search.loading && !home.loading,
                            generation = discovery.actionGeneration,
                            onMore = viewModel.savedSearch::more, onRetry = viewModel.savedSearch::retryResults,
                            onAcknowledge = viewModel.savedSearch::acknowledge,
                            onOpen = viewModel::openSavedSearchProfile, modifier = modifier)
                    }
                    else -> SearchScreen(
                        search = search, saved = saved,
                        canStartSearch = !home.loading && !discovery.busy,
                        canEditDraft = searchDraftEditMatches(discovery, search, saved, draftAction),
                        onDraftChange = if (pane == SearchPane.SavedEditor) {
                            { change ->
                                renderedEditorAction?.let { viewModel.savedSearch.editDraft(it, change) }
                                Unit
                            }
                        } else { change -> viewModel.editSearchDraft(draftAction, change) },
                        onReset = { viewModel.resetSearchDraft(draftAction, SearchDraft.from(search)) },
                        onPreviewStart = viewModel::beginPreview,
                        onPreviewStop = viewModel::endPreview,
                        onSearch = viewModel::submitSearch,
                        onCancelSearch = { viewModel.cancelActiveSearch(searchCancelAction) },
                        onSave = viewModel.savedSearch::saveEditor,
                        onName = { name -> renderedEditorAction?.let { viewModel.savedSearch.editName(it, name) } },
                        onCancelEdit = { renderedEditorAction?.let { viewModel.savedSearch.cancelEditor(it) } },
                        generation = discovery.actionGeneration,
                        onProfile = viewModel::openSearchResultProfile,
                        modifier = modifier,
                    )
                    } }
                    RootDestination.Saved -> SavedPeopleScreen(
                        state = discovery,
                        controlsEnabled = savedPeopleControlMatches(discovery, search, discovery.savedPeopleControlAction()),
                        onKind = viewModel::selectSavedKind,
                        onSort = viewModel::selectSavedSort,
                        onRefresh = viewModel::refreshRenderedSavedPeople,
                        onProfile = viewModel::openSavedPersonProfile,
                        onFavorite = viewModel::toggleFavorite,
                        onPreference = viewModel::choosePreference,
                        onLoadMore = viewModel::loadMoreSavedPeople,
                        onCheckAll = viewModel::checkAllSavedStatus,
                        onCheckSingle = viewModel::checkSingleSavedStatus,
                        onCancelCheck = viewModel::cancelRenderedSavedStatus,
                        modifier = modifier,
                    )
                    RootDestination.Settings -> {
                        val replayAction = discovery.settingsReplayAction()
                        SettingsScreen(
                            state=home,
                            replayLikes = replayAction.expected,
                            replayEnabled = !home.loading && settingsReplayMatches(discovery, search, replayAction),
                            onReplayLikes = { viewModel.setSettingsReplayLikes(replayAction, it) },
                            onRefresh=viewModel::refresh,
                            onSaveDiagnostics=onSaveDiagnostics,
                            onShareDiagnostics=onShareDiagnostics,
                            onDebugLogging=viewModel::setDebugLoggingEnabled,
                            onCaptureMode=viewModel::setHttpCaptureMode,
                            onClearHar=viewModel::clearHttpCapture,
                            onSaveHar=onSaveHar,
                            onShareHar=onShareHar,
                            modifier=modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    state: HomeUiState,
    replayLikes: Boolean,
    replayEnabled: Boolean,
    onReplayLikes: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSaveDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onDebugLogging: (Boolean) -> Unit,
    onCaptureMode: (CaptureMode) -> Unit,
    onClearHar: () -> Unit,
    onSaveHar: () -> Unit,
    onShareHar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenGroup("Lokale Datenbasis") {
            Text("Personen, Inserate und Historie liegen in der Rust-eigenen SQLite-Datenbank.",
                style = MaterialTheme.typography.bodySmall)
            if (state.loading) CircularProgressIndicator()
            state.error?.let { Text("Fehler: $it", color = MaterialTheme.colorScheme.error) }
            val schema = state.schemaVersion
            if (schema == null) Text("Datenbankstatus noch nicht bekannt.")
            else {
                Text("Datenbankschema: v$schema", style = MaterialTheme.typography.titleSmall)
                Text("Personen: ${state.personCount} · Quellenprofile: ${state.sourceProfileCount} · Inserate: ${state.listingCount}")
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.loading,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Datenbankstatus aktualisieren") }
        }
        ScreenGroup("Entdecken") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Likes erneut zeigen")
                    Text("Bereits gelikte Profile im Swipe-Deck", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = replayLikes, onCheckedChange = onReplayLikes, enabled = replayEnabled,
                    modifier = Modifier.semantics { contentDescription = "Likes erneut zeigen" })
            }
        }
        ScreenGroup("Diagnose") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Debug-Details")
                    Text(if (state.debugLoggingEnabled) "DEBUG" else "INFO", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.debugLoggingEnabled, onCheckedChange = onDebugLogging,
                    modifier = Modifier.semantics { contentDescription = "Debug-Details" })
            }
            Text("Debug-Protokolle können persönliche Inhalte enthalten. Teile sie nur bewusst.",
                style = MaterialTheme.typography.bodySmall)
            state.diagnosticLastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(if (state.diagnosticAvailable) "Log-Datei: ${state.diagnosticSizeBytes} Bytes"
                else "Noch keine Log-Datei vorhanden.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveDiagnostics, enabled = state.diagnosticAvailable,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Debug-Log speichern") }
                TextButton(onClick = onShareDiagnostics, enabled = state.diagnosticAvailable,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Debug-Log teilen") }
            }
        }
        ScreenGroup("HTTP-Aufzeichnung") {
            Text("Standardmäßig aus – auch nach jedem Neustart. HTML/JSON können personenbezogene Daten enthalten. HAR-Dateien nur bewusst exportieren und teilen.",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CaptureMode.OFF to "Aus", CaptureMode.METADATA to "Metadaten", CaptureMode.TEXT to "Mit Textantworten")
                    .forEach { (mode, label) ->
                        ScreenChoice(selected = state.httpCaptureMode == mode, enabled = true,
                            onClick = { onCaptureMode(mode) }, label = { Text(label) })
                    }
            }
            Text("Aufzeichnung: ${state.httpCaptureEntries} Requests · ${state.httpCaptureBytes} Bytes / 25 MiB",
                style = MaterialTheme.typography.bodySmall)
            if (state.httpCaptureStopped) Text("Sitzungslimit erreicht. Export weiter möglich; zum Neustart Aufzeichnung löschen.",
                color = MaterialTheme.colorScheme.error)
            state.httpCaptureError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveHar, enabled = state.httpCaptureEntries > 0u,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("HAR speichern") }
                TextButton(onClick = onShareHar, enabled = state.httpCaptureEntries > 0u,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("HAR teilen") }
            }
            TextButton(onClick = onClearHar, enabled = state.httpCaptureEntries > 0u || state.httpCaptureStopped,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Aufzeichnung löschen")
            }
        }
    }
}
