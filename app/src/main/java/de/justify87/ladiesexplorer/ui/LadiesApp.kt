package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = Tab.entries

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Ladies Explorer") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = {
                            selected = index
                            if (tab == Tab.Settings) viewModel.refresh()
                        },
                        icon = {
                            when (tab) {
                                Tab.Discover -> Icon(Icons.Default.FavoriteBorder, contentDescription = null)
                                Tab.Search -> Icon(Icons.Default.Search, contentDescription = null)
                                Tab.Settings -> Icon(Icons.Default.Settings, contentDescription = null)
                            }
                        },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tabs[selected]) {
                Tab.Discover -> Placeholder(
                    title = "Entdecken",
                    detail = "Die Profildatenbank ist vorbereitet. Der Swipe-Stapel folgt in einem späteren Migrationsschritt.",
                )
                Tab.Search -> SearchSmokeScreen(viewModel)
                Tab.Settings -> {
                    Text("Lokale Datenbasis", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Personen, Quellenprofile und Inserate werden in der Rust-eigenen SQLite-Datenbank verwaltet.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.loading) CircularProgressIndicator()
                    if (state.error != null) {
                        Text("Fehler: " + state.error, color = MaterialTheme.colorScheme.error)
                    } else if (state.schemaVersion != null) {
                        Text("Datenbankschema: v" + state.schemaVersion)
                        Text("Personen: " + state.personCount)
                        Text("Quellenprofile: " + state.sourceProfileCount)
                        Text("Inserate: " + state.listingCount)
                    }
                    Button(enabled = !state.loading, onClick = viewModel::refresh) {
                        Text("Datenbankstatus aktualisieren")
                    }
                    Button(enabled = !state.loading, onClick = onPickLegacyBundle) {
                        Text("Web-App-Datenarchiv importieren")
                    }
                    Text(
                        "Das ZIP muss mit tools/export-legacy.sh aus dem bisherigen Web-App-Datenordner erstellt worden sein. Ein Import in eine bereits befüllte Datenbank wird abgelehnt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Debug-Protokoll", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Fehler und HTTP-Antworten werden lokal protokolliert. Bei nicht lesbaren " +
                            "Seiten enthält das Protokoll einen Ausschnitt des echten HTML. " +
                            "Es kann personenbezogene Daten enthalten. Bitte nur bewusst teilen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.diagnosticLastError?.let { error ->
                        Text(
                            "Debug-Logging fehlgeschlagen: $error",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!state.diagnosticAvailable) {
                        Text(
                            "Noch keine lesbare Log-Datei vorhanden.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "Log-Datei: " + state.diagnosticSizeBytes + " Bytes",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onSaveDiagnostics, enabled = state.diagnosticAvailable) {
                        Text("Debug-Log als Datei speichern")
                    }
                    Button(onClick = onShareDiagnostics, enabled = state.diagnosticAvailable) {
                        Text("Debug-Log teilen")
                    }
                    state.lastImport?.let { report ->
                        Text("Import abgeschlossen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Personen: " + report.persons +
                                " · Quellenprofile: " + report.sourceProfiles +
                                " · Inserate: " + report.listings +
                                " · Gespeicherte Suchen: " + report.savedSearches +
                                " · Manuelle Preislisten: " + report.manualPriceLists +
                                " · Mediendateien: " + report.mediaFiles,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Placeholder(title: String, detail: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(4.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Migration läuft", style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
