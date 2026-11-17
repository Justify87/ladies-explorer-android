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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Tab(val title: String) {
    Discover("Entdecken"),
    Search("Suche"),
    Settings("Einstellungen"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LadiesApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = Tab.entries

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Ladies Explorer") })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
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
                    detail = "Hier entsteht der Swipe-Stapel. Das technische Grundgerüst enthält keine Demo-Profile.",
                )
                Tab.Search -> Placeholder(
                    title = "Suche",
                    detail = "Online-Suche und Filter werden erst in den Migrationsmeilensteinen implementiert.",
                )
                Tab.Settings -> {
                    Text("Technischer Selbsttest", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Die Android-Oberfläche kommuniziert über UniFFI mit Rust und SQLite.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.loading) CircularProgressIndicator()
                    if (state.error != null) {
                        Text(
                            "Fehler: " + state.error,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (state.coreVersion != null) {
                        Text("Rust-API: v" + state.coreVersion)
                        Text("Gespeicherte Testeinträge: " + state.storedNotes)
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.draftNote,
                        onValueChange = viewModel::updateNote,
                        label = { Text("SQLite-Testwert") },
                        singleLine = true,
                    )
                    Button(
                        enabled = !state.loading && state.error == null,
                        onClick = viewModel::saveTestNote,
                    ) {
                        Text("Testwert speichern")
                    }
                    Button(enabled = !state.loading, onClick = viewModel::refresh) {
                        Text("Rust-Status aktualisieren")
                    }
                    if (state.lastSavedNote != null) {
                        Text("Zuletzt gespeichert: " + state.lastSavedNote)
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
            Text("Technisches Skelett", style = MaterialTheme.typography.titleLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start,
            )
        }
    }
}
