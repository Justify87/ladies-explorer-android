package de.justify87.ladiesexplorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LadiesApp(
    viewModel: MainViewModel,
    onPickLegacyBundle: () -> Unit,
    onSaveDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onSaveHar: () -> Unit,
    onShareHar: () -> Unit,
) {
    val home by viewModel.uiState.collectAsStateWithLifecycle()
    val search by viewModel.searchUiState.collectAsStateWithLifecycle()
    val discovery by viewModel.discoverState.collectAsStateWithLifecycle()
    val map by viewModel.mapState.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = listSaver<ShellRoute, String>(
        save = { it.encode() }, restore = { ShellRoute.decode(it) },
    )) { mutableStateOf(ShellRoute()) }
    val stateHolder = rememberSaveableStateHolder()
    var lastPresentedRun by rememberSaveable { mutableStateOf(search.response?.searchRunId) }
    LaunchedEffect(search.response?.searchRunId) {
        val run = search.response?.searchRunId
        if (run != null && run != lastPresentedRun) {
            lastPresentedRun = run
            if (!search.profileOpen && (route.root == RootDestination.Search || route.root == RootDestination.Discover)) {
                route = route.select(DiscoverMode.Swipe)
            }
        }
    }
    val mapVisible = route.mapVisible && !search.profileOpen
    LaunchedEffect(mapVisible) {
        if (mapVisible) viewModel.refreshMap()
    }

    // Root changes must not reload already paginated lists or discard their scroll state.
    // Explicit refresh and data mutations remain owned by the ViewModel.
    fun selectRoot(destination: RootDestination) {
        if (route.root == destination) return
        route = route.select(destination)
        when (destination) {
            RootDestination.Search -> viewModel.refreshSearchDefinitions()
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

    val savedResultsOpen = route.root == RootDestination.Search && search.showingSavedSearchId != null
    fun back() {
        when (route.backAction(search.profileOpen, savedResultsOpen)) {
            BackAction.CloseProfile -> viewModel.closeProfile()
            BackAction.CloseSavedResults -> viewModel.closeSavedResults()
            BackAction.Navigate -> route = route.back()
            BackAction.System -> Unit
        }
    }
    BackHandler(enabled = route.backAction(search.profileOpen, savedResultsOpen) != BackAction.System) { back() }

    Scaffold(
        topBar = {
            if (!search.profileOpen) TopAppBar(
                title = {
                    if (savedResultsOpen) {
                        Text("Gespeicherte Ergebnisse", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    if (savedResultsOpen) {
                        IconButton(onClick = { back() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = {
                    if (route.root == RootDestination.Discover) {
                        IconButton(onClick = { selectRoot(RootDestination.Search) }) {
                            Icon(Icons.Default.Search, contentDescription = "Suche öffnen")
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
                                        if (search.savedSearches.any { it.unreadNewResults }) {
                                            Badge() // unread indicator, not a count
                                        }
                                    }) { Icon(Icons.Default.Search, contentDescription =
                                        if (search.savedSearches.any { it.unreadNewResults }) "Neue gespeicherte Suchergebnisse" else null) }
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
        if (search.profileOpen) {
            key(search.profileSessionId) {
                ProfileDetailScreen(
                    state = search,
                    onFavorite = viewModel::toggleFavorite,
                    onPreference = viewModel::choosePreference,
                    onConfirmIdentity = viewModel::confirmIdentity,
                    onCancelArchive = viewModel::cancelMediaArchive,
                    onBack = viewModel::closeProfile,
                    actionsBusy = discovery.busy,
                    modifier = modifier,
                )
            }
        } else {
            stateHolder.SaveableStateProvider(route.stateKey) {
                when (route.root) {
                    RootDestination.Discover -> Column(modifier = modifier) {
                        if (route.discovery == DiscoverMode.Swipe) {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                AvailabilitySelector(search.headerAvailability(), !search.loading && !home.loading && !discovery.busy) {
                                    viewModel.runAvailability(it)
                                }
                            }
                        }
                        if (route.discovery == DiscoverMode.Swipe) {
                            SearchRunStatus(search, viewModel::cancelActiveSearch)
                        }
                        DiscoverModeSwitcher(route.discovery, onSelect = { route = route.select(it) })
                        if (route.discovery == DiscoverMode.Swipe) {
                            DiscoverScreen(
                                state = discovery,
                                searchLoading = search.loading,
                                onRefresh = viewModel::refreshDiscovery,
                                onSkip = viewModel::skipCard,
                                onChoose = viewModel::choosePreference,
                                onFavorite = viewModel::toggleFavorite,
                                onProfile = viewModel::loadProfile,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            MapScreen(
                                state = map,
                                onRefresh = viewModel::refreshMap,
                                onResolve = viewModel::resolveMapLocations,
                                onCancel = viewModel::cancelMapResolution,
                                onFavoritesOnly = viewModel::setMapFavoritesOnly,
                                onProfile = viewModel::loadProfile,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    RootDestination.Search -> SearchScreen(
                        state=search,
                        canStartSearch = !home.loading && !discovery.busy,
                        onCity=viewModel::setSearchCity,
                        onRadius=viewModel::setSearchRadius,
                        onCategory=viewModel::setCategory,
                        onAge=viewModel::setAgeRange,
                        onHeight=viewModel::setHeightRange,
                        onPages=viewModel::setMaxPages,
                        onToggleFilter=viewModel::toggleFilter,
                        onAvailability=viewModel::setAvailability,
                        onLocalVisibility=viewModel::setLocalVisibility,
                        onReset=viewModel::resetSearchDraft,
                        onPreviewVisible=viewModel::setPreviewVisible,
                        onSearch={viewModel.startSearch()},
                        onCancelSearch=viewModel::cancelActiveSearch,
                        onSave=viewModel::saveCurrentSearch,
                        onNewPreset=viewModel::createNewSavedSearch,
                        onLoadSaved=viewModel::loadSavedSearch,
                        onRunSaved={viewModel.startSearch(it)},
                        onDeleteSaved=viewModel::deleteSavedSearch,
                        onAcknowledge=viewModel::acknowledgeSavedSearch,
                        onShowSavedResults=viewModel::showSavedResults,
                        onLoadMoreSavedResults=viewModel::loadMoreSavedResults,
                        onProfile=viewModel::loadProfile,
                        modifier=modifier,
                    )
                    RootDestination.Saved -> FavoritesScreen(
                        state=discovery,
                        onRefresh=viewModel::refreshDiscovery,
                        onProfile=viewModel::loadProfile,
                        onFavorite=viewModel::toggleFavorite,
                        onLoadMore=viewModel::loadMoreFavorites,
                        modifier=modifier,
                    )
                    RootDestination.Settings -> SettingsScreen(
                        state=home,
                        onRefresh=viewModel::refresh,
                        onPickLegacyBundle=onPickLegacyBundle,
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
                TextButton(onClick = onCancel) { Text("Abbrechen") }
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
                leadingIcon = { Icon(if (mode == DiscoverMode.Map) Icons.Default.Map else Icons.Default.FavoriteBorder,
                    contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onPickLegacyBundle: () -> Unit,
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
        Button(onClick=onPickLegacyBundle,enabled=!state.loading,modifier=Modifier.fillMaxWidth()) {
            Text("Web-App-Datenarchiv importieren")
        }
        Text(
            "Das ZIP muss mit tools/export-legacy.sh erstellt werden. Der Import in eine bereits befüllte Datenbank ist nicht erlaubt.",
            style=MaterialTheme.typography.bodySmall,
        )
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
        state.lastImport?.let { report ->
            Text("Import abgeschlossen",style=MaterialTheme.typography.titleMedium)
            Text(
                "Personen: ${report.persons} · Quellenprofile: ${report.sourceProfiles} · " +
                    "Inserate: ${report.listings} · Gespeicherte Suchen: ${report.savedSearches} · " +
                    "Preislisten: ${report.manualPriceLists} · Medien: ${report.mediaFiles}",
            )
        }
    }
}
