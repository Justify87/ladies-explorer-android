package de.justify87.ladiesexplorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle


@OptIn(ExperimentalMaterial3Api::class)
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
            publishVisibility()
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
        if (route.root == destination || saved.blockingNavigation) return
        profileTouchGate.invalidate()
        if (swipeShell) viewModel.setSwipeVisible(false)
        viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
        route = route.select(destination)
        viewModel.savedSearch.setVisible(destination == RootDestination.Search && !search.profileOpen &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        when (destination) {
            RootDestination.Search -> if (saved.result == null) viewModel.refreshSearchDefinitions() else Unit
            RootDestination.Settings -> viewModel.refresh()
            else -> Unit
        }
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
    val renderedEditorAction = saved.editor?.let { SavedEditorAction(it, saved.actionGeneration) }
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
    fun back() {
        profileTouchGate.invalidate()
        if (!search.profileOpen && (savedEditorOpen || savedOverviewOpen || savedResultsOpen)) {
            viewModel.savedSearch.back()
            return
        }
        when (route.backAction(search.profileOpen, savedResultsOpen)) {
            BackAction.CloseProfile -> viewModel.closeProfile()
            BackAction.CloseSavedResults -> viewModel.savedSearch.back()
            BackAction.Navigate -> {
                viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
                route = route.back()
            }
            BackAction.System -> Unit
        }
    }
    // On the default swipe route, Back cancels an active touch or delegates to Android.
    BackHandler(enabled = savedEditorOpen || savedOverviewOpen ||
        route.backAction(search.profileOpen, savedResultsOpen) != BackAction.System ||
        (profileTouchEnabled && profileTouchGate.active)) { back() }

    Scaffold(
        modifier = if (profileTouchEnabled) Modifier.admitProfileTouches(profileTouchGate) {
            windowInfo.isWindowFocused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } else Modifier,
        topBar = {
            if (!search.profileOpen && !swipeShell) TopAppBar(
                title = {
                    if (savedResultsOpen || savedEditorOpen || savedOverviewOpen) {
                        Text(when {
                            savedResultsOpen -> saved.searches.firstOrNull { it.searchId == saved.result?.id }?.name ?: "Gespeicherte Ergebnisse"
                            savedEditorOpen -> "Suche bearbeiten"
                            else -> "Gespeicherte Suchen"
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(
                            if (route.root == RootDestination.Discover) "Ladies Explorer" else route.root.title,
                            color = if (route.root == RootDestination.Discover) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (savedResultsOpen || savedEditorOpen || savedOverviewOpen) {
                        IconButton(onClick = { back() }, enabled = saved.operation != SavedOperation.Write) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = {
                    if (route.root == RootDestination.Discover) {
                        IconButton(onClick = { selectRoot(RootDestination.Search) }) {
                            Icon(Icons.Default.Search, contentDescription = "Suche öffnen")
                        }
                    } else if (route.root == RootDestination.Search && pane == SearchPane.Filters) {
                        IconButton(onClick = { viewModel.savedSearch.open(renderedPaneAction) }, enabled = !search.loading && !saved.blockingNavigation && !home.loading && !discovery.busy) {
                            BadgedBox(badge = { if (saved.searches.any { it.unreadNewResults }) Badge() }) {
                                Icon(Icons.Default.Bookmarks, contentDescription = if (saved.searches.any { it.unreadNewResults })
                                    "Gespeicherte Suchen, neue Ergebnisse" else "Gespeicherte Suchen")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (!search.profileOpen) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    RootDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = route.root == destination,
                            onClick = { selectRoot(destination) },
                            enabled = !saved.blockingNavigation || route.root == destination,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            icon = {
                                when (destination) {
                                    RootDestination.Discover -> Icon(Icons.Default.FavoriteBorder, contentDescription = null)
                                    RootDestination.Search -> BadgedBox(badge = {
                                        if (saved.searches.any { it.unreadNewResults }) {
                                            Badge() // unread indicator, not a count
                                        }
                                    }) { Icon(Icons.Default.Search, contentDescription =
                                        if (saved.searches.any { it.unreadNewResults }) "Neue gespeicherte Suchergebnisse" else null) }
                                    RootDestination.Saved -> Icon(Icons.Default.Favorite, contentDescription = null)
                                    RootDestination.Settings -> Icon(Icons.Default.Settings, contentDescription = null)
                                }
                            },
                            label = { Text(destination.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .then(if (swipeShell || search.profileOpen) Modifier.imePadding() else Modifier)
        if (search.profileOpen) {
            key(search.profileSessionId) {
                ProfileDetailScreen(
                    state = search,
                    notes = notes,
                    noteActions = viewModel.noteActions,
                    onFavorite = viewModel::toggleProfileFavorite,
                    onPreference = viewModel::chooseProfilePreference,
                    isProfileCurrent = viewModel::isProfileActionCurrent,
                    onConfirmIdentity = viewModel::confirmIdentity,
                    onCancelArchive = viewModel::cancelMediaArchive,
                    onRetryProfile = viewModel::retryProfileProjection,
                    onBack = viewModel::closeProfile,
                    actionsBusy = discovery.busy,
                    touchGate = profileTouchGate,
                    modifier = modifier,
                )
            }
        } else {
            stateHolder.SaveableStateProvider(route.stateKey) {
                when (route.root) {
                    RootDestination.Discover -> Column(modifier = modifier) {
                        if (route.discovery == DiscoverMode.Grid) {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                val availabilityAction = search.gridAvailabilityAction(discovery.actionGeneration)
                                AvailabilitySelector(search.headerAvailability(),
                                    gridAvailabilityMatches(discovery, search, availabilityAction) && !home.loading && !saved.busy) {
                                    viewModel.runGridAvailability(availabilityAction, it)
                                }
                            }
                        }
                        if (route.discovery == DiscoverMode.Grid) {
                            SearchRunStatus(search, viewModel::cancelActiveSearch)
                        }
                        if (route.discovery != DiscoverMode.Swipe) DiscoverModeSwitcher(route.discovery, onSelect = {
                            viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
                            route = route.select(it)
                        })
                        if (route.discovery == DiscoverMode.Swipe) {
                            DiscoverScreen(
                                state = discovery,
                                notes = notes,
                                noteActions = viewModel.noteActions,
                                search = search,
                                rootBarHeight = (padding.calculateBottomPadding() -
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).coerceAtLeast(0.dp),
                                onOpenSearch = {
                                    if (route.root == RootDestination.Discover && route.discovery == DiscoverMode.Swipe)
                                        selectRoot(RootDestination.Search)
                                },
                                onSelectMode = { mode ->
                                    if (route.root == RootDestination.Discover && route.discovery == DiscoverMode.Swipe &&
                                        mode != DiscoverMode.Swipe) {
                                        profileTouchGate.invalidate()
                                        viewModel.setSwipeVisible(false)
                                        viewModel.setProfileActionSurface(ProfileActionSurface.NONE)
                                        route = route.select(mode)
                                    }
                                },
                                availabilityEnabled = !search.loading && !home.loading && !discovery.busy && !saved.busy,
                                onAvailability = viewModel::runSwipeAvailability,
                                onCancelSearch = viewModel::cancelActiveSearch,
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
                                DiscoveryGridScreen(
                                    discovery = discovery,
                                    search = search,
                                    onRefresh = viewModel::refreshDiscovery,
                                    onLoadMore = viewModel::loadMoreDiscovery,
                                    onLike = viewModel::toggleGridLike,
                                    onProfile = viewModel::openGridProfile,
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
                        onDraftChange = if (pane == SearchPane.SavedEditor) {
                            { change ->
                                renderedEditorAction?.let { viewModel.savedSearch.editDraft(it, change) }
                                Unit
                            }
                        } else viewModel::editSearchDraft,
                        onReset = viewModel::resetSearchDraft,
                        onPreviewTarget = viewModel::setPreviewTarget,
                        onSearch = viewModel::submitSearch,
                        onCancelSearch = viewModel::cancelActiveSearch,
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
                        onKind = viewModel::selectSavedKind,
                        onSort = viewModel::selectSavedSort,
                        onRefresh = viewModel::refreshDiscovery,
                        onProfile = viewModel::openSavedPersonProfile,
                        onFavorite = viewModel::toggleFavorite,
                        onPreference = viewModel::choosePreference,
                        onLoadMore = viewModel::loadMoreSavedPeople,
                        onCheckAll = viewModel::checkAllSavedStatus,
                        onCheckSingle = viewModel::checkSingleSavedStatus,
                        onCancelCheck = viewModel::cancelSavedStatus,
                        modifier = modifier,
                    )
                    RootDestination.Settings -> SettingsScreen(
                        state=home,
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

@Composable
private fun SearchRunStatus(state: SearchUiState, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val active = state.response
        if (active != null) {
            Text("${active.city} · ${active.requestedRadiusKm} km · Quelle: ${active.serverCount?.takeIf { active.radiusVerified } ?: "unbekannt"}",
                style = MaterialTheme.typography.labelMedium)
            if (active.partial || !active.radiusVerified) {
                Text("Teilergebnis / Radius nicht bestätigt", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Suche läuft …", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel, enabled = !state.searchCancelling) { Text(if (state.searchCancelling) "Wird beendet …" else "Abbrechen") }
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
    }
}

@Composable
private fun DiscoverModeSwitcher(active: DiscoverMode, onSelect: (DiscoverMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiscoverMode.entries.forEach { mode ->
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = mode == active,
                onClick = { onSelect(mode) },
                label = { Text(mode.title) },
                leadingIcon = { Icon(when (mode) {
                    DiscoverMode.Map -> Icons.Default.Map
                    DiscoverMode.Grid -> Icons.Default.GridView
                    DiscoverMode.Swipe -> Icons.Default.FavoriteBorder
                },
                    contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: HomeUiState,
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
        modifier=modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp),
    ) {
        Text("Lokale Datenbasis",style=MaterialTheme.typography.headlineMedium)
        Text("Personen, Inserate und Historie liegen in der Rust-eigenen SQLite-Datenbank.")
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text("Fehler: $it",color=MaterialTheme.colorScheme.error) }
        state.schemaVersion?.let {
            Text("Datenbankschema: v$it")
            Text("Personen: ${state.personCount} · Quellenprofile: ${state.sourceProfileCount} · Inserate: ${state.listingCount}")
        }
        Button(onClick=onRefresh,enabled=!state.loading,modifier=Modifier.fillMaxWidth()) {
            Text("Datenbankstatus aktualisieren")
        }
        Text("Diagnose",style=MaterialTheme.typography.titleLarge)
        androidx.compose.foundation.layout.Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("Debug-Details", modifier=Modifier.weight(1f))
            Switch(checked=state.debugLoggingEnabled,onCheckedChange=onDebugLogging)
        }
        Text("Debug-Protokolle können persönliche Inhalte enthalten. Teile sie nur bewusst.",style=MaterialTheme.typography.bodySmall)
        state.diagnosticLastError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Text(
            if (state.diagnosticAvailable) "Log-Datei: ${state.diagnosticSizeBytes} Bytes"
            else "Noch keine Log-Datei vorhanden.",
            style=MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick=onSaveDiagnostics,enabled=state.diagnosticAvailable,
            modifier=Modifier.fillMaxWidth(),
        ) { Text("Debug-Log als Datei speichern") }
        Button(
            onClick=onShareDiagnostics,enabled=state.diagnosticAvailable,
            modifier=Modifier.fillMaxWidth(),
        ) { Text("Debug-Log teilen") }
        Text("HTTP-Aufzeichnung",style=MaterialTheme.typography.titleMedium)
        Text(
            "Standardmäßig aus – auch nach jedem Neustart. HTML/JSON können personenbezogene Daten enthalten. HAR-Dateien nur bewusst exportieren und teilen.",
            style=MaterialTheme.typography.bodySmall,
        )
        listOf(
            CaptureMode.OFF to "Aus",
            CaptureMode.METADATA to "Metadaten",
            CaptureMode.TEXT to "Mit Textantworten",
        ).forEach { (mode, label) ->
            androidx.compose.foundation.layout.Row(
                verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
                modifier=Modifier.fillMaxWidth(),
            ) {
                RadioButton(selected=state.httpCaptureMode==mode,onClick={onCaptureMode(mode)})
                Text(label)
            }
        }
        Text(
            "Aufzeichnung: ${state.httpCaptureEntries} Requests · ${state.httpCaptureBytes} Bytes / 25 MiB",
            style=MaterialTheme.typography.bodySmall,
        )
        if (state.httpCaptureStopped) Text(
            "Sitzungslimit erreicht. Export weiter möglich; zum Neustart Aufzeichnung löschen.",
            color=MaterialTheme.colorScheme.error,
        )
        state.httpCaptureError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Button(onClick=onSaveHar,enabled=state.httpCaptureEntries>0u,modifier=Modifier.fillMaxWidth()) {
            Text("HAR speichern")
        }
        Button(onClick=onShareHar,enabled=state.httpCaptureEntries>0u,modifier=Modifier.fillMaxWidth()) {
            Text("HAR teilen")
        }
        Button(onClick=onClearHar,enabled=state.httpCaptureEntries>0u || state.httpCaptureStopped,
            modifier=Modifier.fillMaxWidth()) {
            Text("Aufzeichnung löschen")
        }
    }
}
