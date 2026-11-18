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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Tab(val title: String) {
    Discover("Entdecken"),
    Search("Suche"),
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
) {
    val home by viewModel.uiState.collectAsStateWithLifecycle()
    val search by viewModel.searchUiState.collectAsStateWithLifecycle()
    val discovery by viewModel.discoverState.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = Tab.entries

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
                                    Tab.Settings -> viewModel.refresh()
                                }
                            },
                            icon={
                                when (tab) {
                                    Tab.Discover -> Icon(Icons.Default.FavoriteBorder,contentDescription=null)
                                    Tab.Search -> Icon(Icons.Default.Search,contentDescription=null)
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
                    onSave=viewModel::saveCurrentSearch,
                    onLoadSaved=viewModel::loadSavedSearch,
                    onRunSaved={viewModel.startSearch(it)},
                    onDeleteSaved=viewModel::deleteSavedSearch,
                    onAcknowledge=viewModel::acknowledgeSavedSearch,
                    onProfile=viewModel::loadProfile,
                    modifier=modifier,
                )
                Tab.Favorites -> FavoritesScreen(
                    state=discovery,
                    onRefresh=viewModel::refreshDiscovery,
                    onProfile=viewModel::loadProfile,
                    onFavorite=viewModel::toggleFavorite,
                    modifier=modifier,
                )
                Tab.Settings -> SettingsScreen(
                    state=home,
                    onRefresh=viewModel::refresh,
                    onPickLegacyBundle=onPickLegacyBundle,
                    onSaveDiagnostics=onSaveDiagnostics,
                    onShareDiagnostics=onShareDiagnostics,
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
