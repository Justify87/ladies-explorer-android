package de.justify87.ladiesexplorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Tab(val title: String) {
    Discover("Entdecken"),
    Search("Suche"),
    Map("Karte"),
    Favorites("Favoriten"),
    Settings("Einstellungen"),
}

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
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = Tab.entries
    val mapStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(tabs[selected], search.profileOpen) {
        if (tabs[selected] == Tab.Map && !search.profileOpen) viewModel.refreshMap()
    }

    // Refresh the displayed session size while the capture settings are visible.
    LaunchedEffect(selected, home.httpCaptureMode, search.profileOpen) {
        if (tabs[selected] == Tab.Settings && !search.profileOpen && home.httpCaptureMode != CaptureMode.OFF) {
            while (true) {
                delay(2_000)
                viewModel.refreshCaptureStatus()
            }
        }
    }

    BackHandler(enabled=search.profileOpen) { viewModel.closeProfile() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (search.profileOpen) "Profil" else "Ladies Explorer") },
                navigationIcon = {
                    if (search.profileOpen) {
                        IconButton(onClick=viewModel::closeProfile) {
                            Icon(Icons.Default.ArrowBack,contentDescription="Zurück")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!search.profileOpen) {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected=selected==index,
                            onClick={
                                selected=index
                                when (tab) {
                                    Tab.Discover,Tab.Favorites -> viewModel.refreshDiscovery()
                                    Tab.Search -> viewModel.refreshSearchDefinitions()
                                    Tab.Map -> Unit // entry effect also handles restored navigation
                                    Tab.Settings -> viewModel.refresh()
                                }
                            },
                            icon={
                                when (tab) {
                                    Tab.Discover -> Icon(Icons.Default.FavoriteBorder,contentDescription=null)
                                    Tab.Search -> Icon(Icons.Default.Search,contentDescription=null)
                                    Tab.Map -> Icon(Icons.Default.Map,contentDescription=null)
                                    Tab.Favorites -> Icon(Icons.Default.Favorite,contentDescription=null)
                                    Tab.Settings -> Icon(Icons.Default.Settings,contentDescription=null)
                                }
                            },
                            label={Text(tab.title)},
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (search.profileOpen) {
            ProfileDetailScreen(
                state=search,
                onFavorite=viewModel::toggleFavorite,
                onPreference=viewModel::choosePreference,
                onConfirmIdentity=viewModel::confirmIdentity,
                onCancelArchive=viewModel::cancelMediaArchive,
                modifier=modifier,
            )
        } else {
            when(tabs[selected]) {
                Tab.Discover -> DiscoverScreen(
                    state=discovery,
                    onRefresh=viewModel::refreshDiscovery,
                    onSkip=viewModel::skipCard,
                    onChoose=viewModel::choosePreference,
                    onFavorite=viewModel::toggleFavorite,
                    onProfile=viewModel::loadProfile,
                    modifier=modifier,
                )
                Tab.Search -> SearchScreen(
                    state=search,
                    onCity=viewModel::setSearchCity,
                    onRadius=viewModel::setSearchRadius,
                    onCategory=viewModel::setCategory,
                    onAge=viewModel::setAgeRange,
                    onHeight=viewModel::setHeightRange,
                    onPages=viewModel::setMaxPages,
                    onToggleFilter=viewModel::toggleFilter,
                    onSearch={viewModel.startSearch()},
                    onCancelSearch=viewModel::cancelActiveSearch,
                    onSave=viewModel::saveCurrentSearch,
                    onNewPreset=viewModel::createNewSavedSearch,
                    onLoadSaved=viewModel::loadSavedSearch,
                    onRunSaved={viewModel.startSearch(it)},
                    onDeleteSaved=viewModel::deleteSavedSearch,
                    onAcknowledge=viewModel::acknowledgeSavedSearch,
                    onShowSavedResults=viewModel::showSavedResults,
                    onCloseSavedResults=viewModel::closeSavedResults,
                    onLoadMoreSavedResults=viewModel::loadMoreSavedResults,
                    onProfile=viewModel::loadProfile,
                    modifier=modifier,
                )
                Tab.Map -> mapStateHolder.SaveableStateProvider("map") {
                    MapScreen(
                        state=map,
                        onRefresh=viewModel::refreshMap,
                        onResolve=viewModel::resolveMapLocations,
                        onCancel=viewModel::cancelMapResolution,
                        onFavoritesOnly=viewModel::setMapFavoritesOnly,
                        onProfile=viewModel::loadProfile,
                        modifier=modifier,
                    )
                }
                Tab.Favorites -> FavoritesScreen(
                    state=discovery,
                    onRefresh=viewModel::refreshDiscovery,
                    onProfile=viewModel::loadProfile,
                    onFavorite=viewModel::toggleFavorite,
                    onLoadMore=viewModel::loadMoreFavorites,
                    modifier=modifier,
                )
                Tab.Settings -> SettingsScreen(
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
